package com.runetools;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;

/**
 * AdventureLog against the game's real message texts (colour tags removed, as the plugin hands them
 * in). Messages for a tick arrive before that tick's GameTick, so each test feeds a tick's messages
 * and then calls {@code onTick}.
 */
public class AdventureLogTest
{
	private static final long NOW = 1_790_000_000_000L;

	private static final class Toggles implements AdventureLog.Settings
	{
		final Set<AdventureLog.Category> on = EnumSet.allOf(AdventureLog.Category.class);
		long min = 1_000_000L;

		@Override
		public boolean on(AdventureLog.Category category)
		{
			return on.contains(category);
		}

		@Override
		public long minValue()
		{
			return min;
		}
	}

	private final Toggles toggles = new Toggles();
	private final AdventureLog log = new AdventureLog(toggles);
	private int tick;

	/** One tick: its messages, then its GameTick. Returns what finished. */
	private List<JsonObject> tick(String... messages)
	{
		for (String m : messages)
		{
			log.onGameMessage(m, NOW);
		}
		log.onTick(++tick);
		return log.drain();
	}

	private List<JsonObject> ticks(int n)
	{
		List<JsonObject> out = new ArrayList<>();
		for (int i = 0; i < n; i++)
		{
			out.addAll(tick());
		}
		return out;
	}

	private static JsonObject only(List<JsonObject> events, String type)
	{
		JsonObject found = null;
		for (JsonObject e : events)
		{
			if (type.equals(e.get("type").getAsString()))
			{
				Assert.assertNull("more than one " + type, found);
				found = e;
			}
		}
		Assert.assertNotNull("no " + type + " in " + events, found);
		return found;
	}

	@Test
	public void combatTasksKeepTheTierAndNameWithoutThePoints()
	{
		JsonObject e = only(tick("Congratulations, you've completed a hard combat task: Whack-a-Mole (3 points)."), "ca_task");
		Assert.assertEquals("hard", e.get("tier").getAsString());
		Assert.assertEquals("Whack-a-Mole", e.get("task").getAsString());
		Assert.assertEquals(NOW, e.get("ts").getAsLong());
		Assert.assertEquals(16, e.get("id").getAsString().length());

		e = only(tick("Congratulations, you've completed an elite combat task: Perfect Zulrah."), "ca_task");
		Assert.assertEquals("elite", e.get("tier").getAsString());
		Assert.assertEquals("Perfect Zulrah", e.get("task").getAsString());
	}

	@Test
	public void diaryAreaCompletions()
	{
		JsonObject e = only(tick("Congratulations! You have completed all of the hard tasks in the Varrock area. "
			+ "Speak to Toby to claim your reward."), "diary");
		Assert.assertEquals("hard", e.get("tier").getAsString());
		Assert.assertEquals("Varrock", e.get("area").getAsString());

		e = only(tick("Congratulations! You have completed all of the elite tasks in the Kourend & Kebos area. "
			+ "Speak to Elise to claim your reward."), "diary");
		Assert.assertEquals("Kourend & Kebos", e.get("area").getAsString());
		// a single task is not an area completion
		Assert.assertTrue(tick("Well done! You have completed an easy task in the Karamja area. "
			+ "Your Achievement Diary has been updated.").isEmpty());
	}

	@Test
	public void aPetTakesItsNameFromTheCollectionLogEntryThatFollows()
	{
		Assert.assertTrue(tick("You have a funny feeling like you're being followed.",
			"New item added to your collection log: Pet snakeling").isEmpty());
		JsonObject e = only(ticks(3), "pet");
		Assert.assertEquals("[\"Pet snakeling\"]", e.getAsJsonArray("names").toString());
		Assert.assertFalse(e.has("duplicate"));
	}

	@Test
	public void aDuplicatePetWithItsNameFromTheClanBroadcast()
	{
		log.onGameMessage("You have a funny feeling like you would have been followed...", NOW);
		log.onClanMessage("Zezima has a funny feeling like he would have been followed: Vorki at 1,234 kills.", "Zezima");
		log.onClanMessage("Someone Else has a funny feeling like she's being followed: Hellpuppy at 50 kills.", "Zezima");
		log.onTick(++tick);
		JsonObject e = only(ticks(4), "pet");
		Assert.assertTrue(e.get("duplicate").getAsBoolean());
		Assert.assertEquals("[\"Vorki\"]", e.getAsJsonArray("names").toString());
	}

	@Test
	public void aPetIntoTheBackpackWithNoNameIsStillLogged()
	{
		tick("You feel something weird sneaking into your backpack.");
		JsonObject e = only(ticks(12), "pet");
		Assert.assertTrue(e.get("backpack").getAsBoolean());
		Assert.assertEquals(0, e.getAsJsonArray("names").size());
	}

