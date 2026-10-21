package com.runetools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;

/**
 * Reads the player's Collection Log from the game so the site can render a per-account replica.
 *
 * <p>The complete obtained set is NOT stored account-wide in varps (only a handful of gating
 * varbits plus overall/per-tab counters), so per-item data is read from the collection-log
 * <em>interface</em> as the player opens pages - the same approach the collection-log ecosystem
 * uses. Overall progress comes straight from varps and is available the moment you log in.
 *
 * <p>Interface, varbit and varp ids come from RuneLite's gameval constants ({@code InterfaceID},
 * {@code VarbitID}, {@code VarPlayerID}); the page-draw script is {@code ScriptID.COLLECTION_DRAW_LIST}.
 * The three whole-log scripts below ({@link #SETUP_SCRIPT}, {@link #TRANSMIT_SCRIPT},
 * {@link #INIT_SCRIPT}) have no public constant, so they stay numeric. Every read here touches
 * widgets/varps and MUST run on the client thread.
 */
final class CollectionLog
{
	// Whole-log transmit. SETUP_SCRIPT fires when the interface is built; asking for a search makes
	// the server send every entry, one TRANSMIT_SCRIPT pre-fire each; INIT_SCRIPT restores the view.
	static final int SETUP_SCRIPT = 7797;      // collection log interface setup
	static final int TRANSMIT_SCRIPT = 4100;   // one obtained entry: args {?, itemId, quantity}
	private static final int INIT_SCRIPT = 2240;

	// Per-tab page-list containers, indexed by tab 0..4: boss, raid, clue, minigame, other.
	private static final int[] TAB_CONTAINER = {
		InterfaceID.Collection.BOSS_CONTAINER,
		InterfaceID.Collection.RAID_CONTAINER,
		InterfaceID.Collection.CLUE_CONTAINER,
		InterfaceID.Collection.MINIGAME_CONTAINER,
		InterfaceID.Collection.OTHER_CONTAINER};

	// Per-tab {obtained, total} counters, in tab order - always available once logged in.
	private static final int[][] VP_TAB = {
		{VarPlayerID.COLLECTION_COUNT_BOSSES, VarPlayerID.COLLECTION_COUNT_BOSSES_MAX},
		{VarPlayerID.COLLECTION_COUNT_RAIDS, VarPlayerID.COLLECTION_COUNT_RAIDS_MAX},
		{VarPlayerID.COLLECTION_COUNT_CLUES, VarPlayerID.COLLECTION_COUNT_CLUES_MAX},
		{VarPlayerID.COLLECTION_COUNT_MINIGAMES, VarPlayerID.COLLECTION_COUNT_MINIGAMES_MAX},
		{VarPlayerID.COLLECTION_COUNT_OTHER, VarPlayerID.COLLECTION_COUNT_OTHER_MAX}};
	static final String[] TAB_NAMES = {"Bosses", "Raids", "Clues", "Minigames", "Other"};

	private CollectionLog()
	{
	}

	/** Overall + per-tab progress, straight from varps. Returns null before the counters populate. */
	static JsonObject readTotals(Client client)
	{
		int total = client.getVarpValue(VarPlayerID.COLLECTION_COUNT_MAX);
		if (total <= 0)
		{
			return null;
		}
		JsonObject t = new JsonObject();
		t.addProperty("obtained", Math.max(0, client.getVarpValue(VarPlayerID.COLLECTION_COUNT)));
		t.addProperty("total", total);
		JsonArray tabs = new JsonArray();
		for (int i = 0; i < VP_TAB.length; i++)
		{
			JsonObject o = new JsonObject();
			o.addProperty("name", TAB_NAMES[i]);
			o.addProperty("obtained", Math.max(0, client.getVarpValue(VP_TAB[i][0])));
			o.addProperty("total", Math.max(0, client.getVarpValue(VP_TAB[i][1])));
			tabs.add(o);
		}
		t.add("tabs", tabs);
		return t;
	}

