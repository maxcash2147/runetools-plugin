package com.runetools;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;

/**
 * A per-slot snapshot of a GE offer, kept between events so we can compute fill deltas without
 * double-counting (see {@link OfferTracker}). Price and spent are {@code long} to match
 * {@code GrandExchangeOffer} since RuneLite 1.13; narrowing them back to int could overflow on a
 * large offer. Mutable with a no-arg constructor so Gson can round-trip it.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SavedOffer
{
	private int itemId;
	private long price;
	private int totalQuantity;
	private int quantitySold;   // cumulative units filled so far
	private long spent;         // cumulative gp moved so far
	private GrandExchangeOfferState state;

	static SavedOffer from(GrandExchangeOffer o)
	{
		return new SavedOffer(o.getItemId(), o.getPrice(), o.getTotalQuantity(),
			o.getQuantitySold(), o.getSpent(), o.getState());
	}
}
