package com.runetools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Assert;
import org.junit.Test;

/** The unsynced-trade queue persisted in RuneLite's config must survive the int-to-long price change. */
public class TradeTest
{
	@Test
	public void queueSavedBeforeTheLongPriceChangeStillLoads()
	{
		// Exactly what savePending() wrote when price was an int (no accountHash on older entries).
		JsonObject old = new JsonParser().parse("{\"itemId\":4151,\"side\":\"buy\",\"qty\":3,"
			+ "\"price\":1500000,\"tsMillis\":1720000000000,\"extId\":\"0:4151:buy:3\",\"rsn\":\"Zezima\"}")
			.getAsJsonObject();
		Trade t = Trade.fromPendingJson(old);
		Assert.assertEquals(4151, t.getItemId());
		Assert.assertEquals(3, t.getQty());
		Assert.assertEquals(1_500_000L, t.getPrice());
		Assert.assertEquals("Zezima", t.getRsn());
		Assert.assertNull(t.getAccountHash());
	}

	@Test
	public void longPriceRoundTrips()
	{
		Trade t = new Trade(20997, "sell", 1, 4_000_000_000L, 1720000000000L, "3:20997:sell:1", null, "123");
		String saved = t.toPendingJson().toString();
		Assert.assertTrue(saved.contains("\"price\":4000000000"));
		Assert.assertEquals(t, Trade.fromPendingJson(new JsonParser().parse(saved).getAsJsonObject()));
	}
}
