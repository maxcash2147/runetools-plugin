package com.runetools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Measures the player's real combat timing from what the client already shows them, for the
 * "My timing" page on the site and the trainers it links into. Two measurements:
 *
 * <ol>
 *   <li><b>Click deadline</b> (effective latency). For every equip click, and every click that turns
 *   a protection prayer on, how long before the next game tick appeared on screen it was made, and
 *   whether the change came back on that tick or one tick later. A click made later than your
 *   latency before a tick misses it, so the boundary between the hits and the misses is your
 *   latency, in exactly the sense the site's trainers use for "ping": the last N ms of every tick on
 *   your screen already belong to the next one.</li>
 *   <li><b>Protection prayers at Jad and the Hunllef</b>, judged by the rules the Prayer Trainer
 *   teaches: Jad's attack is decided 3 ticks after his animation starts, after your input on that
 *   tick; the Hunllef checks the prayer that was up at the start of its attack tick.</li>
 * </ol>
 *
 * <p>Event order is what makes this work without guessing: the client processes everything the
 * server sent for a tick (container changes, animations, new projectiles, the overhead icon) and only
 * then posts {@code GameTick}. So anything seen between two GameTicks belongs to the tick of the
 * second one, and what is read AT a GameTick is the state at the end of that tick.
 *
 * <p>Only aggregates ever leave this class ({@link #report()}): counts in {@value #BIN_MS} ms
 * buckets and prayer results. No single click, its time or what was clicked is kept beyond the few
 * ticks it takes to resolve.
 *
 * <p>Pure (no RuneLite types) so it can be unit tested. The plugin feeds it on the client thread and
 * it is not thread-safe.
 */
final class CombatTiming
{
	/** A protection prayer, or the style of an attack it protects from. */
	enum Style
	{
		MELEE, RANGED, MAGIC
	}

	static final int TICK_MS = 600;
	static final int BIN_MS = 20;
	static final int BINS = TICK_MS / BIN_MS;
	/** Samples needed (with at least {@link #MIN_EACH} hits and misses) before a latency is estimated. */
	static final int MIN_SAMPLES = 30;
	static final int MIN_EACH = 3;
	/** Another Jad attacking within this many ticks makes it a multi-Jad fight (triple Jads are 9 apart). */
	static final int TRIPLE_WINDOW_TICKS = 27;
	/** The fights whose protection prayers are judged, as the site and the Prayer Trainer name them. */
	static final String JAD = "jad";
	static final String TRIPLE = "triple";
	static final String HUNLLEF = "hunllef";
	static final String[] FIGHTS = {JAD, TRIPLE, HUNLLEF};

	// A tick that arrives this far off 600 ms after the previous one (a lag spike, the first tick
	// after a hop or a frozen client) says nothing about where inside the tick a click was made.
	private static final long REGULAR_MIN_MS = 450;
	private static final long REGULAR_MAX_MS = 750;
	private static final int RING = 16;           // ticks of overhead history kept (Jad needs 5)
	private static final int MAX_PENDING = 64;    // clicks awaiting their tick; bounds a runaway

	private static final class Click
	{
		final long at;
		final int itemId;
		final int nextTick;         // the first tick the click could land on
		long phase = -1;            // ms from the click to that tick appearing; -1 = unknown or unusable
		int landedTick = -1;        // the tick its item showed up worn

		Click(long at, int itemId, int nextTick)
		{
			this.at = at;
			this.itemId = itemId;
			this.nextTick = nextTick;
		}
	}

	private static final class PrayerClick
	{
		final long at;
		final Style style;
		final int nextTick;
		long phase = -1;

		PrayerClick(long at, Style style, int nextTick)
		{
			this.at = at;
			this.style = style;
			this.nextTick = nextTick;
		}
	}

	private static final class Attack
	{
		final String fight;        // JAD (may become TRIPLE when graded) or HUNLLEF
		final int npc;             // the attacking NPC's index (Jads), -1 otherwise
		final int attackTick;
		final Style style;
		final int checkTick;       // the tick whose end-state decides it

		Attack(String fight, int npc, int attackTick, Style style, int checkTick)
		{
			this.fight = fight;
			this.npc = npc;
			this.attackTick = attackTick;
			this.style = style;
			this.checkTick = checkTick;
		}
	}

	private final String session;
	private final long startedAt;

	private int tick;
	private long lastTickAt = -1;
	private final Style[] overheadAt = new Style[RING];
	private final int[] overheadTick = new int[RING];

	private final int[] hit = new int[BINS];
	private final int[] miss = new int[BINS];

	private final List<Click> clicks = new ArrayList<>();

	private PrayerClick pendingPrayer;
	private final List<Attack> attacks = new ArrayList<>();
	private final Map<Integer, Integer> jadLastAttack = new HashMap<>();
	private final Map<String, int[]> prayer = new LinkedHashMap<>();   // fight -> {n, blocked, late, wrong, none}

	private int revision;

	CombatTiming(String session, long startedAtEpochMs)
	{
		this.session = session;
		this.startedAt = startedAtEpochMs;
		Arrays.fill(overheadTick, -1);
	}

	String session()
	{
		return session;
	}

	/** Bumped whenever a measurement is recorded; equal revisions mean an identical {@link #report()}. */
	int revision()
	{
		return revision;
	}

	// -- events (times are milliseconds on one monotonic clock) ------------------------------------ //

	/**
	 * A game tick appeared ({@code GameTick}), with the local player's overhead protection prayer as
	 * the server has it at the end of that tick (null for none).
	 */
	void onTick(long now, Style overhead)
	{
		long gap = lastTickAt < 0 ? -1 : now - lastTickAt;
		boolean regular = gap >= REGULAR_MIN_MS && gap <= REGULAR_MAX_MS;
		tick++;
		lastTickAt = now;
		int slot = tick % RING;
		overheadAt[slot] = overhead;
		overheadTick[slot] = tick;

		for (Click c : clicks)
		{
			if (c.nextTick == tick)
			{
				c.phase = regular ? now - c.at : -1;
			}
		}
		resolveClicks();
		resolvePrayerClick(now, overhead, regular);
		gradeAttacks();
	}

	/**
	 * The player clicked to equip {@code itemId} from the inventory. {@code alreadyWorn}: an identical
	 * item is worn already (a stackable top-up), which can't be told apart from its own landing.
	 */
	void onEquipClick(long now, int itemId, boolean alreadyWorn)
	{
		if (itemId <= 0 || alreadyWorn || lastTickAt < 0)
		{
			return;
		}
		for (Click c : clicks)
		{
			// Clicking an item again before it lands sends the old slot contents a second time, which
			// the server ignores: not a second sample.
			if (c.itemId == itemId && c.landedTick < 0)
			{
				return;
			}
		}
		Click c = new Click(now, itemId, tick + 1);
		clicks.add(c);
		if (clicks.size() > MAX_PENDING)
		{
			clicks.remove(0);
		}
	}

	/** The worn-equipment container changed; {@code wornIds} is everything worn now. */
	void onWornChanged(Collection<Integer> wornIds)
	{
		for (Click c : clicks)
		{
			if (c.landedTick < 0 && wornIds.contains(c.itemId))
			{
				c.landedTick = tick + 1;   // seen before this tick's GameTick
			}
		}
	}

	/**
	 * A click on a protection prayer in the prayer book. {@code overheadNow} is the overhead the
	 * server last confirmed, so turning on the prayer that is already up isn't a sample.
	 */
	void onPrayerClick(long now, Style style, boolean activate, Style overheadNow)
	{
		pendingPrayer = null;   // a second protection click in flight: no telling which one landed
		if (!activate || style == null || style == overheadNow || lastTickAt < 0)
		{
			return;
		}
		pendingPrayer = new PrayerClick(now, style, tick + 1);
	}

	/** A quick-prayer orb click: it may change the overhead too, so a pending prayer sample is void. */
	void onQuickPrayerClick()
	{
		pendingPrayer = null;
	}

	/**
	 * A Jad started a magic or ranged attack on the player (its animation, seen before this tick's
	 * GameTick). {@code npc} tells several Jads apart.
	 */
	void onJadAttack(int npc, Style style)
	{
		if (style == null || lastTickAt < 0)
		{
			return;
		}
		int attackTick = tick + 1;
		attacks.add(new Attack(JAD, npc, attackTick, style, attackTick + 3));
		jadLastAttack.put(npc, attackTick);
		jadLastAttack.values().removeIf(t -> attackTick - t > TRIPLE_WINDOW_TICKS + 10);
	}

	/**
	 * The Hunllef launched an attack at the player on the tick that was just processed (a projectile
	 * that first appeared at this tick's GameTick). Call after {@link #onTick}.
	 */
	void onHunllefAttack(Style style)
	{
		if (style == null || lastTickAt < 0)
		{
			return;
		}
		attacks.add(new Attack(HUNLLEF, -1, tick, style, tick - 1));
		gradeAttacks();
	}

	/**
	 * The tick stream broke (a world hop): whatever was waiting on the next tick can't be judged, so
	 * drop it. What has already been measured stays.
	 */
	void interrupt()
	{
		clicks.clear();
		pendingPrayer = null;
		attacks.clear();
		jadLastAttack.clear();
		lastTickAt = -1;
		Arrays.fill(overheadTick, -1);
	}

	// -- resolution ---------------------------------------------------------------------------------- //

	private void resolveClicks()
	{
		for (Iterator<Click> it = clicks.iterator(); it.hasNext(); )
		{
			Click c = it.next();
			if (c.landedTick > 0 && c.landedTick <= tick)
			{
				int late = c.landedTick - c.nextTick;
				// Two or more ticks late means something else held it back (the 10-actions-a-tick
				// limit, a delay after a teleport): not a measure of when it was clicked.
				if (c.phase >= 0 && late <= 1)
				{
					sample(c.phase, late == 0);
				}
				it.remove();
			}
			else if (c.landedTick < 0 && tick >= c.nextTick + 3)
			{
				it.remove();   // never landed (requirements, no space, ...)
			}
		}
	}

	private void resolvePrayerClick(long now, Style overhead, boolean regular)
	{
		PrayerClick p = pendingPrayer;
		if (p == null)
		{
			return;
		}
		if (p.nextTick == tick)
		{
			p.phase = regular ? now - p.at : -1;
		}
		if (overhead == p.style)
		{
			int late = tick - p.nextTick;
			if (p.phase >= 0 && late <= 1)
			{
				sample(p.phase, late == 0);
			}
			pendingPrayer = null;
		}
		else if (tick >= p.nextTick + 2)
		{
			pendingPrayer = null;
		}
	}

	private void sample(long phase, boolean landedNextTick)
	{
		if (phase < 0 || phase >= TICK_MS)
		{
			return;
		}
		int bin = (int) Math.min(BINS - 1, phase / BIN_MS);
		if (landedNextTick)
		{
			hit[bin]++;
		}
		else
		{
			miss[bin]++;
		}
		revision++;
	}

	private void gradeAttacks()
	{
		for (Iterator<Attack> it = attacks.iterator(); it.hasNext(); )
		{
			Attack a = it.next();
			if (tick < a.checkTick + 1)
			{
				continue;   // the tick after the check is needed too, to tell "late" from "missed"
			}
			it.remove();
			if (!hasTick(a.checkTick) || !hasTick(a.checkTick + 1))
			{
				continue;   // a tick went missing (hop, lag): don't guess
			}
			Style at = overhead(a.checkTick);
			Style after = overhead(a.checkTick + 1);
			int[] r = prayer.computeIfAbsent(fightOf(a), k -> new int[5]);
			r[0]++;
			if (at == a.style)
			{
				r[1]++;          // blocked
			}
			else if (after == a.style)
			{
				r[2]++;          // the right prayer came up one tick late
			}
			else if (at != null)
			{
				r[3]++;          // the other protection prayer was up
			}
			else
			{
				r[4]++;          // no protection prayer at all
			}
			revision++;
		}
	}

	/** A Jad attack counts as a multi-Jad fight when another Jad attacked within the window around it. */
	private String fightOf(Attack a)
	{
		if (!JAD.equals(a.fight))
		{
			return a.fight;
		}
		for (Map.Entry<Integer, Integer> e : jadLastAttack.entrySet())
		{
			if (e.getKey() != a.npc && Math.abs(e.getValue() - a.attackTick) <= TRIPLE_WINDOW_TICKS)
			{
				return TRIPLE;
			}
		}
		return JAD;
	}

	private boolean hasTick(int t)
	{
		return t > 0 && overheadTick[t % RING] == t;
	}

	private Style overhead(int t)
	{
		return hasTick(t) ? overheadAt[t % RING] : null;
	}

	// -- read-outs ----------------------------------------------------------------------------------- //

	/**
	 * The effective latency, in ms, from bucketed samples: the threshold that best separates clicks
	 * that made the next tick (made at least that long before it) from those that didn't. Ties take
	 * the middle of the tied range. -1 until there are enough samples on both sides. The server's
	 * {@code timing.estimate_latency} is the same algorithm; keep them in step.
	 */
	static int estimateLatency(int[] hits, int[] misses)
	{
		int n = Math.min(hits.length, misses.length);
		int totalHit = 0;
		int totalMiss = 0;
		for (int i = 0; i < n; i++)
		{
			totalHit += hits[i];
			totalMiss += misses[i];
		}
		if (totalHit + totalMiss < MIN_SAMPLES || totalHit < MIN_EACH || totalMiss < MIN_EACH)
		{
			return -1;
		}
		// errors(b): hits below the threshold plus misses at or above it, threshold = b buckets
		int best = Integer.MAX_VALUE;
		int lo = 0;
		int hi = 0;
		int hitsBelow = 0;
		int missesAbove = totalMiss;
		for (int b = 0; b <= n; b++)
		{
			int errors = hitsBelow + missesAbove;
			if (errors < best)
			{
				best = errors;
				lo = b;
				hi = b;
			}
			else if (errors == best)
			{
				hi = b;
			}
			if (b < n)
			{
				hitsBelow += hits[b];
				missesAbove -= misses[b];
			}
		}
		return Math.round((lo + hi) * BIN_MS / 2f);
	}

	int latencyMs()
	{
		return estimateLatency(hit, miss);
	}

	int samples()
	{
		int s = 0;
		for (int i = 0; i < BINS; i++)
		{
			s += hit[i] + miss[i];
		}
		return s;
	}

	/** {attacks, blocked, late, wrong, none} for a fight, or null if none were seen. */
	int[] prayerResults(String fight)
	{
		int[] r = prayer.get(fight);
		return r != null ? r.clone() : null;
	}

	/**
	 * Everything measured this session, cumulative, as the {@code /api/plugin/timing} body (without
	 * the identity fields, which the plugin stamps). Keep in step with the README's "What it sends".
	 */
	JsonObject report()
	{
		JsonObject o = new JsonObject();
		o.addProperty("v", 1);
		o.addProperty("session", session);
		o.addProperty("started", startedAt);

		JsonObject lat = new JsonObject();
		lat.addProperty("bin_ms", BIN_MS);
		lat.add("hit", ints(hit));
		lat.add("miss", ints(miss));
		o.add("latency", lat);

		JsonObject pr = new JsonObject();
		for (String fight : FIGHTS)
		{
			int[] r = prayer.get(fight);
			if (r == null)
			{
				continue;
			}
			JsonObject f = new JsonObject();
			f.addProperty("n", r[0]);
			f.addProperty("blocked", r[1]);
			f.addProperty("late", r[2]);
			f.addProperty("wrong", r[3]);
			f.addProperty("none", r[4]);
			pr.add(fight, f);
		}
		o.add("prayer", pr);
		return o;
	}

	private static JsonArray ints(int[] a)
	{
		JsonArray arr = new JsonArray();
		for (int v : a)
		{
			arr.add(v);
		}
		return arr;
	}
}
