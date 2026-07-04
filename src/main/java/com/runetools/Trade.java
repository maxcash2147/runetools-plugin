package com.runetools;

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
	int price;        // gp per item (spentDelta / qtyDelta)
	long tsMillis;    // client capture time; the server folds ms -> seconds
	String extId;     // idempotency hint: slot:item:side:cumulativeQty
	String rsn;
	String accountHash;
}
