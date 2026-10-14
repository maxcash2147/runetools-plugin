package com.runetools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spots achievements as they happen, for the player's Adventurer's Log on RuneTools: level-ups,
 * quests, diaries, combat tasks, pets, valuable drops, boss kill-count milestones and personal
 * bests, and clue milestones and rewards.
 *
 * <p>Almost everything comes from the messages the game itself prints, matched on the exact texts
 * RuneLite's own Screenshot and Chat Commands plugins match (the plugin removes colour tags first).
 * Levels, quests and clue rewards are read by the plugin and handed in already parsed. Some facts
 * arrive in pieces across a tick or two (a kill count and its personal best, a pet and its name), so
 * those wait a few ticks before they are complete; {@link #onTick} finishes them.
 *
 * <p>Pure (no RuneLite types) so it can be unit tested. The plugin feeds it on the client thread and
 * takes finished events with {@link #drain()}; it is not thread-safe. Each event is the JSON the
 * {@code /api/plugin/achievements} endpoint takes: a {@code type}, a capture time {@code ts} (epoch
 * ms), a random {@code id} so a resend is recognised, and the type's own fields. Keep the README's
 * "What it sends" table in step with what is built here.
 */
final class AdventureLog
{
	/** The categories, each switched on or off by its own setting. */
	enum Category
	{
		LEVELS, QUESTS, COMBAT, PETS, DROPS, CLUES
	}

	/** One stack of loot from a chest or raid, priced by the plugin: {@code value} is the whole stack's. */
	static final class LootStack
	{
		final String name;
		final int qty;
		final long value;

		LootStack(String name, int qty, long value)
		{
			this.name = name;
			this.qty = qty;
			this.value = value;
		}
	}

	/** What the player has switched on, read fresh for every event. */
	interface Settings
	{
		boolean on(Category category);

		/** The smallest drop or clue reward value (gp) worth logging. */
		long minValue();
	}

	/** Kill counts worth an entry: the first kill, then the milestones (a superset of the hiscores' own). */
	static final Set<Integer> KILL_MILESTONES = new HashSet<>(Arrays.asList(
		1, 10, 50, 100, 250, 500, 1000, 2000, 3000, 5000, 10000, 25000, 50000, 100000));
	/** Clue completions (per tier) worth an entry. */
	static final Set<Integer> CLUE_MILESTONES = new HashSet<>(Arrays.asList(
		1, 10, 25, 50, 100, 250, 500, 1000, 2000, 5000, 10000));
	static final List<String> CLUE_TIERS = Arrays.asList("beginner", "easy", "medium", "hard", "elite", "master");
	static final List<String> CA_TIERS = Arrays.asList("easy", "medium", "hard", "elite", "master", "grandmaster");
	static final List<String> DIARY_TIERS = Arrays.asList("easy", "medium", "hard", "elite");

	// How long the pieces of one fact are allowed to arrive apart.
	private static final int PET_WAIT_TICKS = 10;          // the pet's name follows its message
	private static final int TIME_WAIT_TICKS = 10;         // a fight duration waits for its kill count
	private static final int UNTRADEABLE_WAIT_TICKS = 2;   // and an untradeable drop for a log entry
	private static final int CLUE_TIER_TICKS = 3;          // the clue message comes just before its reward
	private static final int MAX_READY = 200;              // a runaway can't grow the queue without bound
	private static final int MAX_PET_NAMES = 5;
	private static final int MAX_REWARD_ITEMS = 8;

	// "Congratulations, you've completed a hard combat task: Whack-a-Mole (3 points)."
	private static final Pattern CA_TASK = Pattern.compile(
		"^Congratulations, you've completed an? (\\w+) combat task: (.+?)(?: \\(\\d+ points?\\))?\\.?$",
		Pattern.CASE_INSENSITIVE);
	// "Congratulations! You have completed all of the hard tasks in the Varrock area. Speak to ..."
	private static final Pattern DIARY = Pattern.compile(
		"Congratulations! You have completed all of the (\\w+) tasks in the (.+?) area", Pattern.CASE_INSENSITIVE);
	// The pet messages: a new pet following you ("You have a funny feeling like you're being
	// followed."), one sneaking into your backpack (follower already out), and a duplicate you would
	// have got ("...like you would have been followed..."). Matched on their common start.
	private static final Pattern PET = Pattern.compile(
		"^You (?:have a funny feeling like you|feel something weird sneaking into your backpack)",
		Pattern.CASE_INSENSITIVE);
	// The clan broadcast, which may carry the clan's name first and ends with when the pet came:
	// "[Clan] <player> has a funny feeling like he would have been followed: Pet snakeling at 50 killcount.",
	// "<player> feels something weird sneaking into her backpack: Heron at 12,345,678 XP.",
	// "<player> feels like she's acquired something special: Tumeken's guardian".
	private static final Pattern CLAN_PET = Pattern.compile(
		"^(?:\\[[^\\]]*\\] )?(.+?) (?:has a funny feeling like .+? followed|feels something weird sneaking into .+? backpack"
			+ "|feels like .+? acquired something special): (.+?)(?: at (.+?))?\\.?$");
	private static final Pattern CLOG_ITEM = Pattern.compile("New item added to your collection log: (.+?)\\.?$",
		Pattern.CASE_INSENSITIVE);
	private static final Pattern UNTRADEABLE = Pattern.compile("^Untradeable drop: (.+?)\\.?$");
	// "Valuable drop: 3 x Dragon bones (8,151 coins)"
	private static final Pattern VALUABLE = Pattern.compile(
		"^Valuable drop: (?:([\\d,]+) x )?(.+?) \\(([\\d,]+) coins\\)");
	// Kill counts. "Your Zulrah kill count is: 100.", "Your Barrows chest count is: 50.",
	// "Your Corrupted Gauntlet completion count is: 10.", "Your herbiboar harvest count is: 25."
	private static final Pattern KC_PRIMARY = Pattern.compile(
		"Your (.+?) (kill|chest|completion|success|harvest) count is: ?([\\d,]+)", Pattern.CASE_INSENSITIVE);
	// "Your completed Chambers of Xeric count is: 50.", "Your subdued Wintertodt count is: 500."
	private static final Pattern KC_SECONDARY = Pattern.compile(
		"Your (?:completed|subdued) (.+?) count is: ?([\\d,]+)", Pattern.CASE_INSENSITIVE);
	// "Your completion count for Tombs of Amascut: Expert Mode is: 5."
	private static final Pattern KC_TERTIARY = Pattern.compile(
		"Your completion count for (.+?) is: ?([\\d,]+)", Pattern.CASE_INSENSITIVE);
	// "Fight duration: 1:23.40 (new personal best).", "Subdued in 3:12 (new personal best).",
	// "... Theatre of Blood completion time: 20:00 (new personal best)". Lap times are agility.
	private static final Pattern PB = Pattern.compile(
		"(?:(?:Fight |Challenge |Corrupted challenge )?(?<!Lap )duration:|Subdued in|(?<!total )completion time:) "
			+ "([0-9:]+(?:\\.[0-9]+)?)\\.? \\(new personal best\\)", Pattern.CASE_INSENSITIVE);
	// "Congratulations - your raid is complete! Team size: 3 players Duration: 23:45 (new personal best)"
	private static final Pattern RAID_PB = Pattern.compile(
		"Team size: (Solo|\\d+(?:\\+|-\\d+)? players?) Duration: ([0-9:]+(?:\\.[0-9]+)?)\\.? \\(new personal best\\)",
		Pattern.CASE_INSENSITIVE);
	// "You have completed 100 hard Treasure Trails."
	private static final Pattern CLUE = Pattern.compile(
		"You have completed ([\\d,]+) (\\w+) Treasure Trails?\\.", Pattern.CASE_INSENSITIVE);

	private final Settings settings;
	private final List<JsonObject> ready = new ArrayList<>();

	// pieces still waiting for the rest of their fact
	private PendingPet pet;
	private PendingKill kill;
	private PendingTime time;
	private final List<PendingDrop> untradeables = new ArrayList<>();
	private final Set<String> loggedThisTick = new HashSet<>();   // collection-log names, lower case
	private int loggedTick = -1;
	private String clueTier;
	private int clueTierTick = -1;
	private int tick;

	private static final class PendingPet
	{
		final int tick;
		final boolean duplicate;
		final boolean backpack;
		final long at;
		final Set<String> names = new LinkedHashSet<>();
		String milestone;   // "50 killcount", from the clan broadcast when there is one

		PendingPet(int tick, boolean duplicate, boolean backpack, long at)
		{
			this.tick = tick;
			this.duplicate = duplicate;
			this.backpack = backpack;
			this.at = at;
		}
	}

	private static final class PendingKill
	{
		final int tick;
		final String boss;
		final int count;
		final long at;

		PendingKill(int tick, String boss, int count, long at)
		{
			this.tick = tick;
			this.boss = boss;
			this.count = count;
			this.at = at;
		}
	}

	private static final class PendingTime
	{
		final int tick;
		final String time;
		final String team;

		PendingTime(int tick, String time, String team)
		{
			this.tick = tick;
			this.time = time;
			this.team = team;
		}
	}

	private static final class PendingDrop
	{
		final int tick;
		final String name;
		final long at;

		PendingDrop(int tick, String name, long at)
		{
			this.tick = tick;
			this.name = name;
			this.at = at;
		}
	}

	AdventureLog(Settings settings)
	{
		this.settings = settings;
	}

	/** Take every finished event, oldest first. */
	List<JsonObject> drain()
	{
		List<JsonObject> out = new ArrayList<>(ready);
		ready.clear();
		return out;
	}

	boolean hasReady()
	{
		return !ready.isEmpty();
	}

	/** Forget anything half-seen (a logout, a world hop, a character switch). Finished events stay. */
	void reset()
	{
		pet = null;
		kill = null;
		time = null;
		untradeables.clear();
		loggedThisTick.clear();
		clueTier = null;
	}

	// -- inputs ------------------------------------------------------------------------------------ //

	/**
	 * A message from the game itself (a game message, a message box or a friends-chat notification),
	 * colour tags already removed. {@code now} is epoch ms.
	 */
	void onGameMessage(String text, long now)
	{
		if (text == null || text.isEmpty())
		{
			return;
		}
		Matcher m;
		if ((m = CA_TASK.matcher(text)).find())
		{
			String tier = m.group(1).toLowerCase(Locale.ROOT);
			if (CA_TIERS.contains(tier) && on(Category.COMBAT))
			{
				JsonObject e = event("ca_task", now);
				e.addProperty("tier", tier);
				e.addProperty("task", clean(m.group(2)));
				add(e);
			}
			return;
		}
		if ((m = DIARY.matcher(text)).find())
		{
			String tier = m.group(1).toLowerCase(Locale.ROOT);
			if (DIARY_TIERS.contains(tier) && on(Category.QUESTS))
			{
				JsonObject e = event("diary", now);
				e.addProperty("tier", tier);
				e.addProperty("area", clean(m.group(2)));
				add(e);
			}
			return;
		}
		if ((m = PET.matcher(text)).find())
		{
			if (on(Category.PETS))
			{
				flushPet();   // two pets in a row: the first keeps whatever name it found
				String lower = text.toLowerCase(Locale.ROOT);
				pet = new PendingPet(tick, lower.contains("would have been"), lower.contains("backpack"), now);
			}
			return;
		}
		if ((m = CLOG_ITEM.matcher(text)).find())
		{
			String name = clean(m.group(1));
			if (loggedTick != tick)
			{
				loggedThisTick.clear();
				loggedTick = tick;
			}
			loggedThisTick.add(name.toLowerCase(Locale.ROOT));
			if (pet != null)
			{
				addPetName(name);
			}
			// The log entry is the better record of this item, so its untradeable drop isn't kept.
			untradeables.removeIf(d -> d.name.equalsIgnoreCase(name));
			return;
		}
		if ((m = UNTRADEABLE.matcher(text)).find())
		{
			String name = clean(m.group(1));
			if (pet != null)
			{
				addPetName(name);
			}
			if (on(Category.DROPS) && !name.isEmpty() && !(loggedTick == tick && loggedThisTick.contains(name.toLowerCase(Locale.ROOT))))
			{
				untradeables.add(new PendingDrop(tick, name, now));
			}
			return;
		}
		if ((m = VALUABLE.matcher(text)).find())
		{
			long value = number(m.group(3));
			if (on(Category.DROPS) && value >= settings.minValue() && value > 0)
			{
				JsonObject e = event("drop", now);
				e.addProperty("item", clean(m.group(2)));
				e.addProperty("qty", m.group(1) != null ? Math.max(1, number(m.group(1))) : 1);
				e.addProperty("value", value);
				add(e);
			}
			return;
		}
		if ((m = CLUE.matcher(text)).find())
		{
			String tier = m.group(2).toLowerCase(Locale.ROOT);
			if (!CLUE_TIERS.contains(tier))
			{
				return;
			}
			clueTier = tier;
			clueTierTick = tick;
			long count = number(m.group(1));
			if (on(Category.CLUES) && count <= Integer.MAX_VALUE && CLUE_MILESTONES.contains((int) count))
			{
				JsonObject e = event("clue_tier", now);
				e.addProperty("tier", tier);
				e.addProperty("count", count);
				add(e);
			}
			return;
		}
		// A Theatre of Blood room message gives the room's own time before the raid's ("Wave 'The
		// Final Challenge' (Normal Mode) complete! Duration: 7:42.60 Theatre of Blood completion time:
		// 21:33.60 (new personal best)"): only the raid's part can be the raid's best.
		String timed = text;
		if (text.startsWith("Wave '"))
		{
			int raid = text.indexOf("Theatre of Blood");
			timed = raid < 0 ? "" : text.substring(raid);
		}
		// A raid's completion message carries the team size, so it is checked before the general time.
		if ((m = RAID_PB.matcher(timed)).find())
		{
			time = new PendingTime(tick, m.group(2), m.group(1));
			return;
		}
		if ((m = PB.matcher(timed)).find())
		{
			time = new PendingTime(tick, m.group(1), null);
		}
		// Not "else": a Theatre of Blood wave message can carry a duration and, in some versions, the
		// count in one line.
		String boss = null;
		long count = -1;
		if ((m = KC_SECONDARY.matcher(text)).find() || (m = KC_TERTIARY.matcher(text)).find())
		{
			boss = clean(m.group(1));
			count = number(m.group(2));
		}
		else if ((m = KC_PRIMARY.matcher(text)).find())
		{
			boss = clean(m.group(1));
			String type = m.group(2).toLowerCase(Locale.ROOT);
			if ("chest".equals(type) && !boss.equalsIgnoreCase("barrows") && !boss.equalsIgnoreCase("lunar"))
			{
				boss = null;   // other "chest counts" are not fights
			}
			else if ("chest".equals(type) && boss.equalsIgnoreCase("lunar"))
			{
				boss = "Lunar Chest";
			}
			count = number(m.group(3));
		}
		if (boss != null && !boss.isEmpty() && count > 0 && count <= Integer.MAX_VALUE)
		{
			if (kill != null)
			{
				finishKill(true);   // kills two ticks apart: the first one is complete already
			}
			kill = new PendingKill(tick, boss, (int) count, now);
		}
	}

	/** A clan or group-chat broadcast: a pet's name, when it's this player's. */
	void onClanMessage(String text, String me)
	{
		if (pet == null || text == null || me == null)
		{
			return;
		}
		Matcher m = CLAN_PET.matcher(text);
		if (m.find() && m.group(1).trim().equalsIgnoreCase(me.trim()))
		{
			addPetName(clean(m.group(2)));
			if (m.group(3) != null && pet.milestone == null)
			{
				pet.milestone = clean(m.group(3));
			}
		}
	}

	/**
	 * Real (unboosted) levels went up in a skill. {@code total} is the total level once this change
	 * is counted; {@code maxed} that every skill is now 99. Virtual levels past 99 aren't passed in.
	 */
	void onLevelUp(String skill, int from, int to, int total, boolean maxed, long now)
	{
		if (!on(Category.LEVELS) || skill == null || to <= from || to > 99 || from < 1)
		{
			return;
		}
		JsonObject e = event("level", now);
		e.addProperty("skill", skill);
		e.addProperty("from", from);
		e.addProperty("level", to);
		e.addProperty("total", total);
		if (maxed)
		{
			e.addProperty("maxed", true);
		}
		add(e);
	}

	/** A quest's completion scroll appeared, already matched to the quest's name. */
	void onQuest(String quest, int completedCount, long now)
	{
		if (!on(Category.QUESTS) || quest == null || quest.isEmpty())
		{
			return;
		}
		JsonObject e = event("quest", now);
		e.addProperty("quest", quest);
		if (completedCount > 0)
		{
			e.addProperty("count", completedCount);
		}
		add(e);
	}

	/**
	 * A clue reward screen: {@code items} as {id, quantity} pairs, most valuable first, and the
	 * total value the plugin priced them at. Logged when it reaches the value setting.
	 */
	void onClueReward(List<int[]> items, long value, long now)
	{
		if (!on(Category.DROPS) || items == null || items.isEmpty() || value < settings.minValue() || value <= 0)
		{
			return;
		}
		JsonObject e = event("clue_reward", now);
		if (clueTier != null && tick - clueTierTick <= CLUE_TIER_TICKS)
		{
			e.addProperty("tier", clueTier);
		}
		e.addProperty("value", value);
		JsonArray arr = new JsonArray();
		for (int[] it : items)
		{
			if (arr.size() >= MAX_REWARD_ITEMS)
			{
				break;
			}
			if (it != null && it.length >= 2 && it[0] > 0 && it[1] > 0)
			{
				JsonObject o = new JsonObject();
				o.addProperty("id", it[0]);
				o.addProperty("qty", it[1]);
				arr.add(o);
			}
		}
		e.add("items", arr);
		add(e);
	}

	/**
	 * Loot from a chest or a raid (Barrows, the raids' reward chests, the Moons...), which the game
	 * doesn't announce the way it does an NPC's valuable drop. Each stack worth at least the value
	 * setting is logged as a drop, the most valuable first, up to {@link #MAX_REWARD_ITEMS}.
	 */
	void onChestLoot(List<LootStack> stacks, long now)
	{
		if (!on(Category.DROPS) || stacks == null)
		{
			return;
		}
		List<LootStack> worth = new ArrayList<>();
		for (LootStack s : stacks)
		{
			if (s != null && s.name != null && !s.name.trim().isEmpty() && s.qty > 0 && s.value > 0
				&& s.value >= settings.minValue())
			{
				worth.add(s);
			}
		}
		worth.sort((a, b) -> Long.compare(b.value, a.value));
		for (int i = 0; i < worth.size() && i < MAX_REWARD_ITEMS; i++)
		{
			LootStack s = worth.get(i);
			JsonObject e = event("drop", now);
			e.addProperty("item", clean(s.name));
			e.addProperty("qty", s.qty);
			e.addProperty("value", s.value);
			add(e);
		}
	}

	/**
	 * A game tick passed (after this tick's messages). Finishes the facts whose pieces have all had
	 * time to arrive.
	 */
	void onTick(int tick)
	{
		this.tick = tick;
		// A kill count waits one more tick for its fight duration (raids send them a tick apart).
		if (kill != null && tick - kill.tick >= 2)
		{
			finishKill(false);
		}
		if (time != null && tick - time.tick > TIME_WAIT_TICKS)
		{
			time = null;   // a duration whose fight never reported a count (agility, a minigame)
		}
		if (pet != null && (tick - pet.tick > PET_WAIT_TICKS || (!pet.names.isEmpty() && tick - pet.tick > 2)))
		{
			flushPet();
		}
		for (Iterator<PendingDrop> it = untradeables.iterator(); it.hasNext(); )
		{
			PendingDrop d = it.next();
			if (tick - d.tick >= UNTRADEABLE_WAIT_TICKS)
			{
				it.remove();
				JsonObject e = event("drop", d.at);
				e.addProperty("item", d.name);
				e.addProperty("qty", 1);
				e.addProperty("untradeable", true);
				add(e);
			}
		}
	}

	// -- finishing ------------------------------------------------------------------------------- //

	/**
	 * Finish the pending kill. {@code newKillArrived}: another kill count just came in, so a duration
	 * seen after this kill belongs to that one and is left for it.
	 */
	private void finishKill(boolean newKillArrived)
	{
		PendingKill k = kill;
		kill = null;
		if (!on(Category.COMBAT))
		{
			time = null;
			return;
		}
		PendingTime t = null;
		if (time != null && !(newKillArrived && time.tick > k.tick) && Math.abs(k.tick - time.tick) <= TIME_WAIT_TICKS)
		{
			t = time;
			time = null;
		}
		if (t != null)
		{
			JsonObject e = event("pb", k.at);
			e.addProperty("boss", k.boss);
			e.addProperty("time", t.time);
			e.addProperty("kc", k.count);
			if (t.team != null)
			{
				e.addProperty("team", t.team);
			}
			add(e);
		}
		if (KILL_MILESTONES.contains(k.count))
		{
			JsonObject e = event("boss_kc", k.at);
			e.addProperty("boss", k.boss);
			e.addProperty("kc", k.count);
			add(e);
		}
	}

	private void flushPet()
	{
		PendingPet p = pet;
		pet = null;
		if (p == null || !on(Category.PETS))
		{
			return;
		}
		JsonObject e = event("pet", p.at);
		if (p.milestone != null && !p.milestone.isEmpty())
		{
			e.addProperty("milestone", p.milestone);
		}
		if (p.duplicate)
		{
			e.addProperty("duplicate", true);
		}
		if (p.backpack)
		{
			e.addProperty("backpack", true);
		}
		// Every item the game named around the pet message; the site keeps the one that is a pet.
		JsonArray names = new JsonArray();
		for (String n : p.names)
		{
			names.add(n);
		}
		e.add("names", names);
		add(e);
	}

	private void addPetName(String name)
	{
		if (pet != null && !name.isEmpty() && pet.names.size() < MAX_PET_NAMES)
		{
			pet.names.add(name);
		}
	}

	// -- helpers ----------------------------------------------------------------------------------- //

	private boolean on(Category c)
	{
		return settings.on(c);
	}

	private JsonObject event(String type, long now)
	{
		JsonObject e = new JsonObject();
		e.addProperty("type", type);
		e.addProperty("ts", now);
		e.addProperty("id", UUID.randomUUID().toString().replace("-", "").substring(0, 16));
		return e;
	}

	private void add(JsonObject e)
	{
		if (ready.size() < MAX_READY)
		{
			ready.add(e);
		}
	}

	/** Trim and drop a trailing full stop; names are bounded so a garbled line can't grow a payload. */
	static String clean(String s)
	{
		if (s == null)
		{
			return "";
		}
		String t = s.replace(' ', ' ').trim();
		while (t.endsWith("."))
		{
			t = t.substring(0, t.length() - 1).trim();
		}
		return t.length() > 80 ? t.substring(0, 80) : t;
	}

	static long number(String s)
	{
		if (s == null)
		{
			return -1;
		}
		try
		{
			return Long.parseLong(s.replace(",", "").trim());
		}
		catch (NumberFormatException ex)
		{
			return -1;
		}
	}

	/**
	 * Recipe for Disaster's subquests end with scrolls that name who was helped rather than the
	 * subquest ("You have freed the Goblin Generals!", "You have assisted the Lumbridge Cook...
	 * again!"): what each title contains (squashed), and the subquest it is in RuneLite's list. The
	 * finale is "You have completed Recipe for Disaster!", matched like any other quest.
	 */
	private static final String[][] RFD_SUBQUESTS = {
		{"anothercooksquest", "Another Cook's Quest"},
		{"lumbridgecookagain", "Another Cook's Quest"},
		{"mountaindwarf", "Mountain Dwarf"},
		{"goblingenerals", "Wartface & Bentnoze"},
		{"wartface", "Wartface & Bentnoze"},
		{"piratepete", "Pirate Pete"},
		{"lumbridgeguide", "Lumbridge Guide"},
		{"evildave", "Evil Dave"},
		{"skrach", "Skrach Uglogwee"},
		{"siramikvarze", "Sir Amik Varze"},
		{"awowogei", "King Awowogei"},
		{"culinaromancer", "Culinaromancer"},
	};

	/**
	 * Which quest a completion scroll's title names ("You have completed The Corsair Curse!",
	 * "'One Small Favour' completed!"), from the game's own quest list: the longest quest name found
	 * in the title, ignoring case and punctuation. A partial completion ("kind of") is not one.
	 */
	static String matchQuest(String title, Collection<String> questNames)
	{
		if (title == null || questNames == null)
		{
			return null;
		}
		String t = title.toLowerCase(Locale.ROOT);
		if (t.contains("kind of"))
		{
			return null;
		}
		String key = squash(t);
		if (!key.contains("recipefordisaster"))
		{
			for (String[] sub : RFD_SUBQUESTS)
			{
				String name = "Recipe for Disaster - " + sub[1];
				if (key.contains(sub[0]) && questNames.contains(name))
				{
					return name;
				}
			}
		}
		String best = null;
		int bestLen = 0;
		for (String q : questNames)
		{
			String k = squash(q.toLowerCase(Locale.ROOT));
			// Titles often drop a leading "The" ("You have completed Corsair Curse!").
			String bare = k.startsWith("the") && k.length() > 6 ? k.substring(3) : k;
			int len = key.contains(k) ? k.length() : key.contains(bare) ? bare.length() : 0;
			if (len > bestLen && len >= 4)
			{
				best = q;
				bestLen = len;
			}
		}
		return best;
	}

	private static String squash(String s)
	{
		StringBuilder b = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++)
		{
			char c = s.charAt(i);
			if (Character.isLetterOrDigit(c))
			{
				b.append(c);
			}
		}
		return b.toString();
	}

	/** For tests: the categories in a stable order. */
	static List<Category> categories()
	{
		return Collections.unmodifiableList(Arrays.asList(Category.values()));
	}
}
