package com.runetools;

import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPOutputStream;
import org.junit.Assert;
import org.junit.Test;

/**
 * Round-trips real OSRS TCG serialized states (the exact fixtures from the plugin's own
 * {@code TcgStateCodecTest}) through the same {@code RLTCG_v2:} encoding it writes to config, then
 * checks {@link Tcg#readStored(String)} parses them into the payload we upload to the hiscores.
 */
public class TcgTest
{
	/** Replicates com.osrstcg.persist.TcgStateStorageEncoding#encode: gzip -> xor(salt) -> base64, prefixed. */
	private static String encode(String plainJson) throws Exception
	{
		byte[] utf8 = plainJson.getBytes(StandardCharsets.UTF_8);
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try (GZIPOutputStream gz = new GZIPOutputStream(baos))
		{
			gz.write(utf8);
		}
		byte[] blob = baos.toByteArray();
		byte[] salt = {
			0x52, 0x4c, 0x54, 0x43, 0x47, 0x7c, 0x6f, 0x73, 0x72, 0x73, 0x2d, 0x74, 0x63, 0x67, 0x21,
		};
		for (int i = 0; i < blob.length; i++)
		{
			blob[i] ^= salt[i % salt.length];
		}
		return "RLTCG_v2:" + Base64.getEncoder().encodeToString(blob);
	}

	@Test
	public void parsesCurrentSchema6CardEntries() throws Exception
	{
		String json = "{"
			+ "\"schemaVersion\":6,"
			+ "\"credits\":1200,"
			+ "\"openedPacks\":3,"
			+ "\"cardEntries\":[{"
			+ "\"cardName\":\"Abyssal whip\","
			+ "\"variants\":["
			+ "{\"pulledBy\":\"Player\",\"pulledAt\":1710000000000},"
			+ "{\"foil\":true,\"pulledBy\":\"Player\",\"pulledAt\":1710000010000,\"locked\":true}"
			+ "]}],"
			+ "\"totalCreditsGained\":5000,"
			+ "\"profileCreatedAtUnix\":1700000000"
			+ "}";

		Tcg.Data d = Tcg.readStored(encode(json));
		Assert.assertNotNull(d);

		JsonObject e = d.economy;
		Assert.assertEquals(6, e.get("schema").getAsInt());
		Assert.assertEquals(1200L, e.get("credits").getAsLong());
		Assert.assertEquals(3L, e.get("opened_packs").getAsLong());
		Assert.assertEquals(5000L, e.get("total_credits_gained").getAsLong());
		Assert.assertEquals(1, e.get("unique_owned").getAsInt());
		Assert.assertEquals(2L, e.get("total_owned").getAsLong());
		Assert.assertEquals(1, e.get("foil_unique").getAsInt());
		Assert.assertFalse(e.get("custom_rates").getAsBoolean());   // default foil chance + multipliers
		Assert.assertEquals(1_700_000_000L, e.get("profile_created_at").getAsLong());

		Assert.assertEquals(1, d.cards.size());
		JsonObject c = d.cards.get(0).getAsJsonObject();
		Assert.assertEquals("Abyssal whip", c.get("name").getAsString());
		Assert.assertEquals(2L, c.get("qty").getAsLong());
		Assert.assertEquals(1L, c.get("foil").getAsLong());
	}

	@Test
	public void parsesLegacySchema5CardInstancesAndFlagsCustomRates() throws Exception
	{
		// The plugin's own SCHEMA_5_MINIMAL fixture (foilChancePercent 5 -> non-standard rates).
		String json = "{"
			+ "\"schemaVersion\":5,"
			+ "\"credits\":1200,"
			+ "\"openedPacks\":3,"
			+ "\"cardInstances\":["
			+ "{\"id\":\"abc\",\"cardName\":\"Abyssal whip\",\"foil\":false,\"pulledBy\":\"Player\",\"pulledAt\":1710000000000,\"locked\":false},"
			+ "{\"id\":\"def\",\"cardName\":\"Abyssal whip\",\"foil\":true,\"pulledBy\":\"Player\",\"pulledAt\":1710000010000,\"locked\":true}"
			+ "],"
			+ "\"foilChancePercent\":5,"
			+ "\"killCreditMultiplier\":1.0,"
			+ "\"levelUpCreditMultiplier\":1.0,"
			+ "\"xpCreditMultiplier\":1.0,"
			+ "\"totalCreditsGained\":5000"
			+ "}";

		Tcg.Data d = Tcg.readStored(encode(json));
		Assert.assertNotNull(d);
		Assert.assertTrue(d.economy.get("custom_rates").getAsBoolean());
		Assert.assertEquals(1200L, d.economy.get("credits").getAsLong());
		Assert.assertEquals(1, d.economy.get("unique_owned").getAsInt());
		Assert.assertEquals(2L, d.economy.get("total_owned").getAsLong());
		Assert.assertEquals(1, d.economy.get("foil_unique").getAsInt());

		Assert.assertEquals(1, d.cards.size());
		JsonObject c = d.cards.get(0).getAsJsonObject();
		Assert.assertEquals("Abyssal whip", c.get("name").getAsString());
		Assert.assertEquals(2L, c.get("qty").getAsLong());
		Assert.assertEquals(1L, c.get("foil").getAsLong());
	}

