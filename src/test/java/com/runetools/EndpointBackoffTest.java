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
		for (int i = 1; i < EndpointBackoff.GIVE_UP_AFTER; i++)
		{
			Assert.assertFalse(b.failed("x", 42L, true, 0L, BASE, MAX));
		}
		Assert.assertTrue(b.failed("x", 42L, true, 0L, BASE, MAX));
		// ...and counting starts over afterwards.
		Assert.assertFalse(b.failed("x", 42L, true, 0L, BASE, MAX));
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
