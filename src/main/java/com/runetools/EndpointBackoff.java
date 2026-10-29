package com.runetools;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Backoff kept per endpoint, so one failing request (a 5xx on the Combat Achievements catalogue, a
 * 404 on an endpoint the server doesn't have) waits on its own instead of being retried every sweep
 * or every tick, while the endpoints that work carry on at the normal pace.
 *
 * <p>Each failure doubles that endpoint's wait, from {@code baseMs} up to {@code maxMs}; a success
 * clears it. The same payload failing with a server error {@link #GIVE_UP_AFTER} times in a row, over
 * at least {@link #GIVE_UP_MIN_MS}, is given up on: the caller stops sending exactly that payload, so
 * one body the server chokes on can't keep a queue stuck forever, while a server that is down for a
 * while (a deploy, a database restart) costs nothing. Plain logic with the clock passed in, so it can
 * be unit-tested; thread-safe.
 */
final class EndpointBackoff
{
	/** Server errors in a row on the same payload before the caller gives up on it. */
	static final int GIVE_UP_AFTER = 5;
	/** ...and how long they must have gone on for, from the first. */
	static final long GIVE_UP_MIN_MS = 30L * 60L * 1000L;

	private static final class State
	{
		int failures;
		long nextAt;
		long payload;
		int payloadErrors;
		long payloadSince;
	}

	private final Map<String, State> states = new ConcurrentHashMap<>();

	/** Whether {@code endpoint} may be tried at {@code now}. */
	boolean allowed(String endpoint, long now)
	{
		State s = states.get(endpoint);
		return s == null || now >= s.nextAt;
	}

	/** When {@code endpoint} may next be tried (0 when it may now). */
	long nextAt(String endpoint)
	{
		State s = states.get(endpoint);
		return s != null ? s.nextAt : 0L;
	}

	/**
	 * Record a failure and push the endpoint's next try back. {@code serverError} marks a failure that
	 * counts toward giving up on {@code payload} (see {@link SyncResponse#serverError}); a transport
	 * failure, an outage, a 404 or a rate limit never does. Returns true when the caller should give up
	 * on this exact payload.
	 */
	boolean failed(String endpoint, long payload, boolean serverError, long now, long baseMs, long maxMs)
	{
		State s = states.computeIfAbsent(endpoint, k -> new State());
		synchronized (s)
		{
			s.failures++;
			long wait = Math.min(maxMs, baseMs << Math.min(s.failures - 1, 10));
			s.nextAt = Math.max(s.nextAt, now + Math.max(baseMs, wait));
			if (!serverError)
			{
				return false;
			}
			if (s.payloadErrors > 0 && s.payload == payload)
			{
				s.payloadErrors++;
			}
			else
			{
				s.payload = payload;
				s.payloadErrors = 1;
				s.payloadSince = now;
			}
			if (s.payloadErrors >= GIVE_UP_AFTER && now - s.payloadSince >= GIVE_UP_MIN_MS)
			{
				s.payloadErrors = 0;
				return true;
			}
			return false;
		}
	}

	/** The endpoint answered properly: back to the normal pace. */
	void succeeded(String endpoint)
	{
		states.remove(endpoint);
	}

	/** Forget every endpoint's backoff (a new login, a new key). */
	void clear()
	{
		states.clear();
	}
}
