package com.runetools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.gameval.InterfaceID;
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
 * <p>All ids are verified public RuneLite constants (group {@code InterfaceID.COLLECTION},
 * component {@code InterfaceID.Collection.ITEMS_CONTENTS}, {@code ScriptID.COLLECTION_DRAW_LIST},
 * {@code VarPlayerID.COLLECTION_COUNT*}, {@code VarbitID.COLLECTION_LAST_TAB/_CATEGORY}). Every
 * read here touches widgets/varps and MUST run on the client thread.
 */
final class CollectionLog
{
	static final int GROUP = 621;            // InterfaceID.COLLECTION
	static final int DRAW_SCRIPT = 2731;     // ScriptID.COLLECTION_DRAW_LIST - fires when a page is drawn
	private static final int ITEMS_CONTENTS = 37;   // InterfaceID.Collection.ITEMS_CONTENTS (child of group 621)

	// Whole-log transmit. SETUP_SCRIPT fires when the interface is built; asking for a search makes
	// the server send every entry, one TRANSMIT_SCRIPT pre-fire each; INIT_SCRIPT restores the view.
	static final int SETUP_SCRIPT = 7797;      // collection log interface setup
	static final int TRANSMIT_SCRIPT = 4100;   // one obtained entry: args {?, itemId, quantity}
	private static final int INIT_SCRIPT = 2240;
	// VarbitID.COLLECTION_POH_HOST_BOOK_OPEN - 1 while viewing someone else's log via their POH.
	static final int VB_POH_HOST_BOOK_OPEN = 12061;

	// Per-tab page-list containers (InterfaceID.Collection.*_CONTAINER), indexed by tab 0..4.
	private static final int[] TAB_CONTAINER = {10, 14, 24, 26, 29};   // boss, raid, clue, minigame, other

	// Which page is currently open (varbits) - a stable (tab, category) key independent of the UI text.
	private static final int VB_LAST_TAB = 6905;       // VarbitID.COLLECTION_LAST_TAB
	private static final int VB_LAST_CATEGORY = 6906;  // VarbitID.COLLECTION_LAST_CATEGORY

	// Overall + per-tab obtained/total counters (VarPlayerID.*) - always available once logged in.
	private static final int VP_COUNT = 2943;      // VarPlayerID.COLLECTION_COUNT
	private static final int VP_COUNT_MAX = 2944;  // VarPlayerID.COLLECTION_COUNT_MAX
	private static final int[][] VP_TAB = {        // {obtained, total} per tab, in tab order
		{4613, 4614}, {4615, 4616}, {4617, 4618}, {4619, 4620}, {4621, 4622}};
	static final String[] TAB_NAMES = {"Bosses", "Raids", "Clues", "Minigames", "Other"};

	private CollectionLog()
	{
	}

	/** Overall + per-tab progress, straight from varps. Returns null before the counters populate. */
	static JsonObject readTotals(Client client)
	{
		int total = client.getVarpValue(VP_COUNT_MAX);
		if (total <= 0)
		{
			return null;
		}
		JsonObject t = new JsonObject();
		t.addProperty("obtained", Math.max(0, client.getVarpValue(VP_COUNT)));
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
		Widget container = client.getWidget(GROUP, ITEMS_CONTENTS);
		if (container == null)
		{
			return null;
		}
		Widget[] children = container.getDynamicChildren();
		if (children == null || children.length == 0)
		{
			return null;
		}

		int tab = client.getVarbitValue(VB_LAST_TAB);
		int category = client.getVarbitValue(VB_LAST_CATEGORY);

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
		Widget list = client.getWidget(GROUP, TAB_CONTAINER[tab]);
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
		return client.getVarbitValue(VB_POH_HOST_BOOK_OPEN) == 1;
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
