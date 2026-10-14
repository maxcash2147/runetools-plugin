package com.runetools;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(RuneToolsConfig.GROUP)
public interface RuneToolsConfig extends Config
{
	String GROUP = "runetools";

	@ConfigSection(
		name = "Connection",
		description = "How the plugin talks to the RuneTools site",
		position = 0
	)
	String connectionSection = "connection";

	@ConfigSection(
		name = "What to sync",
		description = "Which data the plugin uploads",
		position = 1
	)
	String syncSection = "sync";

	@ConfigItem(
		keyName = "apiKey",
		name = "API key",
		description = "Legacy manual key. You no longer need this - just log in and click \"Open RuneTools\" "
			+ "in the panel; each character links itself automatically. Kept only for advanced/manual setups.",
		secret = true,
		hidden = true,
		section = connectionSection,
		position = 0
	)
	default String apiKey()
	{
		return "";
	}

	@ConfigItem(
		keyName = "serverUrl",
		name = "Server URL",
		description = "The RuneTools server to sync with. Leave as the default unless you are testing against a local/staging server.",
		section = connectionSection,
		position = 1
	)
	default String serverUrl()
	{
		return "https://runetools.com";
	}

	@ConfigItem(
		keyName = "consent",
		name = "I consent to sending my account data to RuneTools",
		description = "The plugin sends nothing until this is enabled.",
		// Keep this in step with what the plugin actually sends (RuneToolsApiClient) and with the
		// README's "What it sends" table.
		warning = "Once enabled, and once you connect a character, this plugin sends the following "
			+ "to the RuneTools server:\n"
			+ "- Your character name (RSN), account hash and a capture time, to link the character to "
			+ "your RuneTools account.\n"
			+ "- Completed Grand Exchange trades (item, buy or sell, quantity, price, time), for "
			+ "profit tracking.\n"
			+ "- Your coins (including banked coins once you open your bank), free GE slots, open GE "
			+ "offers, inventory and whether you are on a members world, for flip suggestions that "
			+ "fit your bankroll.\n"
			+ "- Your skill levels and experience, for level-aware calculators and to prove the "
			+ "character is yours (the site checks it against the official hiscores).\n"
			+ "- Your Combat Achievements (the task list and which tasks you have completed), for the "
			+ "site's planner.\n"
			+ "- Your Collection Log progress counts, the items you have obtained when you open "
			+ "the log in-game, and the name and time of each new item the game announces, for a "
			+ "browsable copy of your log and your drop timeline on the site.\n"
			+ "- A gear setup's worn gear, inventory, rune pouch, quiver and spellbook, when you save "
			+ "or update that setup.\n"
			+ "- A bank tab's item layout, only when you export it to share it.\n"
			+ "- Your bank contents, only if you turn on \"Include bank\".\n"
			+ "- Combat timing totals, only if you turn on \"Measure combat timing\" (see that setting).\n"
			+ "- Achievements as they happen, for the Adventurer's Log on your public profile: level-ups, "
			+ "quests, achievement diaries, combat tasks, pets, valuable and untradeable drops (the item and "
			+ "the value the game shows), boss kill-count milestones and personal bests, and clue "
			+ "milestones and valuable clue rewards.\n"
			+ "Nothing else is sent. The automatic syncs can each be switched off under \"What to "
			+ "sync\", \"Gear setups\", \"Combat timing\" and \"Adventurer's Log\", and your profile can be "
			+ "made private on the site.\n"
			// The Plugin Hub requires this sentence, near enough verbatim, on any config item that
			// turns on a third-party-server feature. Without it the plugin fails review.
			+ "This feature submits your IP address to a 3rd-party server not controlled or verified "
			+ "by RuneLite developers. Your API key is stored locally and sent only to that server "
			+ "over HTTPS. Enable only if you accept this.",
		section = connectionSection,
		position = 2
	)
	default boolean consent()
	{
		return false;
	}

	@ConfigItem(
		keyName = "syncTrades",
		name = "Sync GE trades",
		description = "Upload completed Grand Exchange buys and sells for automatic profit tracking on the site.",
		section = syncSection,
		position = 0
	)
	default boolean syncTrades()
	{
		return true;
	}

	@ConfigItem(
		keyName = "syncState",
		name = "Sync account state",
		description = "Upload coins, open GE offers and inventory so flip suggestions fit your real bankroll and free slots, "
			+ "plus your skill levels and experience for level-aware calculators and to prove the character is yours.",
		section = syncSection,
		position = 1
	)
	default boolean syncState()
	{
		return true;
	}

	@ConfigItem(
		keyName = "syncBank",
		name = "Include bank",
		description = "Also include bank contents (only read when you open your bank). Powers holdings P/L and the best-loadout-from-bank feature.",
		section = syncSection,
		position = 2
	)
	default boolean syncBank()
	{
		return false;
	}

	@ConfigSection(
		name = "Gear setups",
		description = "Sync loadouts from RuneTools and filter your bank to them",
		position = 2
	)
	String setupsSection = "setups";

	@ConfigItem(
		keyName = "syncSetups",
		name = "Sync gear setups",
		description = "Pull the gear/inventory loadouts you saved on RuneTools so you can filter your bank to them in-game, "
			+ "and add bank layouts you imported on the site as bank tabs. Checked when you open the RuneTools panel "
			+ "or your bank, and every few minutes otherwise.",
		section = setupsSection,
		position = 0
	)
	default boolean syncSetups()
	{
		return true;
	}

