package com.runetools;

import org.junit.Assert;
import org.junit.Test;

public class SessionTrackerTest
{
	private static Trade trade(String side, int qty, long price)
	{
		return new Trade(20997, side, qty, price, 0L, side + qty, "Zezima", "1");
	}

	@Test
	public void realizedProfitIsExactPastTheIntRange()
	{
		SessionTracker s = new SessionTracker();
		s.record(trade("buy", 2, 3_000_000_000L));
		s.record(trade("sell", 2, 3_100_000_000L));
		// Tax is 2% per item capped at 5m: (3.1b - 5m - 3.0b) x 2.
		Assert.assertEquals(190_000_000L, s.realizedProfit());
	}

	@Test
	public void sellWithoutABuyThisSessionIsIgnored()
	{
		SessionTracker s = new SessionTracker();
		s.record(trade("sell", 1, 1_000L));
		Assert.assertEquals(0L, s.realizedProfit());
		Assert.assertEquals(1, s.recent().size());
	}
}
