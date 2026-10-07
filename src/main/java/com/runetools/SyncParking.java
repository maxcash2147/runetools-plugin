package com.runetools;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers, per sync endpoint, a refusal that sending again can't change, so the background loop
 * stops re-posting the same thing every sweep.
 *
 * <ul>
 *   <li>A <b>name</b> park (409, or 403 {@code rsn_taken}) holds everything on that endpoint for one
 *       identity: the server is refusing the character's name, not the data, so no payload helps.</li>
 *   <li>A <b>payload</b> park (400 or 413) holds one exact payload for one identity. A changed payload
 *       is tried again.</li>
 * </ul>
 *
 * <p>An identity is the character's name, its account hash and the API key it authenticates with, so a
 * park lapses on its own the moment another character logs in, the name changes, or a new key is
 * issued. A success on the endpoint clears it too. Nothing here is persisted: a client restart also
 * gets one fresh try. Thread-safe, since the scheduler, client and OkHttp threads all consult it.
 */
final class SyncParking
{
	private static final class Park
	{
		final String identity;
		final boolean wholeEndpoint;   // a name park: every payload is held
		final long payload;

		Park(String identity, boolean wholeEndpoint, long payload)
		{
			this.identity = identity;
			this.wholeEndpoint = wholeEndpoint;
			this.payload = payload;
		}
	}

	private final Map<String, Park> parks = new ConcurrentHashMap<>();

	/** True when {@code endpoint} must not send {@code payload} as {@code identity}. */
	boolean isParked(String endpoint, String identity, long payload)
	{
		Park p = parks.get(endpoint);
		if (p == null)
		{
			return false;
		}
		if (!p.identity.equals(identity))
		{
			parks.remove(endpoint, p);   // another character or key: the old refusal no longer applies
			return false;
		}
		return p.wholeEndpoint || p.payload == payload;
	}

	/** True when {@code endpoint} is refusing this identity's name, whatever the payload. */
	boolean isNameParked(String endpoint, String identity)
	{
		Park p = parks.get(endpoint);
		return p != null && p.wholeEndpoint && p.identity.equals(identity);
	}

	/** True when any endpoint is refusing this identity's name (drives the panel's status line). */
	boolean anyNameParked(String identity)
	{
		for (Park p : parks.values())
		{
			if (p.wholeEndpoint && p.identity.equals(identity))
			{
				return true;
			}
		}
		return false;
	}

	/** The server won't publish under this identity's name on {@code endpoint}. */
	void parkName(String endpoint, String identity)
	{
		parks.put(endpoint, new Park(identity, true, 0L));
	}

	/** The server can never accept this exact payload from this identity on {@code endpoint}. */
	void parkPayload(String endpoint, String identity, long payload)
	{
		// Never narrow a name park to one payload: a late answer to an older request must not reopen
		// an endpoint the server has already said no to for this character.
		parks.compute(endpoint, (k, p) -> p != null && p.wholeEndpoint && p.identity.equals(identity)
			? p : new Park(identity, false, payload));
	}

	/** The endpoint accepted something from this identity, so whatever it refused before is moot. */
	void release(String endpoint, String identity)
	{
		parks.computeIfPresent(endpoint, (k, p) -> p.identity.equals(identity) ? null : p);
	}
}