	@ConfigItem(
		keyName = "syncCombatAchievements",
		name = "Sync Combat Achievements",
		description = "Sync your Combat Achievement tasks and completion to RuneTools to view and plan them on the site.",
		section = setupsSection,
		position = 1
	)
	default boolean syncCombatAchievements()
	{
		return true;
	}

	@ConfigItem(
		keyName = "syncCollectionLog",
		name = "Sync Collection Log",
		description = "Sync your Collection Log to RuneTools for a browsable replica on the site. Overall "
			+ "progress syncs while you play. Each time you open your own Collection Log in-game, the "
			+ "plugin asks the game once for the whole log (the same request the log's Search button "
			+ "makes; nothing changes on screen), and the pages you look at add their names and exact "
			+ "quantities. When the game announces a new item in chat (\"New item added to your collection "
			+ "log\"), its name and the time go to your drop timeline straight away. Only works once you "
			+ "have consented and connected this character.",
		section = setupsSection,
		position = 2
	)
	default boolean syncCollectionLog()
	{
		return true;
	}

	@ConfigItem(
		keyName = "setupFuzzyMatch",
		name = "Fuzzy item matching",
		description = "Match item variations (charged/uncharged, (i), recolours) when filtering the bank, so a banked variant of a setup item still shows.",
		section = setupsSection,
		position = 3
	)
	default boolean setupFuzzyMatch()
	{
		return true;
	}

	@ConfigItem(
		keyName = "setupBankLayout",
		name = "Arrange bank to setup",
		description = "Position bank items like the gear setup (equipment on the left, inventory on the right). Off = filter only.",
		section = setupsSection,
		position = 4
	)
	default boolean setupBankLayout()
	{
		return true;
	}

	@ConfigSection(
		name = "Combat timing",
		description = "Measure your real latency and protection prayers for the site's trainers",
		position = 3
	)
	String timingSection = "timing";

	@ConfigItem(
		keyName = "syncTiming",
		name = "Measure combat timing",
		description = "Measure how your real clicks land on game ticks and show it on runetools.com/timing: "
			+ "your effective latency (the Ping to use in the trainers) and your protection prayers against "
			+ "Jad and the Hunllef. Only totals are sent.",
		// Shown when the setting is turned on. Keep in step with CombatTiming.report() and the README.
		warning = "With this on, while you play the plugin watches your equip clicks, your clicks on the "
			+ "protection prayers, your overhead prayer and the attacks Jad and the Hunllef aim at you. "
			+ "About once a minute it sends RuneTools only these totals for the session:\n"
			+ "- How many of those clicks landed on the next game tick or one tick later, counted in 20 ms "
			+ "steps by how long before the tick they were made (this is how your latency is worked out).\n"
			+ "- For Jad, multi-Jad fights and the Hunllef: how many attacks you prayed against in time, "
			+ "one tick late, with the wrong prayer or with none.\n"
			+ "No single click, its time or where you clicked is ever sent. Nothing is measured or sent while "
			+ "this is off, and turning it off drops anything not yet sent.",
		section = timingSection,
		position = 0
	)
	default boolean syncTiming()
	{
		return false;
	}

	@ConfigSection(
		name = "Adventurer's Log",
		description = "Put your achievements on the Adventurer's Log of your RuneTools profile as they happen",
		position = 4
	)
	String logSection = "adventureLog";

	@ConfigItem(
		keyName = "logLevels",
		name = "Level-ups",
		description = "Log each level you gain, your 99s, total-level milestones and maxing. Seasonal, "
			+ "Deadman, beta and other special worlds are never logged.",
		section = logSection,
		position = 0
	)
	default boolean logLevels()
	{
		return true;
	}

	@ConfigItem(
		keyName = "logQuests",
		name = "Quests and diaries",
		description = "Log each quest you complete and each achievement diary tier you finish.",
		section = logSection,
		position = 1
	)
	default boolean logQuests()
	{
		return true;
	}

	@ConfigItem(
		keyName = "logCombat",
		name = "Combat tasks and kills",
		description = "Log the combat tasks you complete, your first kill and kill-count milestones (10, 50, "
			+ "100, 250, 500, 1,000 and on) at each boss, and new personal bests.",
		section = logSection,
		position = 2
	)
	default boolean logCombat()
	{
		return true;
	}

	@ConfigItem(
		keyName = "logLoot",
		name = "Pets and drops",
		description = "Log pets, and the game's own \"Valuable drop\" (at or above the value below) and "
			+ "\"Untradeable drop\" messages. Those messages only appear when they are switched on in the "
			+ "game's settings (Chat, Loot drop notifications).",
		section = logSection,
		position = 3
	)
	default boolean logLoot()
	{
		return true;
	}

	@ConfigItem(
		keyName = "logClues",
		name = "Clue scrolls",
		description = "Log clue milestones per tier (your first, 10th, 25th, 50th, 100th and on) and clue "
			+ "rewards worth at least the value below.",
		section = logSection,
		position = 4
	)
	default boolean logClues()
	{
		return true;
	}

	@ConfigItem(
		keyName = "logMinValue",
		name = "Smallest drop to log (gp)",
		description = "Valuable drops and clue rewards worth less than this are left out of your log.",
		section = logSection,
		position = 5
	)
	default int logMinValue()
	{
		return 1_000_000;
	}
}
