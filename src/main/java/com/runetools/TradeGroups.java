package com.runetools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Splits the unsynced-trade queue into uploads, one per character, each sent with that character's
 * own key. A fill always goes to the RuneTools account of the character that made it, never to
 * whoever is logged in when the queue is flushed.
 *
 * <ul>
 *   <li>Trades are grouped by the account hash captured with them.</li>
 *   <li>A group whose character has no key here (an alt that was never connected, or a fill captured
 *       before the account hash was known) stays queued, and is dropped once it is older than
 *       {@link #KEYLESS_TTL_MS}.</li>
 *   <li>A missing name is filled only from the same character: another of its queued fills, or the
 *       character in game when it is that same account hash.</li>
 * </ul>
 *
 * <p>Plain logic, so it can be unit-tested.
 */
final class TradeGroups
{
	/** How long a fill with no key to send it with is kept, waiting for its character to connect. */
	static final long KEYLESS_TTL_MS = 30L * 24L * 60L * 60L * 1000L;

	/** One upload: a character's fills and the key that sends them. */
	static final class Group
	{
		final String accountHash;
		final String rsn;
		final String key;
		final List<Trade> trades;

		Group(String accountHash, String rsn, String key, List<Trade> trades)
		{
			this.accountHash = accountHash;
			this.rsn = rsn;
			this.key = key;
			this.trades = trades;
		}
	}

	/** What to send now, and the keyless fills too old to keep. */
	static final class Plan
	{
		final List<Group> send;
		final List<Trade> expired;

		Plan(List<Group> send, List<Trade> expired)
		{
			this.send = send;
			this.expired = expired;
		}
	}

	private TradeGroups()
	{
	}

	static Plan plan(List<Trade> pending, Function<String, String> keyForHash, String currentHash,
		String currentRsn, long nowMillis)
	{
		Map<String, List<Trade>> byHash = new LinkedHashMap<>();
		for (Trade t : pending)
		{
			String h = t.getAccountHash() != null ? t.getAccountHash() : "";
			byHash.computeIfAbsent(h, k -> new ArrayList<>()).add(t);
		}
		List<Group> send = new ArrayList<>();
		List<Trade> expired = new ArrayList<>();
		for (Map.Entry<String, List<Trade>> en : byHash.entrySet())
		{
			String hash = en.getKey().isEmpty() ? null : en.getKey();
			List<Trade> trades = en.getValue();
			String key = hash != null ? keyForHash.apply(hash) : null;
			if (key == null || key.isEmpty())
			{
				for (Trade t : trades)
				{
					if (nowMillis - t.getTsMillis() > KEYLESS_TTL_MS)
					{
						expired.add(t);
					}
				}
				continue;
			}
			String rsn = null;
			for (Trade t : trades)
			{
				if (t.getRsn() != null && !t.getRsn().isEmpty())
				{
					rsn = t.getRsn();   // the newest name this character traded under
				}
			}
			if (rsn == null && hash.equals(currentHash) && currentRsn != null && !currentRsn.isEmpty())
			{
				rsn = currentRsn;
			}
			send.add(new Group(hash, rsn, key, Collections.unmodifiableList(trades)));
		}
		return new Plan(send, expired);
	}
}