	@Test
	public void excludesDebugProvenanceCards() throws Exception
	{
		String json = "{"
			+ "\"schemaVersion\":6,"
			+ "\"credits\":0,"
			+ "\"openedPacks\":0,"
			+ "\"cardEntries\":["
			+ "{\"cardName\":\"Abyssal whip\",\"variants\":["
			+ "{\"pulledBy\":\"Player\"},"
			+ "{\"foil\":true,\"pulledBy\":\"DEBUG_Cheater\"}"
			+ "]},"
			+ "{\"cardName\":\"Dragon claws\",\"variants\":[{\"pulledBy\":\"DEBUG_Cheater\"}]}"
			+ "]}";

		Tcg.Data d = Tcg.readStored(encode(json));
		Assert.assertNotNull(d);
		// The foil debug copy and the fully-debug "Dragon claws" are dropped; one legit whip remains.
		Assert.assertEquals(1, d.cards.size());
		JsonObject c = d.cards.get(0).getAsJsonObject();
		Assert.assertEquals("Abyssal whip", c.get("name").getAsString());
		Assert.assertEquals(1L, c.get("qty").getAsLong());
		Assert.assertNull("no legit foils, so foil key omitted", c.get("foil"));
		Assert.assertEquals(1, d.economy.get("unique_owned").getAsInt());
		Assert.assertEquals(1L, d.economy.get("total_owned").getAsLong());
		Assert.assertEquals(0, d.economy.get("foil_unique").getAsInt());
	}

	@Test
	public void readsLegacyQuantityInVariants() throws Exception
	{
		String json = "{"
			+ "\"schemaVersion\":6,\"credits\":0,\"openedPacks\":0,"
			+ "\"cardEntries\":[{\"cardName\":\"Rune scimitar\",\"variants\":["
			+ "{\"quantity\":2,\"lockedQuantity\":1,\"pulledBy\":\"Player\",\"pulledAt\":100}"
			+ "]}]}";

		Tcg.Data d = Tcg.readStored(encode(json));
		Assert.assertNotNull(d);
		Assert.assertEquals(1, d.cards.size());
		Assert.assertEquals(2L, d.cards.get(0).getAsJsonObject().get("qty").getAsLong());
		Assert.assertEquals(2L, d.economy.get("total_owned").getAsLong());
	}

	@Test
	public void splitHashSeparatesCreditsFromCollection() throws Exception
	{
		String cardsX = "\"cardEntries\":[{\"cardName\":\"Abyssal whip\",\"variants\":[{\"pulledBy\":\"P\"}]}]";
		String cardsY = "\"cardEntries\":[{\"cardName\":\"Dragon claws\",\"variants\":[{\"pulledBy\":\"P\"}]}]";
		Tcg.Data a = Tcg.readStored(encode("{\"schemaVersion\":6,\"credits\":1000,\"openedPacks\":0," + cardsX + "}"));
		Tcg.Data b = Tcg.readStored(encode("{\"schemaVersion\":6,\"credits\":2000,\"openedPacks\":0," + cardsX + "}"));
		Tcg.Data c = Tcg.readStored(encode("{\"schemaVersion\":6,\"credits\":2000,\"openedPacks\":0," + cardsY + "}"));

		// Credits changed, cards didn't: economy hash moves, cards hash stays (tiny economy-only send).
		Assert.assertNotEquals(a.econHash, b.econHash);
		Assert.assertEquals(a.cardsHash, b.cardsHash);
		// Cards changed: cards hash moves (full re-send with the card array).
		Assert.assertNotEquals(b.cardsHash, c.cardsHash);
	}

	@Test
	public void returnsNullForAbsentOrGarbageState()
	{
		Assert.assertNull(Tcg.readStored(null));
		Assert.assertNull(Tcg.readStored(""));
		Assert.assertNull(Tcg.readStored("plain text, not a TCG blob"));
		Assert.assertNull(Tcg.readStored("RLTCG_v2:@@@not-base64@@@"));
	}
}
