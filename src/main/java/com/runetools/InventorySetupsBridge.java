package com.runetools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure data converter between a RuneTools {@link GearSetup} and the RuneLite
 * <a href="https://github.com/dillydill123/inventory-setups">Inventory Setups</a> plugin's
 * Export / Import STRING format.
 *
 * <p>Inventory Setups' "Export setup" button copies (and "Import setup" pastes) the <b>plain,
 * un-encoded JSON</b> of a single {@code InventorySetupPortable}. That wrapper is:
 * <pre>
 *   { "setup": &lt;InventorySetupSerializable&gt;, "layout": &lt;int[]&gt; }
 * </pre>
 * Both classes are Lombok {@code @Value} types with <b>no</b> {@code @SerializedName}, so Gson keys
 * are the raw (short) Java field names. The relevant setup keys are:
 * <pre>
 *   inv  List&lt;item&gt;   inventory   (28 fixed slots, dummy = null element)
 *   eq   List&lt;item&gt;   equipment   (14 fixed slots, dummy = null element)
 *   rp   List&lt;item&gt;   rune pouch  (nullable)
 *   bp   List&lt;item&gt;   bolt pouch  (nullable)
 *   qv   List&lt;item&gt;   quiver      (nullable)
 *   afi  Map&lt;Integer,item&gt; additional filtered items (nullable)
 *   name String        setup name
 *   notes String       notes (nullable)
 *   hc   Color         highlight color   (RuneLite serializes java.awt.Color as its int RGB "value")
 *   hd,dc,fb,uh,fv     misc flags/colors (all nullable -> omitted here so IS uses defaults)
 *   sb   Integer       spellbook (null = 0 standard; 1 ancient, 2 lunar, 3 arceuus)
 *   iId  Integer       icon id (nullable)
 *   ao   String        attack option (nullable)
 * </pre>
 * An item element is {@code InventorySetupItemSerializable}:
 * <pre>
 *   id  int      item id (-1 for a dummy, but dummies serialize as a null list element)
 *   q   Integer  quantity (null = 1)
 *   f   Boolean  fuzzy   (null = false)
 *   sc  enum     stack compare (null = None)
 * </pre>
 * An empty slot is a {@code null} element in the {@code inv}/{@code eq} list (the plugin turns a
 * null element back into its "dummy item", id -1, quantity 0).
 *
 * <p>Equipment index order is RuneLite's {@code EquipmentInventorySlot} (14 slots):
 * 0 head, 1 cape, 2 amulet(neck), 3 weapon, 4 body, 5 shield, 6 arms, 7 legs, 8 hair, 9 gloves(hands),
 * 10 boots(feet), 11 jaw, 12 ring, 13 ammo. RuneTools only uses 11 of these; the rest stay empty.
 *
 * <p>This class makes no RuneLite client / UI calls, so it is unit-testable headless.
 */
public final class InventorySetupsBridge
{
	/** Number of equipment slots Inventory Setups stores (EquipmentInventorySlot order). */
	static final int NUM_EQUIPMENT_ITEMS = 14;
	/** Number of inventory slots Inventory Setups stores. */
	static final int NUM_INVENTORY_ITEMS = 28;

	/** RuneTools slot name -> EquipmentInventorySlot index used by Inventory Setups. */
	static final Map<String, Integer> EQUIP_INDEX;

	static
	{
		Map<String, Integer> m = new LinkedHashMap<>();
		m.put("head", 0);
		m.put("cape", 1);
		m.put("neck", 2);    // AMULET
		m.put("weapon", 3);
		m.put("body", 4);
		m.put("shield", 5);
		m.put("legs", 7);
		m.put("hands", 9);   // GLOVES
		m.put("feet", 10);   // BOOTS
		m.put("ring", 12);
		m.put("ammo", 13);
		EQUIP_INDEX = m;
	}

	/** Inverse of {@link #EQUIP_INDEX}: equipment index -> RuneTools slot name. */
	private static final Map<Integer, String> INDEX_EQUIP;

	static
	{
		Map<Integer, String> m = new LinkedHashMap<>();
		for (Map.Entry<String, Integer> e : EQUIP_INDEX.entrySet())
		{
			m.put(e.getValue(), e.getKey());
		}
		INDEX_EQUIP = m;
	}

