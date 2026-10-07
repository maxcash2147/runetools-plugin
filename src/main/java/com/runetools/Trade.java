package com.runetools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.Value;

/**
 * One completed (or partially-filled) Grand Exchange transaction, ready to upload.
 *
 * <p>{@code rsn} and {@code accountHash} identify which character made the trade so a batch
 * can be attributed correctly even if the player has since switched accounts; they are used
 * for grouping only and are sent as top-level fields, not repeated per trade.
 */
@Value
public class Trade
{
	int itemId;
	String side;      // "buy" | "sell"
	int qty;          // units filled by THIS delta
	long price;       // gp per item (spentDelta / qtyDelta); long, like GrandExchangeOffer since RuneLite 1.13
	long tsMillis;    // client capture time; the server folds ms -> seconds
	String extId;     // idempotency hint: slot:item:side:cumulativeQty
	String rsn;
	String accountHash;

	/** This trade as one element of the unsynced-trade queue persisted in RuneLite's config. */
	JsonObject toPendingJson()
	{
		JsonObject o = new JsonObject();
		o.addProperty("itemId", itemId);
		o.addProperty("side", side);
		o.addProperty("qty", qty);
		o.addProperty("price", price);
		o.addProperty("tsMillis", tsMillis);
		o.addProperty("extId", extId);
		o.addProperty("rsn", rsn);
		if (accountHash != null)
		{
			o.addProperty("accountHash", accountHash);
		}
		return o;
	}

	/**
	 * Read one element of the persisted queue back. Queues saved before RuneLite 1.13 hold
	 * {@code price} as an int; {@code getAsLong} reads that and the wider form alike, so an
	 * upgrade never drops a backlog of unsynced fills.
	 */
	static Trade fromPendingJson(JsonObject o)
	{
		return new Trade(
			o.get("itemId").getAsInt(), o.get("side").getAsString(),
			o.get("qty").getAsInt(), o.get("price").getAsLong(),
			o.get("tsMillis").getAsLong(), o.get("extId").getAsString(),
			optString(o, "rsn"), optString(o, "accountHash"));
	}

	private static String optString(JsonObject o, String key)
	{
		JsonElement e = o.get(key);
		return e != null && !e.isJsonNull() ? e.getAsString() : null;
	}
}
