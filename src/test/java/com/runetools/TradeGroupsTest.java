package com.runetools;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

/** Each character's fills go with its own key, never the key of whoever is logged in. */
public class TradeGroupsTest
{
	private static final long NOW = 100L * 24L * 60L * 60L * 1000L;

	private static Trade trade(String rsn, String hash, long ts)
	{
		return new Trade(4151, "buy", 1, 1_500_000L, ts, "0:4151:buy:1", rsn, hash);
	}

	private static Map<String, String> keys()
	{
		Map<String, String> k = new HashMap<>();
		k.put("111", "rtk_main");
		k.put("222", "rtk_alt");
		return k;
	}

	@Test
	public void eachCharacterIsSentWithItsOwnKey()
	{
		List<Trade> pending = Arrays.asList(trade("Main", "111", NOW), trade("Alt", "222", NOW), trade("Main", "111", NOW));
		TradeGroups.Plan plan = TradeGroups.plan(pending, keys()::get, "222", "Alt", NOW);
		Assert.assertEquals(2, plan.send.size());
		TradeGroups.Group main = plan.send.get(0);
		Assert.assertEquals("111", main.accountHash);
		Assert.assertEquals("rtk_main", main.key);
		Assert.assertEquals("Main", main.rsn);
		Assert.assertEquals(2, main.trades.size());
		TradeGroups.Group alt = plan.send.get(1);
		Assert.assertEquals("rtk_alt", alt.key);
		Assert.assertEquals(1, alt.trades.size());
		Assert.assertTrue(plan.expired.isEmpty());
	}

	@Test
	public void aCharacterWithNoKeyStaysQueued()
	{
		List<Trade> pending = Arrays.asList(trade("Ironman", "333", NOW - 1000L), trade(null, null, NOW));
		TradeGroups.Plan plan = TradeGroups.plan(pending, keys()::get, "111", "Main", NOW);
		Assert.assertTrue("never sent with the logged-in character's key", plan.send.isEmpty());
		Assert.assertTrue(plan.expired.isEmpty());
	}

	@Test
	public void keylessFillsExpireAfterThirtyDays()
	{
		Trade old = trade("Ironman", "333", NOW - TradeGroups.KEYLESS_TTL_MS - 1L);
		Trade recent = trade("Ironman", "333", NOW - 1000L);
		Trade orphan = trade(null, null, NOW - TradeGroups.KEYLESS_TTL_MS - 1L);
		TradeGroups.Plan plan = TradeGroups.plan(Arrays.asList(old, recent, orphan), keys()::get, "111", "Main", NOW);
		Assert.assertEquals(Arrays.asList(old, orphan), plan.expired);
	}

	@Test
	public void aMissingNameComesOnlyFromTheSameCharacter()
	{
		// Another of its own fills knows the name.
		TradeGroups.Plan own = TradeGroups.plan(Arrays.asList(trade(null, "111", NOW), trade("Main", "111", NOW)),
			keys()::get, "222", "Alt", NOW);
		Assert.assertEquals("Main", own.send.get(0).rsn);
		// The character in game is the same account: its name is used.
		TradeGroups.Plan same = TradeGroups.plan(Arrays.asList(trade(null, "111", NOW)), keys()::get, "111", "Main", NOW);
		Assert.assertEquals("Main", same.send.get(0).rsn);
		// A different character in game: never borrowed.
		TradeGroups.Plan other = TradeGroups.plan(Arrays.asList(trade(null, "111", NOW)), keys()::get, "222", "Alt", NOW);
		Assert.assertNull(other.send.get(0).rsn);
		Assert.assertEquals("rtk_main", other.send.get(0).key);
	}
}