	private InventorySetupsBridge()
	{
	}

	// ------------------------------------------------------------------ export (RuneTools -> IS)

	/**
	 * Produce the exact string Inventory Setups' "Import setup" accepts for one setup: the plain JSON
	 * of an {@code InventorySetupPortable} wrapping this loadout. Never throws; returns {@code null}
	 * only if {@code s} is null.
	 */
	public static String toImportString(GearSetup s)
	{
		if (s == null)
		{
			return null;
		}

		JsonObject setup = new JsonObject();

		// name (required non-null on import)
		setup.addProperty("name", s.getName() != null ? s.getName() : "Setup");

		// equipment -> 14-slot list, empty slots are JSON null elements
		JsonArray eq = new JsonArray();
		Map<String, Integer> equipment = s.getEquipment();
		String[] byIndex = new String[NUM_EQUIPMENT_ITEMS];
		for (Map.Entry<Integer, String> e : INDEX_EQUIP.entrySet())
		{
			byIndex[e.getKey()] = e.getValue();
		}
		for (int i = 0; i < NUM_EQUIPMENT_ITEMS; i++)
		{
			String slot = byIndex[i];
			Integer id = (slot != null && equipment != null) ? equipment.get(slot) : null;
			eq.add(itemElement(id, 1, s, id));
		}
		setup.add("eq", eq);

		// inventory -> fixed 28-slot list, preserving positions/empties
		setup.add("inv", itemsList(s.getInventory(), NUM_INVENTORY_ITEMS, s));

		// rune pouch / quiver (nullable lists; omit when empty so IS uses "no pouch")
		JsonArray rp = itemsListLoose(s.getRunePouch(), s);
		if (rp != null)
		{
			setup.add("rp", rp);
		}
		JsonArray qv = itemsListLoose(s.getQuiver(), s);
		if (qv != null)
		{
			setup.add("qv", qv);
		}

		// additional filtered items -> Map<Integer id, item>
		JsonObject afi = additionalMap(s.getAdditional(), s);
		if (afi != null)
		{
			setup.add("afi", afi);
		}

		// spellbook: sb null means standard(0). RuneTools -1 (none) or 0 (standard) => omit.
		int sb = s.getSpellbook();
		if (sb > 0)
		{
			setup.addProperty("sb", sb);
		}

		// notes
		if (s.getNotes() != null && !s.getNotes().isEmpty())
		{
			setup.addProperty("notes", s.getNotes());
		}

		JsonObject portable = new JsonObject();
		portable.add("setup", setup);
		// layout is required non-null on import; an empty array is valid (IS regenerates a layout).
		portable.add("layout", new JsonArray());

		// toString(), not a Gson instance: the Plugin Hub blocks `new Gson()`, and this tree is built
		// by hand out of JsonObject/JsonArray so its own serialisation is all we need.
		return portable.toString();
	}

	/**
	 * Every setup as one file Inventory Setups' "Mass Import Setups" reads: a JSON array of the same
	 * objects its single-setup Import takes.
	 */
	public static String toMassExport(List<GearSetup> setups)
	{
		StringBuilder sb = new StringBuilder("[");
		int n = 0;
		for (GearSetup s : setups)
		{
			String one = toImportString(s);
			if (one == null)
			{
				continue;
			}
			if (n++ > 0)
			{
				sb.append(',');
			}
			sb.append(one);
		}
		return sb.append(']').toString();
	}

	/** One item list element: JSON null for an empty/absent item, else {id,q?,f?}. */
	private static JsonElement itemElement(Integer id, int qty, GearSetup s, Integer fuzzyId)
	{
		if (id == null || id <= 0)
		{
			return JsonNull.INSTANCE;
		}
		JsonObject o = new JsonObject();
		o.addProperty("id", id);
		if (qty != 1)
		{
			o.addProperty("q", qty);
		}
		if (s != null && fuzzyId != null && s.isFuzzy(fuzzyId))
		{
			o.addProperty("f", true);
		}
		return o;
	}

