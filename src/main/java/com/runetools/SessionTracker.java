package com.runetools;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks this login session's Grand Exchange activity for the panel: a running realized-profit
 * estimate (FIFO-matched buys -> sells, minus the 2% GE tax) plus the most recent fills. This is a
 * quick in-game glance only - the site keeps the authoritative, tax-exact P/L history.
 *
 * <p>Only ever touched from the client thread (the offer-changed handler), so it needs no locking.
 */
class SessionTracker
{
	private static final int MAX_RECENT = 12;
	private static final long TAX_CAP = 5_000_000L;   // GE tax caps at 5m per item

	private final Map<Integer, Deque<int[]>> buyLots = new HashMap<>();   // itemId -> queue of {qtyRemaining, unitPrice}
	private final Deque<Trade> recent = new ArrayDeque<>();
	private long realizedProfit;

	void record(Trade t)
	{
		if (t == null)
		{
			return;
		}
		if ("buy".equals(t.getSide()))
		{
			buyLots.computeIfAbsent(t.getItemId(), k -> new ArrayDeque<>())
				.addLast(new int[]{t.getQty(), t.getPrice()});
		}
		else if ("sell".equals(t.getSide()))
		{
			final long taxPerItem = Math.min(t.getPrice() / 50L, TAX_CAP);   // 2% GE tax per item, capped
			int qty = t.getQty();
			final Deque<int[]> lots = buyLots.get(t.getItemId());
			while (qty > 0 && lots != null && !lots.isEmpty())
			{
				final int[] lot = lots.peekFirst();
				final int take = Math.min(qty, lot[0]);
				realizedProfit += ((long) t.getPrice() - taxPerItem - lot[1]) * take;
				lot[0] -= take;
				qty -= take;
				if (lot[0] <= 0)
				{
					lots.removeFirst();
				}
			}
			// any sell qty with no matching buy this session is ignored (cost basis unknown)
		}
		recent.addFirst(t);
		while (recent.size() > MAX_RECENT)
		{
			recent.removeLast();
		}
	}

	long realizedProfit()
	{
		return realizedProfit;
	}

	List<Trade> recent()
	{
		return new ArrayList<>(recent);   // snapshot for the EDT
	}
}
