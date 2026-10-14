package com.runetools;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;

/**
 * CombatTiming, driven by a small simulated server: ticks appear every 600 ms, and a click made at
 * time t lands on the first tick at or after t + latency, which is how the game (and the site's
 * trainers) process input. Events reach the tracker in the client's order: a tick's container
 * changes and animations first, then its GameTick.
 */
public class CombatTimingTest
{
	private static final int TICK = CombatTiming.TICK_MS;

	/** A simulated client + server around one tracker. */
	private static final class Sim
	{
		final CombatTiming t = new CombatTiming("test-session", 1_700_000_000_000L);
		final int latency;
		final Set<Integer> worn = new HashSet<>();
		final List<long[]> inFlight = new ArrayList<>();   // {itemId, landing tick}
		int k;                                              // last tick shown
		CombatTiming.Style overhead;

		Sim(int latency)
		{
			this.latency = latency;
		}

		long tickTime(int tick)
		{
			return 10_000L + (long) tick * TICK;
		}

		/** Equip an item at {@code ms} after the last tick appeared. */
		void equip(int itemId, long ms)
		{
			long at = tickTime(k) + ms;
			t.onEquipClick(at, itemId, worn.contains(itemId));
			int land = (int) Math.ceil((at + latency - 10_000L) / (double) TICK);
			inFlight.add(new long[]{itemId, land});
		}

		/** Show the next tick: its equipment changes first, then the GameTick. */
		void tick()
		{
			k++;
			boolean changed = false;
			for (java.util.Iterator<long[]> it = inFlight.iterator(); it.hasNext(); )
			{
				long[] f = it.next();
				if (f[1] == k)
				{
					worn.add((int) f[0]);
					changed = true;
					it.remove();
				}
			}
			if (changed)
			{
				t.onWornChanged(worn);
			}
			t.onTick(tickTime(k), overhead);
		}

		void ticks(int n)
		{
			for (int i = 0; i < n; i++)
			{
				tick();
			}
		}
	}

	@Test
	public void latencyIsTheBoundaryBetweenClicksThatMadeTheTickAndClicksThatDidNot()
	{
		Sim s = new Sim(85);
		s.tick();
		// One equip per tick at every phase across the tick, a few times over.
		int item = 1000;
		for (int round = 0; round < 3; round++)
		{
			for (int ms = 5; ms < TICK; ms += 15)
			{
				s.equip(item++, ms);
				s.ticks(3);
			}
		}
		Assert.assertTrue(s.t.samples() >= 100);
		int est = s.t.latencyMs();
		Assert.assertTrue("estimate " + est, est >= 80 && est <= 100);
	}

	@Test
	public void noEstimateUntilThereAreEnoughSamplesOnBothSides()
	{
		int[] hit = new int[CombatTiming.BINS];
		int[] miss = new int[CombatTiming.BINS];
		hit[10] = 40;
		Assert.assertEquals(-1, CombatTiming.estimateLatency(hit, miss));     // no misses at all
		miss[2] = 2;
		Assert.assertEquals(-1, CombatTiming.estimateLatency(hit, miss));     // too few misses
		miss[2] = 3;
		hit[10] = 20;
		Assert.assertEquals(-1, CombatTiming.estimateLatency(hit, miss));     // 23 < 30 samples
		hit[10] = 40;
		// Misses fill bucket 2 (40-60 ms), hits bucket 10: every threshold from 60 to 200 ms splits
		// them perfectly, and the middle of that range is reported.
		Assert.assertEquals(130, CombatTiming.estimateLatency(hit, miss));
	}

	@Test
	public void theEstimateIgnoresAFewOutliers()
	{
		int[] hit = new int[CombatTiming.BINS];
		int[] miss = new int[CombatTiming.BINS];
		for (int b = 0; b < CombatTiming.BINS; b++)
		{
			if (b < 6)
			{
				miss[b] = 5;     // under 120 ms: missed
			}
			else
			{
				hit[b] = 5;
			}
		}
		miss[20] = 1;            // a lag spike far from the boundary
		hit[1] = 1;
		Assert.assertEquals(120, CombatTiming.estimateLatency(hit, miss));
	}

