package com.runetools;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;

/**
 * A per-slot snapshot of a GE offer, persisted between events (and restarts) via the
 * RuneLite RSProfile config so we can compute fill deltas without double-counting. Mutable
 * with a no-arg constructor so ConfigManager's Gson can round-trip it.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SavedOffer
{
	private int itemId;
	private int price;
	private int totalQuantity;
	private int quantitySold;   // cumulative units filled so far
	private int spent;          // cumulative gp moved so far
	private GrandExchangeOfferState state;

	static SavedOffer from(GrandExchangeOffer o)
	{
		return new SavedOffer(o.getItemId(), o.getPrice(), o.getTotalQuantity(),
			o.getQuantitySold(), o.getSpent(), o.getState());
	}
}
