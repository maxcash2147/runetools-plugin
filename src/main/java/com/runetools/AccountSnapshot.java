package com.runetools;

import com.google.gson.JsonArray;

/**
 * The latest known live state of the logged-in character. All fields are written on the
 * client thread (inside {@code @Subscribe} handlers) and read on the sync scheduler thread,
 * so they are {@code volatile}; the JSON arrays are rebuilt-then-published, never mutated
 * after assignment. {@code dirty} means there is something new to upload.
 */
class AccountSnapshot
{
	volatile String rsn;
	volatile String accountHash;
	volatile int world;
	volatile boolean members;
	volatile long gp;
	volatile int freeSlots;
	volatile long snapshotTsMillis;
	volatile JsonArray offers = new JsonArray();
	volatile JsonArray inventory = new JsonArray();
	volatile JsonArray bank = new JsonArray();
	volatile boolean dirty;

	boolean hasIdentity()
	{
		return rsn != null && !rsn.isEmpty();
	}
}
