package com.runetools;

import org.junit.Assert;
import org.junit.Test;

public class EndpointBackoffTest
{
	private static final long BASE = 20_000L;
	private static final long MAX = 600_000L;

	@Test
	public void oneFailingEndpointDoesNotHoldTheOthers()
	{
		EndpointBackoff b = new EndpointBackoff();
		b.failed("combat-achievements", 1L, true, 0L, BASE, MAX);
		Assert.assertFalse(b.allowed("combat-achievements", 1_000L));
		Assert.assertTrue(b.allowed("state", 1_000L));
		Assert.assertTrue(b.allowed("combat-achievements", BASE));
	}

	@Test
	public void eachFailureDoublesTheWaitUpToTheCeiling()
	{
		EndpointBackoff b = new EndpointBackoff();
		long now = 0L;
		long[] expected = {20_000L, 40_000L, 80_000L, 160_000L, 320_000L, 600_000L, 600_000L};
		for (long wait : expected)
		{
			b.failed("x", 0L, false, now, BASE, MAX);
			Assert.assertEquals(now + wait, b.nextAt("x"));
			now = b.nextAt("x");
		}
	}

	@Test
	public void aSuccessPutsItBackToTheNormalPace()
	{
		EndpointBackoff b = new EndpointBackoff();
		b.failed("x", 0L, false, 0L, BASE, MAX);
		b.failed("x", 0L, false, 20_000L, BASE, MAX);
		b.succeeded("x");
		Assert.assertTrue(b.allowed("x", 20_001L));
		b.failed("x", 0L, false, 30_000L, BASE, MAX);
		Assert.assertEquals(50_000L, b.nextAt("x"));
	}

	@Test
	public void theSamePayloadFailingWithServerErrorsIsGivenUpOn()
	{
		EndpointBackoff b = new EndpointBackoff();
		long now = 0L;
		int tries = 0;
		boolean gaveUp = false;
		while (!gaveUp && tries < 50)
		{
			gaveUp = b.failed("x", 42L, true, now, BASE, MAX);
			tries++;
			now = b.nextAt("x");   // the next try, when the backoff allows it
		}
		Assert.assertTrue(gaveUp);
		Assert.assertTrue(tries >= EndpointBackoff.GIVE_UP_AFTER);
		// ...and counting starts over afterwards.
		Assert.assertFalse(b.failed("x", 42L, true, now, BASE, MAX));
	}

	@Test
	public void serverErrorsForLessThanHalfAnHourGiveNothingUp()
	{
		EndpointBackoff b = new EndpointBackoff();
		long now = 0L;
		for (int i = 0; i < 20 && now < EndpointBackoff.GIVE_UP_MIN_MS; i++)
		{
			Assert.assertFalse("try " + (i + 1) + " at " + now / 1000 + " s",
				b.failed("x", 42L, true, now, BASE, MAX));
			now = b.nextAt("x");
		}
		// Five errors a second apart, as a burst of retries would give, are not half an hour either.
		EndpointBackoff c = new EndpointBackoff();
		for (int i = 0; i < 10; i++)
		{
			Assert.assertFalse(c.failed("y", 7L, true, i * 1_000L, BASE, MAX));
		}
	}

	@Test
	public void outagesAndOtherPayloadsNeverCountTowardGivingUp()
	{
		EndpointBackoff b = new EndpointBackoff();
		for (int i = 0; i < 20; i++)
		{
			Assert.assertFalse("a transport failure or 404 never drops data", b.failed("x", 42L, false, 0L, BASE, MAX));
		}
		for (int i = 1; i < EndpointBackoff.GIVE_UP_AFTER; i++)
		{
			Assert.assertFalse(b.failed("y", 1L, true, 0L, BASE, MAX));
		}
		Assert.assertFalse("a new payload starts its own count", b.failed("y", 2L, true, 0L, BASE, MAX));
	}

	@Test
	public void clearForgetsEverything()
	{
		EndpointBackoff b = new EndpointBackoff();
		b.failed("x", 0L, false, 0L, BASE, MAX);
		b.clear();
		Assert.assertTrue(b.allowed("x", 0L));
	}
}