	/** Fixed-length list (inv/eq): positions preserved, gaps and overflow become null elements. */
	private static JsonArray itemsList(List<GearSetupItem> items, int size, GearSetup s)
	{
		JsonArray arr = new JsonArray();
		for (int i = 0; i < size; i++)
		{
			GearSetupItem it = (items != null && i < items.size()) ? items.get(i) : null;
			if (it == null || it.getItemId() <= 0)
			{
				arr.add(JsonNull.INSTANCE);
			}
			else
			{
				arr.add(itemElement(it.getItemId(), it.getQty(), s, it.getItemId()));
			}
		}
		return arr;
	}

	/** Variable-length list (rp/qv): null when there is nothing to store. */
	private static JsonArray itemsListLoose(List<GearSetupItem> items, GearSetup s)
	{
		if (items == null || items.isEmpty())
		{
			return null;
		}
		boolean any = false;
		JsonArray arr = new JsonArray();
		for (GearSetupItem it : items)
		{
			if (it != null && it.getItemId() > 0)
			{
				arr.add(itemElement(it.getItemId(), it.getQty(), s, it.getItemId()));
				any = true;
			}
			else
			{
				arr.add(JsonNull.INSTANCE);
			}
		}
		return any ? arr : null;
	}

	/** additional filtered items map: id -> item, keyed by string item id (Gson keys map ints as strings). */
	private static JsonObject additionalMap(List<GearSetupItem> items, GearSetup s)
	{
		if (items == null || items.isEmpty())
		{
			return null;
		}
		JsonObject o = new JsonObject();
		for (GearSetupItem it : items)
		{
			if (it != null && it.getItemId() > 0)
			{
				o.add(String.valueOf(it.getItemId()), itemElement(it.getItemId(), it.getQty(), s, it.getItemId()));
			}
		}
		return o.size() > 0 ? o : null;
	}

	// ------------------------------------------------------------------ import (IS -> RuneTools)

	/**
	 * Parse an Inventory Setups export string into a RuneTools {@link GearSetup} (reverse mapping).
	 * Defensive: returns {@code null} (never throws) on any malformed / empty / non-matching input.
	 */
	public static GearSetup fromExportString(String exportString)
	{
		if (exportString == null || exportString.trim().isEmpty())
		{
			return null;
		}
		try
		{
			JsonElement root = new JsonParser().parse(exportString);
			if (root == null || !root.isJsonObject())
			{
				return null;
			}
			JsonObject portable = root.getAsJsonObject();

			// Accept either the portable wrapper {setup:..} or a bare setup object.
			JsonObject setup;
			if (portable.has("setup") && portable.get("setup").isJsonObject())
			{
				setup = portable.getAsJsonObject("setup");
			}
			else if (portable.has("eq") || portable.has("inv"))
			{
				setup = portable;
			}
			else
			{
				return null;
			}

			GearSetup out = new GearSetup();
			out.setSource("imported");
			out.setName(strOr(setup, "name", "Imported setup"));
			out.setNotes(str(setup, "notes"));

			// spellbook (sb): null/absent means standard (0)
			int sb = 0;
			JsonElement sbEl = setup.get("sb");
			if (sbEl != null && !sbEl.isJsonNull())
			{
				sb = asInt(sbEl, 0);
			}
			out.setSpellbook(sb);

			// equipment: 14-slot list -> {slotName -> itemId} for the slots RuneTools knows
			Map<String, Integer> equipment = new LinkedHashMap<>();
			JsonArray eq = arr(setup, "eq");
			if (eq != null)
			{
				for (int i = 0; i < eq.size(); i++)
				{
					String slot = INDEX_EQUIP.get(i);
					if (slot == null)
					{
						continue;   // arms/hair/jaw etc. have no RuneTools slot
					}
					Integer id = itemId(eq.get(i));
					if (id != null && id > 0)
					{
						equipment.put(slot, id);
						markFuzzy(out, eq.get(i), id);
					}
				}
			}
			out.setEquipment(equipment);
			out.setTwoHanded(!equipment.containsKey("shield") && equipment.containsKey("weapon"));

			// inventory: preserve positions (empty slots become id 0 placeholders)
			out.setInventory(readItems(setup, "inv", out, true));
			out.setRunePouch(readItems(setup, "rp", out, false));
			out.setQuiver(readItems(setup, "qv", out, false));
			out.setAdditional(readAfi(setup, out));

			return out;
		}
		catch (RuntimeException ex)
		{
			return null;
		}
	}

