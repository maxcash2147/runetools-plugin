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
			+ "- Your Collection Log progress counts, and the items you have obtained when you open "
			+ "the log in-game, for a browsable copy of your log on the site.\n"
			+ "- A gear setup's worn gear, inventory, rune pouch, quiver and spellbook, when you save "
			+ "or update that setup.\n"
			+ "- A bank tab's item layout, only when you export it to share it.\n"
			+ "- Your bank contents, only if you turn on \"Include bank\".\n"
			+ "Each of these can be switched off under \"What to sync\" and \"Gear setups\".\n"
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
		description = "Pull the gear/inventory loadouts you saved on RuneTools so you can filter your bank to them in-game.",
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
			+ "progress syncs immediately; opening the log reads the whole thing in one request, and the "
			+ "pages you look at add their names and exact quantities.",
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
}
