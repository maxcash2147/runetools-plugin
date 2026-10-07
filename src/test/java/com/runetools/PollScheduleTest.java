package com.runetools;

import org.junit.Assert;
import org.junit.Test;

public class PollScheduleTest
{
	private static final long MIN = PollSchedule.MIN_INTERVAL_MS;
	private static final long MAX = PollSchedule.MAX_INTERVAL_MS;
	private static final long DAY = 24L * 60L * 60L * 1000L;
	private static final long TICK = 20_000L;   // the plugin's @Schedule period

	@Test
	public void pollsStraightAwayAtStart()
	{
		PollSchedule p = new PollSchedule();
		Assert.assertTrue(p.tryStart(1_000L));
		Assert.assertFalse(p.tryStart(1_000L));   // and not twice in a row
	}

	@Test
	public void backsOffByDoublingUpToTheCeilingWhileNothingChanges()
	{
		PollSchedule p = new PollSchedule();
		long now = 0;
		Assert.assertTrue(p.tryStart(now));
		long expectedGap = MIN;
		for (int i = 0; i < 10; i++)
		{
			Assert.assertEquals(now + expectedGap, p.nextAt());
			Assert.assertFalse(p.tryStart(p.nextAt() - 1));
			now = p.nextAt();
			Assert.assertTrue(p.tryStart(now));
			expectedGap = Math.min(MAX, expectedGap * 2);
		}
		Assert.assertEquals(MAX, p.nextAt() - now);
	}

	@Test
	public void aChangeOnTheSiteGoesBackToTheShortGap()
	{
		PollSchedule p = new PollSchedule();
		long now = 0;
		for (int i = 0; i < 6; i++)
		{
			now = p.nextAt();
			Assert.assertTrue(p.tryStart(now));
		}
		Assert.assertEquals(MAX, p.nextAt() - now);
		p.onChanged();
		Assert.assertEquals(now + MIN, p.nextAt());
		Assert.assertTrue(p.tryStart(now + MIN));
		Assert.assertEquals(now + MIN + MIN, p.nextAt());   // and the backoff restarts from there
	}

	@Test
	public void openingThePanelPollsAtOnceButNotFasterThanItsGap()
	{
		PollSchedule p = new PollSchedule();
		for (int i = 0; i < 6; i++)
		{
			Assert.assertTrue(p.tryStart(p.nextAt()));
		}
		long last = p.nextAt() - MAX;   // when the last poll started

		long later = last + 2 * 60_000L;
		p.pollSoon(later, PollSchedule.PANEL_GAP_MS);
		Assert.assertTrue(p.tryStart(later));

		// Reopening the panel straight after waits out the gap rather than polling again.
		p.pollSoon(later + 1_000L, PollSchedule.PANEL_GAP_MS);
		Assert.assertFalse(p.tryStart(later + 1_000L));
		Assert.assertTrue(p.tryStart(later + PollSchedule.PANEL_GAP_MS));

		// Opening the panel doesn't shorten the idle backoff: with nothing changing on the site, the
		// next background poll is still the long gap away.
		Assert.assertEquals(later + PollSchedule.PANEL_GAP_MS + MAX, p.nextAt());
	}

	@Test
	public void bankingOftenPollsAtMostOncePerBankGap()
	{
		PollSchedule p = new PollSchedule();
		int polls = 0;
		long hour = 60L * 60L * 1000L;
		// A player opening the bank every 15 seconds for an hour, with the 20s loop also running.
		for (long t = 0; t < hour; t += 5_000L)
		{
			boolean bankOpened = t % 15_000L == 0;
			if (bankOpened)
			{
				p.pollSoon(t, PollSchedule.BANK_GAP_MS);
			}
			if ((bankOpened || t % TICK == 0) && p.tryStart(t))
			{
				polls++;
			}
		}
		Assert.assertTrue("polled " + polls + " times", polls <= hour / PollSchedule.BANK_GAP_MS + 1);
	}

	@Test
	public void anIdleDayIsAFewHundredPollsNotThousands()
	{
		PollSchedule p = new PollSchedule();
		int polls = 0;
		for (long t = 0; t < DAY; t += TICK)
		{
			if (p.tryStart(t))
			{
				polls++;
			}
		}
		// Two requests per poll. The old fixed 20s loop made 4,320 polls (8,640 requests) a day.
		Assert.assertTrue("polled " + polls + " times", polls <= DAY / MAX + 10);
		Assert.assertTrue(polls >= DAY / MAX - 10);
	}

	@Test
	public void resetPollsAgainAtOnce()
	{
		PollSchedule p = new PollSchedule();
		for (int i = 0; i < 6; i++)
		{
			Assert.assertTrue(p.tryStart(p.nextAt()));
		}
		long last = p.nextAt() - MAX;
		p.reset();
		Assert.assertTrue(p.tryStart(last + 1));
		Assert.assertEquals(last + 1 + MIN, p.nextAt());
	}

	@Test
	public void onlyARealNewEtagCountsAsAChange()
	{
		Assert.assertTrue(SetupManager.etagChanged("\"1.0\"", "\"2.0\""));
		Assert.assertFalse(SetupManager.etagChanged("\"1.0\"", "\"1.0\""));
		Assert.assertFalse(SetupManager.etagChanged(null, "\"2.0\""));   // first pull, or after our own write
		Assert.assertFalse(SetupManager.etagChanged("", "\"2.0\""));
		Assert.assertFalse(SetupManager.etagChanged("\"1.0\"", null));   // server sent none: can't tell
	}
}