	/** Read an inv/eq/rp/qv list. When {@code keepEmpty}, empty slots are kept as id-0 placeholders. */
	private static List<GearSetupItem> readItems(JsonObject setup, String key, GearSetup out, boolean keepEmpty)
	{
		List<GearSetupItem> list = new ArrayList<>();
		JsonArray a = arr(setup, key);
		if (a == null)
		{
			return list;
		}
		for (JsonElement el : a)
		{
			Integer id = itemId(el);
			if (id != null && id > 0)
			{
				int qty = qty(el);
				list.add(new GearSetupItem(id, qty));
				markFuzzy(out, el, id);
			}
			else if (keepEmpty)
			{
				list.add(new GearSetupItem(0, 0));   // positional empty slot
			}
		}
		// trim trailing empties for inventory so a mostly-empty inv doesn't carry 28 placeholders
		if (keepEmpty)
		{
			int last = -1;
			for (int i = 0; i < list.size(); i++)
			{
				if (list.get(i).getItemId() > 0)
				{
					last = i;
				}
			}
			if (last < 0)
			{
				return new ArrayList<>();
			}
			return new ArrayList<>(list.subList(0, last + 1));
		}
		return list;
	}

	/** additional filtered items: Map<Integer id, item> -> list of items. */
	private static List<GearSetupItem> readAfi(JsonObject setup, GearSetup out)
	{
		List<GearSetupItem> list = new ArrayList<>();
		JsonElement el = setup.get("afi");
		if (el == null || !el.isJsonObject())
		{
			return list;
		}
		for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet())
		{
			Integer id = itemId(e.getValue());
			if (id == null || id <= 0)
			{
				// key is the item id; fall back to it when the value has no explicit id
				try
				{
					id = Integer.parseInt(e.getKey());
				}
				catch (NumberFormatException nfe)
				{
					continue;
				}
			}
			if (id != null && id > 0)
			{
				list.add(new GearSetupItem(id, qty(e.getValue())));
				markFuzzy(out, e.getValue(), id);
			}
		}
		return list;
	}

	private static void markFuzzy(GearSetup out, JsonElement el, int id)
	{
		if (el != null && el.isJsonObject())
		{
			JsonElement f = el.getAsJsonObject().get("f");
			if (f != null && !f.isJsonNull() && asBool(f))
			{
				if (out.getFuzzy() == null)
				{
					out.setFuzzy(new java.util.HashSet<>());
				}
				out.getFuzzy().add(id);
			}
		}
	}

	// ------------------------------------------------------------------ small json helpers

	/** Item id from a list element (null element => absent). */
	private static Integer itemId(JsonElement el)
	{
		if (el == null || el.isJsonNull() || !el.isJsonObject())
		{
			return null;
		}
		JsonElement idEl = el.getAsJsonObject().get("id");
		if (idEl == null || idEl.isJsonNull())
		{
			return null;
		}
		return asInt(idEl, -1);
	}

	private static int qty(JsonElement el)
	{
		if (el != null && el.isJsonObject())
		{
			JsonElement q = el.getAsJsonObject().get("q");
			if (q != null && !q.isJsonNull())
			{
				int v = asInt(q, 1);
				return v > 0 ? v : 1;
			}
		}
		return 1;   // q null = 1
	}

	private static JsonArray arr(JsonObject o, String key)
	{
		JsonElement e = o.get(key);
		return (e != null && e.isJsonArray()) ? e.getAsJsonArray() : null;
	}

	private static String str(JsonObject o, String key)
	{
		JsonElement e = o.get(key);
		if (e == null || e.isJsonNull())
		{
			return null;
		}
		try
		{
			return e.getAsString();
		}
		catch (RuntimeException ex)
		{
			return null;
		}
	}

	private static String strOr(JsonObject o, String key, String fallback)
	{
		String v = str(o, key);
		return v != null ? v : fallback;
	}

	private static int asInt(JsonElement e, int fallback)
	{
		try
		{
			return e.getAsInt();
		}
		catch (RuntimeException ex)
		{
			return fallback;
		}
	}

	private static boolean asBool(JsonElement e)
	{
		try
		{
			return e.getAsBoolean();
		}
		catch (RuntimeException ex)
		{
			return false;
		}
	}
}
