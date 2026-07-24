package com.runetools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import net.runelite.client.config.ConfigManager;

/**
 * Reads the player's <a href="https://github.com/Azderi/osrs-tcg">OSRS TCG</a> collection (a separate
 * Plugin Hub plugin) so the site can host a card hiscores + collection replica.
 *
 * <p>The TCG plugin persists one immutable state object per RuneScape profile in its own config group
 * {@code osrstcg}, key {@code state}, as {@code RLTCG_v2:}&lt;base64(xor(gzip(json)))&gt; with an
 * unkeyed SHA-256 of that stored blob in key {@code hash}. We read that group but NEVER write it - the
 * TCG plugin stays the sole owner of its state. The XOR salt is obfuscation only (public in the
 * BSD-2-Clause plugin source), so mirroring it here carries no secret. See
 * {@code com.osrstcg.persist.TcgStateStorageEncoding} / {@code TcgStateCodec} for the source of truth.
 *
 * <p>Everything here is a pure config read + decode + parse (no varps, widgets or DB tables), so unlike
 * {@link Slayer}/{@link CollectionLog} it does NOT require the client thread and runs on the scheduler.
 */
final class Tcg
{
	private static final String TCG_GROUP = "osrstcg";
	private static final String STATE_KEY = "state";
	private static final String HASH_KEY = "hash";
	private static final String STORAGE_PREFIX = "RLTCG_v2:";
	private static final String DEBUG_PULL_PREFIX = "DEBUG_";   // provenance tag on ::tcg-give / debug pulls

	// Mirrors com.osrstcg.persist.TcgStateStorageEncoding#XOR_SALT ("RLTCG|osrs-tcg!"). Obfuscation only.
	private static final byte[] XOR_SALT = {
		0x52, 0x4c, 0x54, 0x43, 0x47, 0x7c, 0x6f, 0x73, 0x72, 0x73, 0x2d, 0x74, 0x63, 0x67, 0x21,
	};

	// The reward tuning the plugin ships with; any deviation means the account earned at non-standard
	// rates, so we flag it (custom_rates) and the site can rank it separately for a fair leaderboard.
	private static final int DEFAULT_FOIL_CHANCE = 1;
	private static final double DEFAULT_MULTIPLIER = 1.0d;

	// Defensive bounds on (locally editable) config so a corrupt/hand-edited blob can't blow up a payload.
	private static final int MAX_CARDS = 5000;             // the real pool is a few hundred names
	private static final long MAX_CARD_QTY = 1_000_000L;   // per-card copy count sanity cap

	private Tcg()
	{
	}

	/**
	 * The parsed TCG collection, split so a frequent credit change ships a tiny economy-only payload while
	 * the (larger) card list is re-sent only when the collection itself changes ({@link #cards} omitted =
	 * server keeps the last one). {@link #economy} always carries the leaderboard aggregates.
	 */
	static final class Data
	{
		final JsonObject economy;   // schema, credits, opened_packs, total_credits_gained, unique/total/foil counts, custom_rates
		final JsonArray cards;      // [{name, qty, foil}] - qty = total copies, foil = foil copies
		final int econHash;         // change hash of economy (credits tick often)
		final int cardsHash;        // change hash of the card multiset (changes only on pack open / trade)

		private Data(JsonObject economy, JsonArray cards, int econHash, int cardsHash)
		{
			this.economy = economy;
			this.cards = cards;
			this.econHash = econHash;
			this.cardsHash = cardsHash;
		}
	}

	/**
	 * Read + decode the active profile's TCG state, or null when the TCG plugin isn't installed, has no
	 * state for this profile, or the integrity hash / decode fails (we simply skip rather than sync junk).
	 * Safe to call from any thread.
	 */
	static Data read(ConfigManager configManager)
	{
		String stored = readProfileScoped(configManager, STATE_KEY);
		if (stored == null || stored.isEmpty())
		{
			return null;   // TCG plugin absent or nothing saved for this profile
		}

		// If the plugin wrote an integrity hash, honour it: a mismatch means a corrupt or mid-write blob,
		// so skip this cycle (the next read is almost always consistent) rather than upload garbage.
		String expectedHash = readProfileScoped(configManager, HASH_KEY);
		if (expectedHash != null && !expectedHash.isEmpty())
		{
			String actual = sha256Hex(stored);
			if (actual == null || !actual.equalsIgnoreCase(expectedHash.trim()))
			{
				return null;
			}
		}

		return readStored(stored);
	}