	@Test
	public void valuableDropsOverTheSettingWithQuantityAndValue()
	{
		JsonObject e = only(tick("Valuable drop: Abyssal whip (1,512,345 coins)"), "drop");
		Assert.assertEquals("Abyssal whip", e.get("item").getAsString());
		Assert.assertEquals(1, e.get("qty").getAsLong());
		Assert.assertEquals(1_512_345L, e.get("value").getAsLong());

		e = only(tick("Valuable drop: 250 x Death rune (2,250,000 coins)"), "drop");
		Assert.assertEquals("Death rune", e.get("item").getAsString());
		Assert.assertEquals(250, e.get("qty").getAsLong());

		Assert.assertTrue("under the setting", tick("Valuable drop: Rune platebody (38,400 coins)").isEmpty());
		toggles.min = 10_000L;
		Assert.assertEquals(1, tick("Valuable drop: Rune platebody (38,400 coins)").size());
	}

	@Test
	public void anUntradeableDropIsDroppedWhenTheCollectionLogNamesItToo()
	{
		tick("Untradeable drop: Hydra's eye", "New item added to your collection log: Hydra's eye");
		Assert.assertTrue(ticks(3).isEmpty());

		tick("Untradeable drop: Hydra's eye");               // a second copy: no log entry this time
		JsonObject e = only(ticks(3), "drop");
		Assert.assertTrue(e.get("untradeable").getAsBoolean());
		Assert.assertEquals("Hydra's eye", e.get("item").getAsString());
	}

	@Test
	public void killMilestonesAndFirstKillsButNotEveryKill()
	{
		Assert.assertTrue(tick("Your Zulrah kill count is: 99.").isEmpty() && ticks(2).isEmpty());
		tick("Your Zulrah kill count is: 100.");
		JsonObject e = only(ticks(2), "boss_kc");
		Assert.assertEquals("Zulrah", e.get("boss").getAsString());
		Assert.assertEquals(100, e.get("kc").getAsInt());

		tick("Your completed Chambers of Xeric count is: 1.");
		Assert.assertEquals("Chambers of Xeric", only(ticks(2), "boss_kc").get("boss").getAsString());
		tick("Your subdued Wintertodt count is: 500.");
		Assert.assertEquals("Wintertodt", only(ticks(2), "boss_kc").get("boss").getAsString());
		tick("Your Barrows chest count is: 50.");
		Assert.assertEquals("Barrows", only(ticks(2), "boss_kc").get("boss").getAsString());
		tick("Your Lunar Chest count is: 10.");
		Assert.assertEquals("Lunar Chest", only(ticks(2), "boss_kc").get("boss").getAsString());
		tick("Your Corrupted Gauntlet completion count is: 1.");
		Assert.assertEquals("Corrupted Gauntlet", only(ticks(2), "boss_kc").get("boss").getAsString());
		// agility laps are not fights
		tick("Your Ardougne Agility Course lap count is: 100.");
		Assert.assertTrue(ticks(2).isEmpty());
	}

	@Test
	public void aPersonalBestPairsWithItsKillCount()
	{
		tick("Fight duration: 0:58.20 (new personal best).", "Your Vorkath kill count is: 123.");
		JsonObject e = only(ticks(2), "pb");
		Assert.assertEquals("Vorkath", e.get("boss").getAsString());
		Assert.assertEquals("0:58.20", e.get("time").getAsString());
		Assert.assertEquals(123, e.get("kc").getAsInt());

		// a duration that is not a personal best is not logged
		tick("Fight duration: 1:02.40. Personal best: 0:58.20", "Your Vorkath kill count is: 124.");
		Assert.assertTrue(ticks(2).isEmpty());
	}

	@Test
	public void aRaidPersonalBestKeepsTheTeamSizeAndMayArriveATickLate()
	{
		Assert.assertTrue(tick("Your completed Chambers of Xeric count is: 51.").isEmpty());
		List<JsonObject> out = tick("Congratulations - your raid is complete!\nTeam size: 3 players Duration: 23:45 (new personal best)");
		out.addAll(ticks(2));
		JsonObject e = only(out, "pb");
		Assert.assertEquals("Chambers of Xeric", e.get("boss").getAsString());
		Assert.assertEquals("3 players", e.get("team").getAsString());
		Assert.assertEquals("23:45", e.get("time").getAsString());
	}

	@Test
	public void aLapTimeIsNotABossPersonalBest()
	{
		tick("Lap duration: 1:02 (new personal best).");
		tick("Your Zulrah kill count is: 10.");
		List<JsonObject> out = ticks(2);
		only(out, "boss_kc");
		for (JsonObject e : out)
		{
			Assert.assertNotEquals("pb", e.get("type").getAsString());
		}
	}

