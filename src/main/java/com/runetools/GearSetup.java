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

	/** Item ids the bank filter/layout should cover: worn equipment + inventory + rune pouch. */
	public Collection<Integer> filterItemIds()
	{
		List<Integer> ids = new ArrayList<>();
		if (equipment != null)
		{
			ids.addAll(equipment.values());
		}
		if (inventory != null)
		{
			for (GearSetupItem it : inventory)
			{
				ids.add(it.getItemId());
			}
		}
		if (runePouch != null)
		{
			for (GearSetupItem it : runePouch)
			{
				ids.add(it.getItemId());
			}
		}
		if (quiver != null)
		{
			for (GearSetupItem it : quiver)
			{
				ids.add(it.getItemId());
			}
		}
		if (additional != null)
		{
			for (GearSetupItem it : additional)
			{
				ids.add(it.getItemId());
			}
		}
		return ids;
	}

	static GearSetup fromJson(JsonObject o)
	{
		GearSetup s = new GearSetup();
		s.id = o.get("id").getAsLong();
		s.name = str(o, "name");
		s.source = str(o, "source");
		s.style = str(o, "style");
		s.twoHanded = o.has("two_handed") && !o.get("two_handed").isJsonNull() && o.get("two_handed").getAsBoolean();
		s.updatedAt = o.has("updated_at") && !o.get("updated_at").isJsonNull()
			? (long) o.get("updated_at").getAsDouble() : 0L;

		JsonElement eq = o.get("equipment");
		if (eq != null && eq.isJsonObject())
		{
			for (Map.Entry<String, JsonElement> e : eq.getAsJsonObject().entrySet())
			{
				if (!e.getValue().isJsonNull())
				{
					s.equipment.put(e.getKey(), e.getValue().getAsInt());
				}
			}
		}
		s.inventory = items(o, "inventory");
		s.runePouch = items(o, "rune_pouch");
		s.quiver = items(o, "quiver");
		s.additional = items(o, "additional");
		s.spellbook = o.has("spellbook") && !o.get("spellbook").isJsonNull() ? o.get("spellbook").getAsInt() : -1;
		s.monsterId = o.has("monster_id") && !o.get("monster_id").isJsonNull() ? o.get("monster_id").getAsInt() : null;
		s.fuzzy = new HashSet<>();
		JsonElement fz = o.get("fuzzy");
		if (fz != null && fz.isJsonArray())
		{
			for (JsonElement e : fz.getAsJsonArray())
			{
				if (!e.isJsonNull())
				{
					s.fuzzy.add(e.getAsInt());
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
				if (e.isJsonObject())
				{
					JsonObject j = e.getAsJsonObject();
					out.add(new GearSetupItem(j.get("item_id").getAsInt(),
						j.has("qty") ? j.get("qty").getAsInt() : 1));
				}
			}
		}
		return out;
	}

	private static String str(JsonObject o, String key)
	{
		JsonElement e = o.get(key);
		return e != null && !e.isJsonNull() ? e.getAsString() : null;
	}
}
