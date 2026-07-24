package com.runetools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Data;

/** A gear/inventory loadout authored on the RuneTools site and pulled by the plugin. */
@Data
public class GearSetup
{
	private long id;
	private String name;
	private String source;               // dps | bis | manual | imported
	private String style;                // melee | ranged | magic | null
	private boolean twoHanded;           // weapon is 2h -> shield slot is empty
	private Map<String, Integer> equipment = new LinkedHashMap<>();  // slot -> item id
	private List<GearSetupItem> inventory = new ArrayList<>();
	private List<GearSetupItem> runePouch = new ArrayList<>();
	private List<GearSetupItem> quiver = new ArrayList<>();
	private List<GearSetupItem> additional = new ArrayList<>();
	private int spellbook = -1;                                     // 0 std|1 ancient|2 lunar|3 arceuus|-1 none
	private Integer monsterId;
	private Set<Integer> fuzzy = new HashSet<>();                   // item ids matched by variation
	private String notes;
	private long updatedAt;

	public boolean isFuzzy(int itemId)
	{
		return fuzzy != null && fuzzy.contains(itemId);
	}

	/** Flip fuzzy for an item id; returns the new state. */
	public boolean toggleFuzzy(int itemId)
	{
		if (fuzzy == null)
		{
			fuzzy = new HashSet<>();
		}
		if (fuzzy.remove(itemId))
		{
			return false;
		}
		fuzzy.add(itemId);
		return true;
	}

	/** Item ids the bank filter/layout should cover: equipment + inventory + rune pouch + quiver + additional. */
	public Collection<Integer> filterItemIds()
	{
		List<Integer> ids = new ArrayList<>();
		if (equipment != null)
		{
			for (Integer id : equipment.values())
			{
				if (id != null && id > 0)
				{
					ids.add(id);
				}
			}
		}
		addItemIds(ids, inventory);
		addItemIds(ids, runePouch);
		addItemIds(ids, quiver);
		addItemIds(ids, additional);
		return ids;
	}

	private static void addItemIds(List<Integer> ids, List<GearSetupItem> items)
	{
		if (items == null)
		{
			return;
		}
		for (GearSetupItem it : items)
		{
			if (it.getItemId() > 0)   // skip positional empty slots
			{
				ids.add(it.getItemId());
			}
		}
	}

	/**
	 * Same items as {@link #filterItemIds()} but aggregated to id -> total quantity (equipment counts
	 * as one each). Used for the "missing items / cost to complete" diff so a stack of 1000 runes is
	 * priced as 1000, and the same id appearing in two lists is counted once.
	 */
	public Map<Integer, Integer> filterItemCounts()
	{
		LinkedHashMap<Integer, Integer> counts = new LinkedHashMap<>();
		if (equipment != null)
		{
			for (Integer id : equipment.values())
			{
				if (id != null && id > 0)
				{
					counts.merge(id, 1, Integer::sum);
				}
			}
		}
		addItemCounts(counts, inventory);
		addItemCounts(counts, runePouch);
		addItemCounts(counts, quiver);
		addItemCounts(counts, additional);
		return counts;
	}

	private static void addItemCounts(Map<Integer, Integer> counts, List<GearSetupItem> items)
	{
		if (items == null)
		{
			return;
		}
		for (GearSetupItem it : items)
		{
			if (it.getItemId() > 0)   // skip positional empty slots
			{
				counts.merge(it.getItemId(), Math.max(1, it.getQty()), Integer::sum);
			}
		}
	}

	/** Upper bound on any single item list from an (untrusted) setup, to cap Swing/render work. */
	private static final int MAX_ITEMS = 500;

