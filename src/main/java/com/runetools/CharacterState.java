package com.runetools;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Everything captured from the character in game, kept together so it can be dropped together the
 * moment it stops belonging to whoever is playing.
 *
 * <ul>
 *   <li>The account snapshot (bank, inventory, worn gear, skills, offers, name and account hash) and
 *       the coin counters belong to one character's session: a logout drops them, and so does any
 *       capture that finds a different account hash. Without that, the previous character's bank or
 *       skills were uploaded as the next one's.</li>
 *   <li>The Grand Exchange baselines (and this session's P/L) belong to one character: a different
 *       account hash gets fresh ones, but a logout or world hop keeps them, so fills that happened
 *       while logged out are still counted once at the next login.</li>
 * </ul>
 *
 * <p>{@link #adopt} and the trackers are used on the client thread; the snapshot is published through
 * an atomic reference for the sync thread.
 */
final class CharacterState
{
	final AtomicReference<AccountSnapshot> snapshot = new AtomicReference<>(AccountSnapshot.empty());

	private String owner;          // account hash the snapshot belongs to, null when none
	private String trackerOwner;   // account hash the GE trackers belong to, null when none
	private OfferTracker offers = new OfferTracker();
	private SessionTracker session = new SessionTracker();

	// Client thread only.
	int inventoryCoins;
	int bankCoins;

	// The bank the server last accepted in a /state body (its hash), so an unchanged bank is left out.
	private volatile int acceptedBankHash;
	private volatile boolean bankAccepted;

	/**
	 * What was captured now belongs to {@code hash} (the account hash in game). A different hash than
	 * the one the captured state came from drops that state and starts fresh GE trackers. Returns
	 * true when anything was dropped. A null hash (not known yet) changes nothing.
	 */
	synchronized boolean adopt(String hash)
	{
		if (hash == null || hash.isEmpty())
		{
			return false;
		}
		boolean dropped = false;
		if (trackerOwner != null && !trackerOwner.equals(hash))
		{
			offers = new OfferTracker();
			session = new SessionTracker();
			dropped = true;
		}
		trackerOwner = hash;
		if (owner != null && !owner.equals(hash))
		{
			clearCaptured();
			dropped = true;
		}
		owner = hash;
		return dropped;
	}

	/** Back at the login screen: the session's capture goes, the GE baselines stay with their character. */
	synchronized void logout()
	{
		clearCaptured();
		owner = null;
	}

	/** The plugin was switched off: forget everything. */
	synchronized void reset()
	{
		clearCaptured();
		owner = null;
		trackerOwner = null;
		offers = new OfferTracker();
		session = new SessionTracker();
	}

	/** The account hash the captured state belongs to, or null. */
	synchronized String owner()
	{
		return owner;
	}

	synchronized OfferTracker offers()
	{
		return offers;
	}

	synchronized SessionTracker session()
	{
		return session;
	}

	/** Whether {@code bankHash} is the bank the server already holds for this character. */
	boolean bankAlreadySent(int bankHash)
	{
		return bankAccepted && acceptedBankHash == bankHash;
	}

	/** The server accepted a /state body carrying this bank. */
	void bankSent(int bankHash)
	{
		acceptedBankHash = bankHash;
		bankAccepted = true;
	}

	private void clearCaptured()
	{
		snapshot.set(AccountSnapshot.empty());
		inventoryCoins = 0;
		bankCoins = 0;
		bankAccepted = false;
		acceptedBankHash = 0;
	}
}