	/**
	 * Decode + parse a raw {@code RLTCG_v2:} blob (the config integrity hash is checked by {@link #read}).
	 * Package-private for round-trip testing. Returns null on any decode/parse failure.
	 */
	static Data readStored(String stored)
	{
		if (stored == null || stored.isEmpty())
		{
			return null;
		}
		String json = decode(stored);
		if (json.isEmpty())
		{
			return null;
		}

		JsonObject root;
		try
		{
			JsonElement el = new JsonParser().parse(json);
			if (!el.isJsonObject())
			{
				return null;
			}
			root = el.getAsJsonObject();
		}
		catch (RuntimeException ex)
		{
			return null;
		}

		return build(root);
	}

	private static Data build(JsonObject root)
	{
		// Aggregate owned copies per card name (foil folded in), excluding debug-provenance cards so the
		// hiscores only counts legitimately-pulled cards - mirrors the plugin's own web-share filtering.
		Map<String, long[]> byName = new LinkedHashMap<>();   // name -> [total, foil]
		aggregateCardEntries(root.getAsJsonArray("cardEntries"), byName);
		if (byName.isEmpty())
		{
			// Older TCG profiles (pre-cardEntries) stored one row per copy under cardInstances.
			aggregateCardInstances(root.getAsJsonArray("cardInstances"), byName);
		}

		List<Map.Entry<String, long[]>> sorted = new ArrayList<>(byName.entrySet());
		sorted.sort(Comparator.comparing(Map.Entry::getKey, String.CASE_INSENSITIVE_ORDER));

		JsonArray cards = new JsonArray();
		long totalOwned = 0;
		int uniqueOwned = 0;
		int foilUnique = 0;
		for (Map.Entry<String, long[]> e : sorted)
		{
			if (cards.size() >= MAX_CARDS)
			{
				break;
			}
			long total = Math.min(MAX_CARD_QTY, e.getValue()[0]);
			long foil = Math.min(total, e.getValue()[1]);
			if (total <= 0)
			{
				continue;
			}
			JsonObject c = new JsonObject();
			c.addProperty("name", e.getKey());
			c.addProperty("qty", total);
			if (foil > 0)
			{
				c.addProperty("foil", foil);
			}
			cards.add(c);
			totalOwned += total;
			uniqueOwned++;
			if (foil > 0)
			{
				foilUnique++;
			}
		}

		boolean customRates = isCustomRates(root);

		JsonObject economy = new JsonObject();
		economy.addProperty("schema", getLong(root, "schemaVersion", 0));
		economy.addProperty("credits", Math.max(0, getLong(root, "credits", 0)));
		economy.addProperty("opened_packs", Math.max(0, getLong(root, "openedPacks", 0)));
		economy.addProperty("total_credits_gained", Math.max(0, getLong(root, "totalCreditsGained", 0)));
		economy.addProperty("unique_owned", uniqueOwned);
		economy.addProperty("total_owned", totalOwned);
		economy.addProperty("foil_unique", foilUnique);
		economy.addProperty("custom_rates", customRates);
		long createdAt = getLong(root, "profileCreatedAtUnix", 0);
		if (createdAt > 0)
		{
			economy.addProperty("profile_created_at", createdAt);
		}

		return new Data(economy, cards, economy.toString().hashCode(), cards.toString().hashCode());
	}

	/** Current schema: {@code cardEntries:[{cardName, variants:[{foil?, pulledBy?, quantity?}]}]}. */
	private static void aggregateCardEntries(JsonArray entries, Map<String, long[]> byName)
	{
		if (entries == null)
		{
			return;
		}
		for (JsonElement el : entries)
		{
			if (el == null || !el.isJsonObject())
			{
				continue;
			}
			JsonObject entry = el.getAsJsonObject();
			String name = getString(entry, "cardName");
			if (name == null || name.trim().isEmpty())
			{
				continue;
			}
			name = name.trim();
			JsonArray variants = entry.getAsJsonArray("variants");
			if (variants == null)
			{
				continue;
			}
			for (JsonElement ve : variants)
			{
				if (ve == null || !ve.isJsonObject())
				{
					continue;
				}
				JsonObject v = ve.getAsJsonObject();
				if (isDebugPull(getString(v, "pulledBy")))
				{
					continue;   // ::tcg-give / debug-logging pulls don't count on the hiscores
				}
				long qty = Math.max(0, getLong(v, "quantity", 1));   // absent quantity = one copy
				if (qty <= 0)
				{
					continue;
				}
				boolean foil = getBool(v, "foil");
				add(byName, name, qty, foil ? qty : 0);
			}
		}
	}

