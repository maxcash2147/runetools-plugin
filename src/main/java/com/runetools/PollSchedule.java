package com.runetools;

/**
 * Decides when the background loop next asks the server for changes made on the website: gear
 * setups and imported bank layouts. Those are ETag polls, so "nothing changed" is a cheap 304, but
 * at a fixed 20 seconds they still came to two requests every 20 seconds for as long as a character
 * stayed logged in, about 8,600 a day for a client left idle.
 *
 * <p>Instead the gap between polls starts at {@link #MIN_INTERVAL_MS} and doubles after every poll,
 * up to {@link #MAX_INTERVAL_MS}. A poll that finds something new on the site puts it back to the
 * short gap, since more edits usually follow, and so does a new login ({@link #reset}). Anything
 * that suggests the player is about to use the synced data (opening the side panel, opening the
 * bank) asks for a poll right away through {@link #pollSoon}, but never sooner than the gap given
 * there after the previous poll, so repeatedly opening the panel or the bank can't turn into a
 * request storm.
 *
 * <p>Plain logic with an injected clock value, so it can be unit-tested; thread-safe, since the
 * scheduler thread, the client thread, the Swing thread and OkHttp callbacks all reach it.
 */
final class PollSchedule
{
	/** Gap after a poll that found changes, or after the player showed up. */
	static final long MIN_INTERVAL_MS = 30_000L;
	/** Ceiling for an idle client: one poll every five minutes. */
	static final long MAX_INTERVAL_MS = 5L * 60L * 1000L;
	/** The closest two polls may be, even when the player asks for one (e.g. opens the side panel). */
	static final long PANEL_GAP_MS = 10_000L;
	/** The closest a bank opening can pull a poll in; players bank far more often than they need a poll. */
	static final long BANK_GAP_MS = 60_000L;

	private long nextAt;                  // first moment the next poll may start; 0 = due now
	private long lastStartedAt = -1;      // -1 = never polled since the last reset
	private long interval = MIN_INTERVAL_MS;

	/**
	 * If a poll is due at {@code now}, record that it is starting and return true; the caller then
	 * sends it. Until an answer says otherwise, the poll is assumed to find nothing new, so the gap
	 * to the following one has already doubled.
	 */
	synchronized boolean tryStart(long now)
	{
		if (now < nextAt)
		{
			return false;
		}
		lastStartedAt = now;
		nextAt = now + interval;
		interval = Math.min(MAX_INTERVAL_MS, interval * 2);
		return true;
	}

	/** A poll found something changed on the site: keep polling at the short gap for a while. */
	synchronized void onChanged()
	{
		interval = MIN_INTERVAL_MS;
		if (lastStartedAt >= 0)
		{
			nextAt = Math.min(nextAt, lastStartedAt + MIN_INTERVAL_MS);
		}
	}

	/**
	 * The player did something that makes fresh data worth having. Make a poll due as soon as
	 * {@code minGap} has passed since the last one started. The backoff itself carries on where it
	 * was: only a real change on the site shortens it, so a player who opens the bank every few
	 * seconds gets one poll per {@code minGap}, not a return to the short gap.
	 */
	synchronized void pollSoon(long now, long minGap)
	{
		long earliest = lastStartedAt >= 0 ? Math.max(now, lastStartedAt + minGap) : now;
		nextAt = Math.min(nextAt, earliest);
	}

	/** Back to the starting state (a new login): the next check polls straight away. */
	synchronized void reset()
	{
		nextAt = 0;
		lastStartedAt = -1;
		interval = MIN_INTERVAL_MS;
	}

	/** When the next poll may start (for tests and debug logging). */
	synchronized long nextAt()
	{
		return nextAt;
	}
}
