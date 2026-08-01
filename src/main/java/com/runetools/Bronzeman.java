package com.runetools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;

/**
 * Reads the item ids the player has unlocked in the
 * <a href="https://github.com/dennisdev/another-bronzeman-mode">Another Bronzeman Mode</a> RuneLite
 * plugin. That plugin does NOT keep its unlocks in config; it stores them in a per-RS-profile FILE
 * under {@link RuneLite#RUNELITE_DIR}:
 * <pre>
 *   profiles/&lt;profileKey&gt;/another-bronzeman-mode-unlocks.txt
 * </pre>
 * where {@code profileKey} is {@link ConfigManager#getRSProfileKey()} - the same RS-profile key our
 * own {@link ConfigManager} returns for the logged-in character. The file's whole content is a single
 * JSON array of item ids, e.g. {@code [995,4151,11802]}.
 *
 * <p>We read that file but NEVER write it - the Another Bronzeman Mode plugin stays the sole owner of
 * its unlocks. The read is a plain file + JSON parse (no varps, widgets or client calls), so it does
 * NOT need the client thread and can run on the scheduler thread. It is fully defensive: any error
 * (missing file, bad JSON, wrong shape) yields an empty list rather than throwing, and the result is
 * capped so a corrupt/huge file can never blow up a sync payload.
 */
@Slf4j
final class Bronzeman
{
	private static final String UNLOCKS_FILE = "another-bronzeman-mode-unlocks.txt";
	/** Defensive upper bound: the real OSRS item space is far under this; caps a corrupt/huge file. */
	private static final int MAX_IDS = 30000;
	/** Never buffer more than this from the (locally editable) unlocks file. */
	private static final long MAX_FILE_BYTES = 4L * 1024 * 1024;

	// Parsed with JsonParser rather than a Gson instance: the Plugin Hub blocks `new Gson()` (plugins
	// are expected to @Inject the client's), and binding to List<Integer> would need a reflective
	// TypeToken, which hub plugins may not use either. The file is one flat array of ids, so walking
	// the JsonArray by hand is both allowed and stricter about what it accepts.
	private Bronzeman()
	{
	}

	/**
	 * The Another Bronzeman Mode unlocks file for the current RS profile, or {@code null} if we can't
	 * resolve a profile key. Uses {@link ConfigManager#getRSProfileKey()} (the same key our own config
	 * uses); when that is null we fall back to the legacy {@code profiles/<username>/...} layout.
	 */
	static File unlocksFile(ConfigManager configManager, String username)
	{
		String key = configManager != null ? configManager.getRSProfileKey() : null;
		if (key == null || key.isEmpty())
		{
			key = username;   // legacy fallback: profiles/<client.getUsername()>/...
		}
		if (key == null || key.isEmpty())
		{
			return null;
		}
		return new File(RuneLite.RUNELITE_DIR, "profiles/" + key + "/" + UNLOCKS_FILE);
	}

	/**
	 * Read the current character's Bronzeman unlock ids. Returns an empty list (never null, never
	 * throws) when the plugin isn't installed / has no unlocks for this profile, or on any read/parse
	 * error. The result is capped to {@link #MAX_IDS}.
	 */
	static List<Integer> readUnlocks(ConfigManager configManager, String username)
	{
		File f = unlocksFile(configManager, username);
		if (f == null || !f.isFile())
		{
			return new ArrayList<>();
		}
		try
		{
			if (f.length() > MAX_FILE_BYTES)
			{
				log.debug("RuneTools: Bronzeman unlocks file too large ({} bytes) - ignoring", f.length());
				return new ArrayList<>();
			}
			String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
			JsonElement root = new JsonParser().parse(json);
			if (root == null || !root.isJsonArray())
			{
				return new ArrayList<>();
			}
			JsonArray raw = root.getAsJsonArray();
			List<Integer> out = new ArrayList<>(Math.min(raw.size(), MAX_IDS));
			for (JsonElement el : raw)
			{
				// The file is user-editable, so anything that isn't a plain number is skipped rather
				// than trusted - previously Gson would coerce or throw on the whole file.
				if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber())
				{
					continue;
				}
				out.add(el.getAsInt());
				if (out.size() >= MAX_IDS)
				{
					break;
				}
			}
			return out;
		}
		catch (Exception ex)
		{
			// Missing/locked/corrupt file or malformed JSON: treat as no unlocks, never propagate.
			log.debug("RuneTools: could not read Bronzeman unlocks: {}", ex.getMessage());
			return new ArrayList<>();
		}
	}

	/**
	 * A JSON array of the unlock ids for the state payload, or {@code null} when there are none (so the
	 * caller can omit the field). A stable order-independent hash for change detection is computed by
	 * {@link #hash(List)}.
	 */
	static JsonArray toJsonArray(List<Integer> ids)
	{
		if (ids == null || ids.isEmpty())
		{
			return null;
		}
		JsonArray arr = new JsonArray();
		for (Integer id : ids)
		{
			arr.add(id);
		}
		return arr;
	}

	/**
	 * An order-independent hash of the unlock id set, so a NEW unlock (even with no bank change) flips
	 * change detection while a mere re-read in a different order does not.
	 */
	static int hash(List<Integer> ids)
	{
		if (ids == null || ids.isEmpty())
		{
			return 0;
		}
		int h = 1;
		for (Integer id : ids)
		{
			h += (id == null ? 0 : id.intValue());   // sum is order-independent
		}
		return h * 31 + ids.size();
	}
}