	/** Legacy schema: {@code cardInstances:[{cardName, foil, pulledBy}]}, one row per copy. */
	private static void aggregateCardInstances(JsonArray instances, Map<String, long[]> byName)
	{
		if (instances == null)
		{
			return;
		}
		for (JsonElement el : instances)
		{
			if (el == null || !el.isJsonObject())
			{
				continue;
			}
			JsonObject row = el.getAsJsonObject();
			String name = getString(row, "cardName");
			if (name == null || name.trim().isEmpty() || isDebugPull(getString(row, "pulledBy")))
			{
				continue;
			}
			boolean foil = getBool(row, "foil");
			add(byName, name.trim(), 1, foil ? 1 : 0);
		}
	}

	private static void add(Map<String, long[]> byName, String name, long total, long foil)
	{
		long[] cur = byName.computeIfAbsent(name, n -> new long[2]);
		cur[0] += total;
		cur[1] += foil;
	}

	private static boolean isDebugPull(String pulledBy)
	{
		return pulledBy != null && pulledBy.startsWith(DEBUG_PULL_PREFIX);
	}

	/** True if foil chance or any credit multiplier was tuned away from the plugin defaults. */
	private static boolean isCustomRates(JsonObject root)
	{
		int foil = (int) getLong(root, "foilChancePercent", DEFAULT_FOIL_CHANCE);
		if (foil != DEFAULT_FOIL_CHANCE)
		{
			return true;
		}
		return multiplierChanged(root, "killCreditMultiplier")
			|| multiplierChanged(root, "levelUpCreditMultiplier")
			|| multiplierChanged(root, "xpCreditMultiplier");
	}

	private static boolean multiplierChanged(JsonObject root, String key)
	{
		JsonElement e = root.get(key);
		if (e == null || e.isJsonNull())
		{
			return false;   // absent = default
		}
		try
		{
			return Math.abs(e.getAsDouble() - DEFAULT_MULTIPLIER) > 1e-9d;
		}
		catch (RuntimeException ex)
		{
			return false;
		}
	}

	// -- config + codec helpers --------------------------------------------- //

	/** Read the TCG group's per-RS-profile value, falling back to the group scope (as the TCG plugin does). */
	private static String readProfileScoped(ConfigManager configManager, String key)
	{
		String profileKey = configManager.getRSProfileKey();
		if (profileKey == null || profileKey.isEmpty())
		{
			return configManager.getConfiguration(TCG_GROUP, key);
		}
		return configManager.getConfiguration(TCG_GROUP, profileKey, key);
	}

	private static String decode(String stored)
	{
		if (stored.length() <= STORAGE_PREFIX.length() || !stored.startsWith(STORAGE_PREFIX))
		{
			return "";
		}
		try
		{
			byte[] blob = Base64.getDecoder().decode(stored.substring(STORAGE_PREFIX.length()));
			for (int i = 0; i < blob.length; i++)
			{
				blob[i] ^= XOR_SALT[i % XOR_SALT.length];
			}
			try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(blob)))
			{
				return new String(gz.readAllBytes(), StandardCharsets.UTF_8);
			}
		}
		catch (Exception ex)
		{
			return "";   // not our format / truncated / bad gzip - treat as no data
		}
	}

	private static String sha256Hex(String s)
	{
		try
		{
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] digest = md.digest((s == null ? "" : s).getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder(digest.length * 2);
			for (byte b : digest)
			{
				sb.append(String.format("%02x", b & 0xff));
			}
			return sb.toString();
		}
		catch (NoSuchAlgorithmException ex)
		{
			return null;
		}
	}

	// -- null-safe JSON accessors (config is user-editable, so never trust a field's presence/type) -- //

	private static long getLong(JsonObject o, String key, long fallback)
	{
		try
		{
			JsonElement e = o.get(key);
			return e != null && !e.isJsonNull() ? e.getAsLong() : fallback;
		}
		catch (RuntimeException ex)
		{
			return fallback;
		}
	}

	private static boolean getBool(JsonObject o, String key)
	{
		try
		{
			JsonElement e = o.get(key);
			return e != null && !e.isJsonNull() && e.getAsBoolean();
		}
		catch (RuntimeException ex)
		{
			return false;
		}
	}

	private static String getString(JsonObject o, String key)
	{
		try
		{
			JsonElement e = o.get(key);
			return e != null && !e.isJsonNull() ? e.getAsString() : null;
		}
		catch (RuntimeException ex)
		{
			return null;
		}
	}
}