	static GearSetup fromJson(JsonObject o)
	{
		GearSetup s = new GearSetup();
		s.id = o.has("id") && !o.get("id").isJsonNull() ? asLong(o.get("id"), 0L) : 0L;
		s.name = str(o, "name");
		s.source = str(o, "source");
		s.style = str(o, "style");
		s.twoHanded = o.has("two_handed") && !o.get("two_handed").isJsonNull() && asBool(o.get("two_handed"));
		s.updatedAt = o.has("updated_at") && !o.get("updated_at").isJsonNull() ? asEpoch(o.get("updated_at")) : 0L;

		JsonElement eq = o.get("equipment");
		if (eq != null && eq.isJsonObject())
		{
			for (Map.Entry<String, JsonElement> e : eq.getAsJsonObject().entrySet())
			{
				if (s.equipment.size() >= MAX_ITEMS)
				{
					break;
				}
				if (!e.getValue().isJsonNull())
				{
					int id = asInt(e.getValue(), 0);
					if (id > 0)
					{
						s.equipment.put(e.getKey(), id);
					}
				}
			}
		}
		s.inventory = items(o, "inventory");
		s.runePouch = items(o, "rune_pouch");
		s.quiver = items(o, "quiver");
		s.additional = items(o, "additional");
		s.spellbook = o.has("spellbook") && !o.get("spellbook").isJsonNull() ? asInt(o.get("spellbook"), -1) : -1;
		s.monsterId = o.has("monster_id") && !o.get("monster_id").isJsonNull() ? asInt(o.get("monster_id"), 0) : null;
		s.fuzzy = new HashSet<>();
		JsonElement fz = o.get("fuzzy");
		if (fz != null && fz.isJsonArray())
		{
			for (JsonElement e : fz.getAsJsonArray())
			{
				if (!e.isJsonNull() && s.fuzzy.size() < MAX_ITEMS)
				{
					int id = asInt(e, 0);
					if (id > 0)
					{
						s.fuzzy.add(id);
					}
				}
			}
		}
		s.notes = str(o, "notes");
		return s;
	}

	/** Serialize for POST/PUT to the site (matches the /api/setups body). */
	JsonObject toJson()
	{
		JsonObject o = new JsonObject();
		o.addProperty("name", name != null ? name : "Setup");
		o.addProperty("source", source != null ? source : "manual");
		if (style != null)
		{
			o.addProperty("style", style);
		}
		if (monsterId != null)
		{
			o.addProperty("monster_id", monsterId);
		}
		if (spellbook >= 0)
		{
			o.addProperty("spellbook", spellbook);
		}
		JsonObject eq = new JsonObject();
		if (equipment != null)
		{
			for (Map.Entry<String, Integer> e : equipment.entrySet())
			{
				if (e.getValue() != null && e.getValue() > 0)
				{
					eq.addProperty(e.getKey(), e.getValue());
				}
			}
		}
		o.add("equipment", eq);
		o.add("inventory", itemsToJson(inventory));
		o.add("rune_pouch", itemsToJson(runePouch));
		o.add("quiver", itemsToJson(quiver));
		o.add("additional", itemsToJson(additional));
		JsonArray fzArr = new JsonArray();
		if (fuzzy != null)
		{
			for (int id : fuzzy)
			{
				fzArr.add(id);
			}
		}
		o.add("fuzzy", fzArr);
		if (notes != null)
		{
			o.addProperty("notes", notes);
		}
		return o;
	}

	private static JsonArray itemsToJson(List<GearSetupItem> list)
	{
		JsonArray arr = new JsonArray();
		if (list != null)
		{
			for (GearSetupItem it : list)
			{
				JsonObject j = new JsonObject();
				j.addProperty("item_id", it.getItemId());
				j.addProperty("qty", it.getQty());
				arr.add(j);
			}
		}
		return arr;
	}

	private static List<GearSetupItem> items(JsonObject o, String key)
	{
		List<GearSetupItem> out = new ArrayList<>();
		JsonElement el = o.get(key);
		if (el != null && el.isJsonArray())
		{
			for (JsonElement e : el.getAsJsonArray())
			{
				if (out.size() >= MAX_ITEMS)
				{
					break;
				}
				if (!e.isJsonObject())
				{
					continue;
				}
				JsonObject j = e.getAsJsonObject();
				if (!j.has("item_id") || j.get("item_id").isJsonNull())
				{
					continue;   // no item id
				}
				int itemId = asInt(j.get("item_id"), 0);
				int qty = j.has("qty") && !j.get("qty").isJsonNull() ? asInt(j.get("qty"), 1) : 1;
				out.add(new GearSetupItem(itemId, qty));
			}
		}
		return out;
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
			return e.getAsString();   // a nested object/array would throw - treat as absent
		}
		catch (RuntimeException ex)
		{
			return null;
		}
	}

	private static long asLong(JsonElement e, long fallback)
	{
		try
		{
			return e.getAsLong();
		}
		catch (RuntimeException ex)
		{
			return fallback;
		}
	}

	private static long asEpoch(JsonElement e)
	{
		try
		{
			return (long) e.getAsDouble();
		}
		catch (RuntimeException ex)
		{
			return 0L;
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
}
