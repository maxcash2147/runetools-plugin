package com.runetools;

import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Assert;
import org.junit.Test;

/**
 * Fill-delta capture, including prices past the old int range: since RuneLite 1.13
 * {@link GrandExchangeOffer#getPrice()} and {@link GrandExchangeOffer#getSpent()} are long, and the
 * tracker must carry them through to the {@link Trade} without narrowing.
 */
public class OfferTrackerTest
{
	private static GrandExchangeOffer offer(GrandExchangeOfferState state, int itemId, long price,
		int total, int sold, long spent)
	{
		return new GrandExchangeOffer()
		{
			@Override
			public int getQuantitySold()
			{
				return sold;
			}

			@Override
			public int getItemId()
			{
				return itemId;
			}

			@Override
			public int getTotalQuantity()
			{
				return total;
			}

			@Override
			public long getPrice()
			{
				return price;
			}

			@Override
			public long getSpent()
			{
				return spent;
			}

			@Override
			public GrandExchangeOfferState getState()
			{
				return state;
			}
		};
	}

	@Test
	public void firstSightIsAdoptedSilently()
	{
		OfferTracker t = new OfferTracker();
		Assert.assertNull(t.process(0, offer(GrandExchangeOfferState.BUYING, 4151, 1_500_000L, 5, 2, 3_000_000L),
			"Zezima", "1"));
	}

	@Test
	public void priceAboveIntRangeIsCarriedThroughExactly()
	{
		long price = 3_000_000_000L;   // past Integer.MAX_VALUE
		OfferTracker t = new OfferTracker();
		t.process(2, offer(GrandExchangeOfferState.BUYING, 20997, price, 2, 0, 0L), "Zezima", "1");

		Trade first = t.process(2, offer(GrandExchangeOfferState.BUYING, 20997, price, 2, 1, price), "Zezima", "1");
		Assert.assertNotNull(first);
		Assert.assertEquals("buy", first.getSide());
		Assert.assertEquals(1, first.getQty());
		Assert.assertEquals(price, first.getPrice());

		Trade second = t.process(2, offer(GrandExchangeOfferState.BOUGHT, 20997, price, 2, 2, 2 * price), "Zezima", "1");
		Assert.assertNotNull(second);
		Assert.assertEquals(1, second.getQty());
		Assert.assertEquals(price, second.getPrice());
		Assert.assertEquals("2:20997:buy:2", second.getExtId());
	}

	@Test
	public void unitPriceRoundsHalfUp()
	{
		OfferTracker t = new OfferTracker();
		t.process(1, offer(GrandExchangeOfferState.SELLING, 561, 200L, 10, 0, 0L), null, null);
		Trade a = t.process(1, offer(GrandExchangeOfferState.SELLING, 561, 200L, 10, 2, 401L), null, null);
		Assert.assertEquals(201L, a.getPrice());   // 200.5 -> 201
		Trade b = t.process(1, offer(GrandExchangeOfferState.SELLING, 561, 200L, 10, 5, 1001L), null, null);
		Assert.assertEquals(200L, b.getPrice());   // 600 / 3 = 200
		Assert.assertEquals("sell", b.getSide());
	}

	@Test
	public void noTradeWithoutAPositiveDelta()
	{
		OfferTracker t = new OfferTracker();
		t.process(0, offer(GrandExchangeOfferState.BUYING, 4151, 1_500_000L, 5, 1, 1_500_000L), null, null);
		Assert.assertNull(t.process(0, offer(GrandExchangeOfferState.BUYING, 4151, 1_500_000L, 5, 1, 1_500_000L),
			null, null));
	}
}
