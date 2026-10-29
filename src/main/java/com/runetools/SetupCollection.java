package com.runetools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import lombok.Value;

/**
 * A named group of setups shared as one on the site ("3 Man Bandos"), as one setup sees it: which
 * collection, the role this setup plays in it ("Tank") and its place in the list. Optional: a setup
 * the site sends without one is simply in no collection.
 */
@Value
class SetupCollection
{
	private static final int MAX_PER_SETUP = 20;

	long id;
	String name;
	String role;
	int order;

	/**
	 * The collections a setup's JSON names: {@code collection} (one object) and/or {@code collections}
	 * (an array of them). An entry with a missing key or a wrong type is ignored, so a malformed field
	 * reads as "no collection" and never breaks the setup itself.
	 */
	static List<SetupCollection> parse(JsonObject setup)
	{
		List<SetupCollection> out = new ArrayList<>();
		if (setup == null)
		{
			return out;
		}
		add(out, setup.get("collection"));
		JsonElement many = setup.get("collections");
		if (many != null && many.isJsonArray())
		{
			for (JsonElement e : many.getAsJsonArray())
			{
				if (out.size() >= MAX_PER_SETUP)
				{
					break;
				}
				add(out, e);
			}
		}
		return out;
	}

	private static void add(List<SetupCollection> out, JsonElement e)
	{
		SetupCollection c = one(e);
		if (c == null)
		{
			return;
		}
		for (SetupCollection have : out)
		{
			if (have.id == c.id)
			{
				return;   // named twice (in both fields): once is enough
			}
		}
		out.add(c);
	}

	/**
	 * One collection object, or null when id or name is missing, or any field is the wrong type. A
	 * missing (or null) role reads as no role and a missing order as 0, so a site that leaves them out
	 * still groups the setup.
	 */
	static SetupCollection one(JsonElement e)
	{
		if (e == null || !e.isJsonObject())
		{
			return null;
		}
		JsonObject o = e.getAsJsonObject();
		JsonPrimitive id = primitive(o, "id");
		JsonPrimitive name = primitive(o, "name");
		JsonPrimitive role = primitive(o, "role");
		JsonPrimitive order = primitive(o, "order");
		if (id == null || !id.isNumber() || name == null || !name.isString() || (role != null && !role.isString())
			|| (order != null && !order.isNumber()) || wrongType(o, "role") || wrongType(o, "order"))
		{
			return null;
		}
		double idValue = id.getAsDouble();
		double orderValue = order != null ? order.getAsDouble() : 0d;
		if (idValue != Math.rint(idValue) || idValue <= 0 || idValue > Long.MAX_VALUE
			|| orderValue != Math.rint(orderValue) || Math.abs(orderValue) > Integer.MAX_VALUE)
		{
			return null;
		}
		String n = RemoteConfig.clean(name.getAsString(), 80).trim();
		if (n.isEmpty())
		{
			return null;
		}
		String r = role != null ? RemoteConfig.clean(role.getAsString(), 40).trim() : "";
		return new SetupCollection(id.getAsLong(), n, r, order != null ? order.getAsInt() : 0);
	}

	/** A field that is there, not null, and not a plain value (an object or an array). */
	private static boolean wrongType(JsonObject o, String key)
	{
		JsonElement e = o.get(key);
		return e != null && !e.isJsonNull() && !e.isJsonPrimitive();
	}

	private static JsonPrimitive primitive(JsonObject o, String key)
	{
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsJsonPrimitive() : null;
	}
}
