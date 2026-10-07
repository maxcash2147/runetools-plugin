package com.runetools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * An immutable snapshot of the logged-in character's live state. The client thread builds a new
 * snapshot from the current one via {@link #toBuilder()} and publishes it with a single atomic
 * reference swap, so the sync thread always reads one internally-consistent set of fields - never a
 * torn mix such as a fresh inventory alongside a stale gp (gp is inventory coins + bank coins).
 */
final class AccountSnapshot
{
	final String rsn;
	final String accountHash;
	final int world;
	final boolean members;
	final long gp;
	final int freeSlots;
	final JsonArray offers;
	final JsonArray inventory;
	// null until the bank is captured (Include-bank on + bank opened this session), so a sync never
	// uploads an empty bank that would overwrite a previously-synced one.
	final JsonArray bank;
	final JsonObject skills;   // null until captured; {skill: {level, xp}}
	final JsonArray quests;    // null until captured; [{id, name, state}]
	final JsonObject slayer;   // null until captured; {task?, task_id, amount, master_id, points, streak, ...}

	private AccountSnapshot(Builder b)
	{
		rsn = b.rsn;
		accountHash = b.accountHash;
		world = b.world;
		members = b.members;
		gp = b.gp;
		freeSlots = b.freeSlots;
		offers = b.offers;
		inventory = b.inventory;
		bank = b.bank;
		skills = b.skills;
		quests = b.quests;
		slayer = b.slayer;
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
		private int world;
		private boolean members;
		private long gp;
		private int freeSlots;
		private JsonArray offers = new JsonArray();
		private JsonArray inventory = new JsonArray();
		private JsonArray bank;
		private JsonObject skills;
		private JsonArray quests;
		private JsonObject slayer;

		private Builder()
		{
		}

		private Builder(AccountSnapshot s)
		{
			rsn = s.rsn;
			accountHash = s.accountHash;
			world = s.world;
			members = s.members;
			gp = s.gp;
			freeSlots = s.freeSlots;
			offers = s.offers;
			inventory = s.inventory;
			bank = s.bank;
			skills = s.skills;
			quests = s.quests;
			slayer = s.slayer;
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

		Builder world(int v)
		{
			world = v;
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

		Builder quests(JsonArray v)
		{
			quests = v;
			return this;
		}

		Builder slayer(JsonObject v)
		{
			slayer = v;
			return this;
		}

		AccountSnapshot build()
		{
			return new AccountSnapshot(this);
		}
	}
}