	@Test
	public void clueMilestonesAndAValuableReward()
	{
		JsonObject e = only(tick("You have completed 100 hard Treasure Trails."), "clue_tier");
		Assert.assertEquals("hard", e.get("tier").getAsString());
		Assert.assertEquals(100, e.get("count").getAsLong());
		Assert.assertTrue(tick("You have completed 101 hard Treasure Trails.").isEmpty());

		log.onGameMessage("You have completed 102 hard Treasure Trails.", NOW);
		log.onClueReward(Arrays.asList(new int[]{10350, 1}, new int[]{995, 5000}), 4_200_000L, NOW);
		e = only(log.drain(), "clue_reward");
		Assert.assertEquals("hard", e.get("tier").getAsString());
		Assert.assertEquals(4_200_000L, e.get("value").getAsLong());
		Assert.assertEquals(2, e.getAsJsonArray("items").size());

		log.onClueReward(Collections.singletonList(new int[]{995, 5000}), 5_000L, NOW);
		Assert.assertTrue("under the setting", log.drain().isEmpty());
	}

	@Test
	public void levelUpsCarryTheTotalAndTheMaxedFlag()
	{
		log.onLevelUp("Herblore", 71, 72, 1800, false, NOW);
		JsonObject e = only(log.drain(), "level");
		Assert.assertEquals("Herblore", e.get("skill").getAsString());
		Assert.assertEquals(71, e.get("from").getAsInt());
		Assert.assertEquals(72, e.get("level").getAsInt());
		Assert.assertEquals(1800, e.get("total").getAsInt());
		Assert.assertFalse(e.has("maxed"));

		log.onLevelUp("Sailing", 98, 99, 2376, true, NOW);
		Assert.assertTrue(only(log.drain(), "level").get("maxed").getAsBoolean());
		log.onLevelUp("Attack", 99, 100, 2376, false, NOW);   // virtual levels aren't levels
		Assert.assertTrue(log.drain().isEmpty());
	}

	@Test
	public void eachCategoryHasItsOwnSwitch()
	{
		toggles.on.clear();
		Assert.assertTrue(tick("Congratulations, you've completed a hard combat task: Whack-a-Mole (3 points).",
			"Valuable drop: Abyssal whip (1,512,345 coins)",
			"Congratulations! You have completed all of the hard tasks in the Varrock area.",
			"You have completed 100 hard Treasure Trails.",
			"Your Zulrah kill count is: 100.").isEmpty());
		Assert.assertTrue(ticks(12).isEmpty());
		log.onLevelUp("Herblore", 71, 72, 1800, false, NOW);
		log.onQuest("Dragon Slayer II", 150, NOW);
		Assert.assertTrue(log.drain().isEmpty());

		toggles.on.add(AdventureLog.Category.QUESTS);
		log.onQuest("Dragon Slayer II", 150, NOW);
		JsonObject e = only(log.drain(), "quest");
		Assert.assertEquals("Dragon Slayer II", e.get("quest").getAsString());
		Assert.assertEquals(150, e.get("count").getAsInt());
	}

	@Test
	public void questTitlesMatchTheGamesQuestList()
	{
		List<String> quests = Arrays.asList("The Corsair Curse", "One Small Favour", "Dragon Slayer I",
			"Dragon Slayer II", "Cook's Assistant", "Recipe for Disaster", "Another Slice of H.A.M.");
		Assert.assertEquals("The Corsair Curse", AdventureLog.matchQuest("You have completed The Corsair Curse!", quests));
		Assert.assertEquals("One Small Favour", AdventureLog.matchQuest("'One Small Favour' completed!", quests));
		Assert.assertEquals("Dragon Slayer II", AdventureLog.matchQuest("You have completed Dragon Slayer II!", quests));
		Assert.assertEquals("Cook's Assistant", AdventureLog.matchQuest("You have completed the Cook's Assistant Quest!", quests));
		Assert.assertEquals("Another Slice of H.A.M.", AdventureLog.matchQuest("You have completed Another Slice of H.A.M.!", quests));
		Assert.assertNull(AdventureLog.matchQuest("You have kind of completed Recipe for Disaster!", quests));
		Assert.assertNull(AdventureLog.matchQuest("Something else entirely", quests));
	}

	@Test
	public void aWorldHopForgetsHalfSeenFacts()
	{
		tick("You have a funny feeling like you're being followed.", "Your Zulrah kill count is: 100.");
		log.reset();
		Assert.assertTrue(ticks(12).isEmpty());
	}
}