	/** The page currently drawn in the open collection log, or null if it can't be read. */
	static Page readOpenPage(Client client, ItemManager itemManager)
	{
		Widget container = client.getWidget(InterfaceID.Collection.ITEMS_CONTENTS);
		if (container == null)
		{
			return null;
		}
		Widget[] children = container.getDynamicChildren();
		if (children == null || children.length == 0)
		{
			return null;
		}

		// Which page is open - a stable (tab, category) key independent of the UI text.
		int tab = client.getVarbitValue(VarbitID.COLLECTION_LAST_TAB);
		int category = client.getVarbitValue(VarbitID.COLLECTION_LAST_CATEGORY);

		JsonArray items = new JsonArray();
		int hash = 1;
		for (Widget w : children)
		{
			int id = w.getItemId();
			if (id <= 0)
			{
				continue;
			}
			boolean obtained = w.getOpacity() == 0;   // the log fades unobtained items (opacity > 0)
			int qty = obtained ? Math.max(1, w.getItemQuantity()) : 0;
			JsonObject it = new JsonObject();
			it.addProperty("id", id);
			it.addProperty("obtained", obtained);
			if (obtained && qty > 1)
			{
				it.addProperty("qty", qty);
			}
			String name = itemName(itemManager, id);
			if (name != null)
			{
				it.addProperty("name", name);
			}
			items.add(it);
			hash = hash * 31 + id * 2 + (obtained ? 1 : 0) + qty * 7;
		}
		if (items.size() == 0)
		{
			return null;
		}

		JsonObject page = new JsonObject();
		page.addProperty("tab", tab);
		page.addProperty("category", category);
		String pageName = readPageName(client, tab, category);
		if (pageName != null && !pageName.isEmpty())
		{
			page.addProperty("name", pageName);
		}
		page.add("items", items);
		return new Page(tab + ":" + category, hash, page);
	}

	private static String itemName(ItemManager itemManager, int id)
	{
		if (itemManager == null)
		{
			return null;
		}
		try
		{
			String n = itemManager.getItemComposition(id).getName();
			return n != null && !n.isEmpty() && !n.equalsIgnoreCase("null") ? n : null;
		}
		catch (RuntimeException ex)
		{
			return null;
		}
	}

	/**
	 * Best-effort page title from the active tab's page list; may be null (the (tab, category) key
	 * still identifies the page). Layout is UI-dependent, so this picks the category-th text entry.
	 */
	private static String readPageName(Client client, int tab, int category)
	{
		if (tab < 0 || tab >= TAB_CONTAINER.length || category < 0)
		{
			return null;
		}
		Widget list = client.getWidget(TAB_CONTAINER[tab]);
		if (list == null)
		{
			return null;
		}
		Widget[] entries = list.getDynamicChildren();
		if (entries == null)
		{
			return null;
		}
		int seen = -1;
		for (Widget e : entries)
		{
			String txt = e.getText();
			if (txt != null && !txt.isEmpty())
			{
				if (++seen == category)
				{
					return txt;
				}
			}
		}
		return null;
	}

	// -- whole-log capture: ask the SERVER for everything, once ----------------------------- //

	/**
	 * True when the open collection log belongs to SOMEONE ELSE - you are viewing it through a POH
	 * Adventure Log. Their items must never be read: they would be uploaded under your account and
	 * shown as yours, and collecting data about other players is a listed Plugin Hub rejection
	 * reason. Every capture path checks this.
	 */
	static boolean isOtherPlayers(Client client)
	{
		return client.getVarbitValue(VarbitID.COLLECTION_POH_HOST_BOOK_OPEN) == 1;
	}

	/**
	 * Ask the server to transmit the whole collection log. The "Search" op on the interface's search
	 * toggle is what makes the server send every entry (each arrives as its own
	 * {@link #TRANSMIT_SCRIPT} pre-fire); re-running the interface's init script then puts the view
	 * back how the player left it.
	 *
	 * <p>Why this is acceptable for a Plugin Hub plugin: RuneLite's "Rejected or Rolled Back
	 * Features" wiki page lists {@code Client.menuAction} as a sensitive API and names exactly this
	 * call, the collection log Search toggle, as its one acceptable use ("fetching collection log
	 * data"). It is an interface-only op: it opens no other interface, moves nothing, and has no effect in the
	 * game world. The plugin only makes it when the player has just opened their own Collection Log
	 * themselves, at most once per opening, never for a log viewed in someone else's house, and only
	 * when they have consented to syncing and connected this character. The init script is the log's
	 * own UI script and sends nothing to the server.
	 *
	 * <p>The call is kept textually identical to RuneLite's published form so a reviewer can match
	 * it at a glance. Client thread only.
	 */
	static void requestFullLog(Client client)
	{
		client.menuAction(-1, InterfaceID.Collection.SEARCH_TOGGLE, MenuAction.CC_OP, 1, -1, "Search", null);
		client.runScript(INIT_SCRIPT);
	}

	/** A single collection-log page read from the interface, with a change hash for dedup. */
	static final class Page
	{
		final String key;        // "tab:category" - stable page identity
		final int hash;          // content hash so an unchanged page isn't re-sent
		final JsonObject json;   // {tab, category, name?, items:[{id, obtained, qty?, name?}]}

		Page(String key, int hash, JsonObject json)
		{
			this.key = key;
			this.hash = hash;
			this.json = json;
		}
	}
}