	@Test
	public void everyEquipThatLandsIsALatencySample()
	{
		Sim s = new Sim(60);
		s.tick();
		s.equip(41, 50);
		s.ticks(2);
		s.equip(42, 50);
		s.ticks(4);
		Assert.assertEquals(2, s.t.samples());
	}

	@Test
	public void anEquipThatNeverHappensIsNotASample()
	{
		Sim s = new Sim(60);
		s.tick();
		s.equip(31, 100);
		long at = s.tickTime(s.k) + 150;
		s.t.onEquipClick(at, 32, false);   // e.g. a level requirement: it never shows up worn
		s.ticks(6);
		Assert.assertEquals(1, s.t.samples());
	}

	@Test
	public void aTickThatArrivesLateGivesNoSample()
	{
		Sim s = new Sim(60);
		s.tick();
		s.equip(51, 100);
		// The next tick shows up 900 ms after the last (a lag spike).
		s.k++;
		s.worn.add(51);
		s.t.onWornChanged(s.worn);
		s.t.onTick(s.tickTime(s.k) + 300, null);
		s.ticks(3);
		Assert.assertEquals(0, s.t.samples());
	}

	@Test
	public void jadIsJudgedOnTheThirdTickAfterHisAnimation()
	{
		Sim s = new Sim(60);
		s.overhead = CombatTiming.Style.RANGED;
		s.tick();

		// Magic attack; Protect from Magic is up by the end of the 3rd tick after the animation.
		s.t.onJadAttack(7, CombatTiming.Style.MAGIC);     // seen before tick a's GameTick
		s.tick();                                          // a
		s.tick();                                          // a+1
		s.tick();                                          // a+2
		s.overhead = CombatTiming.Style.MAGIC;
		s.tick();                                          // a+3: decided here
		s.tick();
		int[] r = s.t.prayerResults(CombatTiming.JAD);
		Assert.assertArrayEquals(new int[]{1, 1, 0, 0, 0}, r);

		// Ranged attack; the switch back lands one tick too late.
		s.t.onJadAttack(7, CombatTiming.Style.RANGED);
		s.ticks(4);                                        // a .. a+3, still Magic
		s.overhead = CombatTiming.Style.RANGED;
		s.tick();                                          // a+4
		r = s.t.prayerResults(CombatTiming.JAD);
		Assert.assertArrayEquals(new int[]{2, 1, 1, 0, 0}, r);

		// Magic attack with Missiles up throughout, then one with no prayer at all.
		s.t.onJadAttack(7, CombatTiming.Style.MAGIC);
		s.ticks(5);
		s.overhead = null;
		s.t.onJadAttack(7, CombatTiming.Style.MAGIC);
		s.ticks(5);
		r = s.t.prayerResults(CombatTiming.JAD);
		Assert.assertArrayEquals(new int[]{4, 1, 1, 1, 1}, r);
		Assert.assertNull(s.t.prayerResults(CombatTiming.TRIPLE));
	}

	@Test
	public void severalJadsMakeItATripleJadFight()
	{
		Sim s = new Sim(60);
		s.overhead = CombatTiming.Style.MAGIC;
		s.tick();
		s.t.onJadAttack(1, CombatTiming.Style.MAGIC);
		s.ticks(3);
		s.t.onJadAttack(2, CombatTiming.Style.MAGIC);    // the next Jad, 3 ticks later
		s.ticks(6);
		Assert.assertNull(s.t.prayerResults(CombatTiming.JAD));
		Assert.assertArrayEquals(new int[]{2, 2, 0, 0, 0}, s.t.prayerResults(CombatTiming.TRIPLE));
	}

