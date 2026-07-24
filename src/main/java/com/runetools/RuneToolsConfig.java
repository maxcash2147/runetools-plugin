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
		description = "Paste the key you generated on gecharts.com (Account -> Connect RuneLite). Treat it like a password.",
		secret = true,
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
		return "https://gecharts.com";
	}

	@ConfigItem(
		keyName = "consent",
		name = "I consent to sending my account data to RuneTools",
		description = "The plugin sends nothing until this is enabled.",
		warning = "This plugin sends your account name (RSN), account hash, coin balance (which "
			+ "includes banked coins once you have opened your bank), number of free Grand Exchange "
			+ "slots, completed Grand Exchange trades, open GE offers, inventory, skill levels and "
			+ "experience, quest progress, Combat Achievements progress, your current Slayer task (its "
			+ "creature, amount remaining and originally assigned, assigning master, reward points, "
			+ "streak and task location), a capture "
			+ "timestamp, and your current world number and members-world status to the RuneTools "
			+ "server. When you save or "
			+ "update a gear setup it also sends that setup's worn/equipped gear, inventory, rune pouch, "
			+ "quiver and spellbook; when you open your Collection Log it sends your collection-log "
			+ "progress and the items on the pages you view; if you also run the OSRS TCG plugin it sends "
			+ "your TCG card collection (the card names and how many of each you own, foils included), your "
			+ "credits, packs opened and collection stats; when you export a bank tab to share it, it "
			+ "sends that tab's item layout to your gecharts.com profile; and only if you enable "
			+ "\"Include bank\" it "
			+ "sends your bank contents. This server is NOT controlled or verified by the RuneLite "
			+ "developers. Your API "
			+ "key is stored locally and sent only to that server over HTTPS. Enable only if you accept "
			+ "this.",
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
		description = "Upload coins, open GE offers and inventory so flip suggestions fit your real bankroll and free slots.",
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
		keyName = "inGameAlerts",
		name = "In-game price alerts",
		description = "Show a RuneLite notification in-game when one of your RuneTools price alerts triggers. Receives data only - nothing extra is sent.",
		section = syncSection,
		position = 5
	)
	default boolean inGameAlerts()
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
			+ "progress syncs immediately; a page's items sync when you open that page in-game.",
		section = setupsSection,
		position = 2
	)
	default boolean syncCollectionLog()
	{
		return true;
	}

	@ConfigItem(
		keyName = "syncTcg",
		name = "Sync OSRS TCG",
		description = "If you also run the OSRS TCG plugin, sync your card collection, credits and packs "
			+ "opened to RuneTools for the TCG hiscores and a collection page. Does nothing without that plugin.",
		section = setupsSection,
		position = 3
	)
	default boolean syncTcg()
	{
		return true;
	}

	@ConfigItem(
		keyName = "setupFuzzyMatch",
		name = "Fuzzy item matching",
		description = "Match item variations (charged/uncharged, (i), recolours) when filtering the bank, so a banked variant of a setup item still shows.",
		section = setupsSection,
		position = 4
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
		position = 5
	)
	default boolean setupBankLayout()
	{
		return true;
	}
}
