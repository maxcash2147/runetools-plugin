package com.runetools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * An immutable snapshot of the logged-in character's live state. The client thread builds a new
 * snapshot from the current one via {@link #toBuilder()} and publishes it with a single atomic
 * reference swap, so the sync thread always reads one internally-consistent set of fields - never a
 * torn mix such as a fresh inventory alongside a stale gp (gp is inventory coins + bank coins).
 *
 * <p>It holds only what the {@code /state} sync sends, and the server stores every field of it.
 */
final class AccountSnapshot
{
	final String rsn;
	final String accountHash;
	final boolean members;     // logged into a members world
	final long gp;
	final int freeSlots;
	final JsonArray offers;
	final JsonArray inventory;
	// null until the bank is captured (Include-bank on + bank opened this session), so a sync never
	// uploads an empty bank that would overwrite a previously-synced one.
	final JsonArray bank;
	// null until captured; {skill: {level, xp}}. Feeds the site's level-aware calculators, and the
	// exact XP is how the server proves this account plays the in-game name it syncs under.
	final JsonObject skills;

	private AccountSnapshot(Builder b)
	{
		rsn = b.rsn;
		accountHash = b.accountHash;
		members = b.members;
		gp = b.gp;
		freeSlots = b.freeSlots;
		offers = b.offers;
		inventory = b.inventory;
		bank = b.bank;
		skills = b.skills;
	}

	static AccountSnapshot empty()
	{
		return new Builder().build();
	}

	boolean hasIdentity()
	{
		return rsn != null && !rsn.isEmpty();
	}

	Builder toBuilder()
	{
		return new Builder(this);
	}

	static final class Builder
	{
		private String rsn;
		private String accountHash;
		private boolean members;
		private long gp;
		private int freeSlots;
		private JsonArray offers = new JsonArray();
		private JsonArray inventory = new JsonArray();
		private JsonArray bank;
		private JsonObject skills;

		private Builder()
		{
		}

		private Builder(AccountSnapshot s)
		{
			rsn = s.rsn;
			accountHash = s.accountHash;
			members = s.members;
			gp = s.gp;
			freeSlots = s.freeSlots;
			offers = s.offers;
			inventory = s.inventory;
			bank = s.bank;
			skills = s.skills;
		}

		Builder rsn(String v)
		{
			rsn = v;
			return this;
		}

		Builder accountHash(String v)
		{
			accountHash = v;
			return this;
		}

		Builder members(boolean v)
		{
			members = v;
			return this;
		}

		Builder gp(long v)
		{
			gp = v;
			return this;
		}

		Builder freeSlots(int v)
		{
			freeSlots = v;
			return this;
		}

		Builder offers(JsonArray v)
		{
			offers = v;
			return this;
		}

		Builder inventory(JsonArray v)
		{
			inventory = v;
			return this;
		}

		Builder bank(JsonArray v)
		{
			bank = v;
			return this;
		}

		Builder skills(JsonObject v)
		{
			skills = v;
			return this;
		}

		AccountSnapshot build()
		{
			return new AccountSnapshot(this);
		}
	}
}