	@Test
	public void theHunllefChecksThePrayerUpAtTheStartOfItsAttackTick()
	{
		Sim s = new Sim(60);
		s.overhead = CombatTiming.Style.RANGED;
		s.ticks(2);
		s.tick();
		s.t.onHunllefAttack(CombatTiming.Style.RANGED);    // projectile seen at this tick
		Assert.assertArrayEquals(new int[]{1, 1, 0, 0, 0}, s.t.prayerResults(CombatTiming.HUNLLEF));

		// It switched to magic; the player's switch was processed on the attack tick itself.
		s.ticks(4);
		s.overhead = CombatTiming.Style.MAGIC;
		s.tick();
		s.t.onHunllefAttack(CombatTiming.Style.MAGIC);
		Assert.assertArrayEquals(new int[]{2, 1, 1, 0, 0}, s.t.prayerResults(CombatTiming.HUNLLEF));
	}

	@Test
	public void aProtectionPrayerClickIsALatencySampleToo()
	{
		Sim s = new Sim(100);
		s.tick();
		// Clicked 450 ms after the tick (150 ms before the next): it lands on the next tick.
		s.t.onPrayerClick(s.tickTime(s.k) + 450, CombatTiming.Style.MAGIC, true, null);
		s.overhead = CombatTiming.Style.MAGIC;
		s.tick();
		Assert.assertEquals(1, s.t.samples());

		// Clicked 560 ms after the tick (40 ms before the next, under the latency): one tick late.
		s.tick();
		s.t.onPrayerClick(s.tickTime(s.k) + 560, CombatTiming.Style.RANGED, true, CombatTiming.Style.MAGIC);
		s.tick();
		s.overhead = CombatTiming.Style.RANGED;
		s.tick();
		Assert.assertEquals(2, s.t.samples());
		JsonObject lat = s.t.report().getAsJsonObject("latency");
		Assert.assertEquals(1, lat.getAsJsonArray("hit").get(7).getAsInt());    // 150 ms -> bucket 7
		Assert.assertEquals(1, lat.getAsJsonArray("miss").get(2).getAsInt());   // 40 ms -> bucket 2
	}

	@Test
	public void twoProtectionClicksInFlightAreNotASample()
	{
		Sim s = new Sim(100);
		s.tick();
		s.t.onPrayerClick(s.tickTime(s.k) + 200, CombatTiming.Style.MAGIC, true, null);
		s.t.onPrayerClick(s.tickTime(s.k) + 260, CombatTiming.Style.MAGIC, false, null);   // a flick
		s.overhead = null;
		s.ticks(3);
		Assert.assertEquals(0, s.t.samples());
	}

	@Test
	public void aWorldHopDropsWhatWasInFlight()
	{
		Sim s = new Sim(60);
		s.tick();
		s.equip(61, 100);
		s.t.onJadAttack(3, CombatTiming.Style.MAGIC);
		s.t.interrupt();
		s.ticks(6);
		Assert.assertEquals(0, s.t.samples());
		Assert.assertNull(s.t.prayerResults(CombatTiming.JAD));
	}

	@Test
	public void theReportCarriesOnlyAggregates()
	{
		Sim s = new Sim(60);
		s.tick();
		s.equip(71, 100);
		s.ticks(3);
		JsonObject r = s.t.report();
		Assert.assertEquals(new HashSet<>(java.util.Arrays.asList("v", "session", "started", "latency", "prayer")),
			r.keySet());
		JsonObject lat = r.getAsJsonObject("latency");
		Assert.assertEquals(CombatTiming.BIN_MS, lat.get("bin_ms").getAsInt());
		Assert.assertEquals(CombatTiming.BINS, lat.getAsJsonArray("hit").size());
		Assert.assertEquals(CombatTiming.BINS, lat.getAsJsonArray("miss").size());
		Assert.assertEquals("test-session", r.get("session").getAsString());
		int before = s.t.revision();
		s.ticks(2);
		Assert.assertEquals("nothing new measured, nothing new to send", before, s.t.revision());
	}
}
