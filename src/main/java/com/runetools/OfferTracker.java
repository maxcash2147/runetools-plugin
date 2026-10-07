package com.runetools;

import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;

/**
 * Turns the stream of {@code GrandExchangeOfferChanged}-derived offers into completed-fill
 * {@link Trade}s:
 *
 * <ul>
 *   <li>keep a per-slot {@link SavedOffer} baseline;</li>
 *   <li>emit a trade only for a POSITIVE (quantitySold, spent) delta, so multi-tick partial
 *       fills are each counted exactly once and never re-counted;</li>
 *   <li>a slot with no baseline yet (fresh start / first sight) is adopted silently - we can't
 *       count fills that happened before we were tracking;</li>
 *   <li>a changed item/price/total is treated as a fresh offer (desync guard);</li>
 *   <li>EMPTY clears the slot's baseline. The caller drops the login-time EMPTY burst.</li>
 * </ul>
 *
 * <p>Baselines are held <b>in memory</b> (a fresh array per plugin start), deliberately NOT in the
 * RSProfile config: round-tripping this custom object through {@code ConfigManager} proved
 * unreliable - the read came back {@code null}, so every fill looked like a fresh baseline and no
 * trade was ever emitted. The only cost of in-memory state is that baselines don't survive a client
 * restart (an offer already partly filled before startup is adopted, not back-counted), which is an
 * acceptable trade for correct, reliable capture.
 */
class OfferTracker
{
	private static final int SLOTS = 8;

	private final SavedOffer[] baselines = new SavedOffer[SLOTS];

	static boolean isBuy(GrandExchangeOfferState s)
	{
		return s == GrandExchangeOfferState.BUYING
			|| s == GrandExchangeOfferState.BOUGHT
			|| s == GrandExchangeOfferState.CANCELLED_BUY;
	}

	private SavedOffer get(int slot)
	{
		return (slot >= 0 && slot < SLOTS) ? baselines[slot] : null;
	}

	private void set(int slot, SavedOffer o)
	{
		if (slot >= 0 && slot < SLOTS)
		{
			baselines[slot] = o;
		}
	}

	/**
	 * @return a Trade for a newly-filled quantity, or {@code null} if this event yields none.
	 *         The caller must drop the login-time EMPTY burst (state==EMPTY while not LOGGED_IN)
	 *         before calling this.
	 */
	Trade process(int slot, GrandExchangeOffer offer, String rsn, String accountHash)
	{
		GrandExchangeOfferState state = offer.getState();

		if (state == GrandExchangeOfferState.EMPTY)
		{
			set(slot, null);   // slot collected/cancelled in-game
			return null;
		}

		SavedOffer cur = SavedOffer.from(offer);
		SavedOffer prev = get(slot);

		if (prev == null)
		{
			set(slot, cur);    // fresh baseline; emit nothing (can't count pre-tracking fills)
			return null;
		}

		boolean newOffer = prev.getItemId() != cur.getItemId()
			|| prev.getPrice() != cur.getPrice()
			|| prev.getTotalQuantity() != cur.getTotalQuantity();

		int prevQty = newOffer ? 0 : prev.getQuantitySold();
		long prevSpent = newOffer ? 0 : prev.getSpent();
		int qtyDiff = cur.getQuantitySold() - prevQty;
		long spentDiff = cur.getSpent() - prevSpent;

		set(slot, cur);

		if (qtyDiff <= 0 || spentDiff <= 0)
		{
			return null;
		}

		// Exact integer half-up rounding (both diffs are > 0 here): avoids float precision loss.
		long unitPrice = (spentDiff + qtyDiff / 2) / qtyDiff;
		String side = isBuy(state) ? "buy" : "sell";
		String extId = slot + ":" + cur.getItemId() + ":" + side + ":" + cur.getQuantitySold();
		return new Trade(cur.getItemId(), side, qtyDiff, unitPrice, System.currentTimeMillis(),
			extId, rsn, accountHash);
	}
}
