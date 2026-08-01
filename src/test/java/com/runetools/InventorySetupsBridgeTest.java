package com.runetools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

/**
 * Verifies {@link InventorySetupsBridge} against the RuneLite "Inventory Setups" Export/Import STRING
 * format (github.com/dillydill123/inventory-setups): a plain-JSON {@code InventorySetupPortable}
 * = {@code {"setup": InventorySetupSerializable, "layout": int[]}}, item elements
 * {@code {id, q?, f?, sc?}}, empty slots as {@code null} list elements, equipment in
 * EquipmentInventorySlot index order (0 head..13 ammo), spellbook key {@code sb}.
 */
public class InventorySetupsBridgeTest
{
	private static GearSetup sampleSetup()
	{
		GearSetup s = new GearSetup();
		s.setName("Vorkath Melee");
		s.setSource("bis");
		s.setStyle("melee");
		s.setSpellbook(1); // ancient
		s.setNotes("dhcb switch");

		Map<String, Integer> eq = new LinkedHashMap<>();
		eq.put("head", 10828);    // Neitiznot helm
		eq.put("cape", 21295);    // Infernal cape
		eq.put("neck", 6585);     // Amulet of fury
		eq.put("weapon", 11802);  // Armadyl godsword
		eq.put("body", 21021);    // Bandos chestplate (example id)
		eq.put("shield", 22322);  // Avernic defender
		eq.put("legs", 4585);     // Dragon platelegs (example id)
		eq.put("hands", 7462);    // Barrows gloves
		eq.put("feet", 13239);    // Primordial boots
		eq.put("ring", 12785);    // Ring of suffering (example id)
		eq.put("ammo", 11212);    // Dragon arrow (example id)
		s.setEquipment(eq);

		List<GearSetupItem> inv = new ArrayList<>();
		inv.add(new GearSetupItem(385, 8));      // shark x8
		inv.add(new GearSetupItem(0, 0));        // empty slot (positional)
		inv.add(new GearSetupItem(12695, 4));    // super combat x4
		s.setInventory(inv);

		List<GearSetupItem> rp = new ArrayList<>();
		rp.add(new GearSetupItem(560, 1000));    // death rune
		rp.add(new GearSetupItem(565, 1000));    // blood rune
		s.setRunePouch(rp);

		// one fuzzy item (the whip variant matching)
		s.toggleFuzzy(11802);
		return s;
	}

	@Test
	public void roundTripsThroughImportString()
	{
		GearSetup original = sampleSetup();
		String json = InventorySetupsBridge.toImportString(original);
		Assert.assertNotNull(json);

		// Structure sanity: it's a portable wrapper with a non-null layout and 14-slot eq / 28-slot inv.
		JsonObject portable = new JsonParser().parse(json).getAsJsonObject();
		Assert.assertTrue("must be a portable wrapper", portable.has("setup"));
		Assert.assertTrue("layout must be present (import requires non-null)", portable.has("layout"));
		Assert.assertTrue(portable.get("layout").isJsonArray());
		JsonObject setup = portable.getAsJsonObject("setup");
		Assert.assertEquals(14, setup.getAsJsonArray("eq").size());
		Assert.assertEquals(28, setup.getAsJsonArray("inv").size());
		Assert.assertEquals("Vorkath Melee", setup.get("name").getAsString());
		Assert.assertEquals(1, setup.get("sb").getAsInt());

		// weapon at EquipmentInventorySlot.WEAPON (index 3), shield at index 5, ammo at index 13
		JsonArray eq = setup.getAsJsonArray("eq");
		Assert.assertEquals(11802, eq.get(3).getAsJsonObject().get("id").getAsInt());
		Assert.assertEquals(22322, eq.get(5).getAsJsonObject().get("id").getAsInt());
		Assert.assertEquals(11212, eq.get(13).getAsJsonObject().get("id").getAsInt());
		// unused RuneTools slots (arms=6, hair=8, jaw=11) are null
		Assert.assertTrue(eq.get(6).isJsonNull());
		Assert.assertTrue(eq.get(8).isJsonNull());
		Assert.assertTrue(eq.get(11).isJsonNull());
		// fuzzy flag carried on the weapon
		Assert.assertTrue(eq.get(3).getAsJsonObject().get("f").getAsBoolean());

		// inv position 0 = shark x8, position 1 = null (empty), position 2 = super combat
		JsonArray inv = setup.getAsJsonArray("inv");
		Assert.assertEquals(385, inv.get(0).getAsJsonObject().get("id").getAsInt());
		Assert.assertEquals(8, inv.get(0).getAsJsonObject().get("q").getAsInt());
		Assert.assertTrue(inv.get(1).isJsonNull());
		Assert.assertEquals(12695, inv.get(2).getAsJsonObject().get("id").getAsInt());
		for (int i = 3; i < 28; i++)
		{
			Assert.assertTrue("trailing inv slots empty", inv.get(i).isJsonNull());
		}

		// rune pouch present with two runes
		JsonArray rp = setup.getAsJsonArray("rp");
		Assert.assertEquals(2, rp.size());
		Assert.assertEquals(560, rp.get(0).getAsJsonObject().get("id").getAsInt());
		Assert.assertEquals(1000, rp.get(0).getAsJsonObject().get("q").getAsInt());

		// ---- reverse ----
		GearSetup back = InventorySetupsBridge.fromExportString(json);
		Assert.assertNotNull(back);
		Assert.assertEquals("Vorkath Melee", back.getName());
		Assert.assertEquals(1, back.getSpellbook());
		Assert.assertEquals("dhcb switch", back.getNotes());

		// equipment ids/slots survive the full round trip
		for (Map.Entry<String, Integer> e : original.getEquipment().entrySet())
		{
			Assert.assertEquals("slot " + e.getKey(), e.getValue(), back.getEquipment().get(e.getKey()));
		}
		Assert.assertEquals(original.getEquipment().size(), back.getEquipment().size());

		// inventory ids/positions/qty survive (empty slot preserved between the two items)
		Assert.assertEquals(3, back.getInventory().size());
		Assert.assertEquals(385, back.getInventory().get(0).getItemId());
		Assert.assertEquals(8, back.getInventory().get(0).getQty());
		Assert.assertEquals(0, back.getInventory().get(1).getItemId());   // empty placeholder
		Assert.assertEquals(12695, back.getInventory().get(2).getItemId());

		// rune pouch survives
		Assert.assertEquals(2, back.getRunePouch().size());
		Assert.assertEquals(560, back.getRunePouch().get(0).getItemId());
		Assert.assertEquals(1000, back.getRunePouch().get(0).getQty());

		// fuzzy weapon carried back
		Assert.assertTrue(back.isFuzzy(11802));
	}

	@Test
	public void returnsNullOnGarbageWithoutThrowing()
	{
		Assert.assertNull(InventorySetupsBridge.fromExportString(null));
		Assert.assertNull(InventorySetupsBridge.fromExportString(""));
		Assert.assertNull(InventorySetupsBridge.fromExportString("   "));
		Assert.assertNull(InventorySetupsBridge.fromExportString("not json at all"));
		Assert.assertNull(InventorySetupsBridge.fromExportString("[1,2,3]"));           // array, not object
		Assert.assertNull(InventorySetupsBridge.fromExportString("42"));                // primitive
		Assert.assertNull(InventorySetupsBridge.fromExportString("{\"foo\":\"bar\"}")); // object w/o setup/eq/inv
		Assert.assertNull(InventorySetupsBridge.fromExportString("{\"setup\":"));       // truncated json
		// null input to toImportString is also tolerated
		Assert.assertNull(InventorySetupsBridge.toImportString(null));
	}

	/**
	 * A hand-written string in the EXACT shape Inventory Setups' "Export setup" emits: an
	 * {@code InventorySetupPortable} = {@code {setup, layout}}, where the serializable setup uses the
	 * short keys ({@code inv}/{@code eq}/{@code rp}/{@code name}/{@code sb}/{@code hc}), items are
	 * {@code {id,q,f}}, empty slots are {@code null} elements, and equipment is in
	 * EquipmentInventorySlot order. {@code hc} (highlight color) and {@code layout} are present as a
	 * real export would have them; the bridge must ignore them and still parse cleanly.
	 */
	@Test
	public void parsesRealShapedExportString()
	{
		String exported = "{"
			+ "\"setup\":{"
			+   "\"inv\":["
			+     "{\"id\":385,\"q\":6},null,{\"id\":2434},null,null,null,null,null,"
			+     "null,null,null,null,null,null,null,null,"
			+     "null,null,null,null,null,null,null,null,"
			+     "null,null,null,null"
			+   "],"
			+   "\"eq\":["
			+     "{\"id\":10828},{\"id\":21295},{\"id\":6585},{\"id\":11802,\"f\":true},"
			+     "{\"id\":21021},{\"id\":22322},null,{\"id\":4585},"
			+     "null,{\"id\":7462},{\"id\":13239},null,{\"id\":12785},{\"id\":11212}"
			+   "],"
			+   "\"rp\":[{\"id\":560,\"q\":1000},{\"id\":565,\"q\":500},{\"id\":566,\"q\":500}],"
			+   "\"name\":\"Real Export\","
			+   "\"notes\":\"pasted from IS\","
			+   "\"hc\":{\"value\":-65536,\"falpha\":0.0},"
			+   "\"sb\":2"
			+ "},"
			+ "\"layout\":[10828,-1,21295,-1,6585,-1]"
			+ "}";

		GearSetup s = InventorySetupsBridge.fromExportString(exported);
		Assert.assertNotNull(s);
		Assert.assertEquals("Real Export", s.getName());
		Assert.assertEquals("pasted from IS", s.getNotes());
		Assert.assertEquals(2, s.getSpellbook());     // lunar
		Assert.assertEquals("imported", s.getSource());

		// equipment mapped from index order to RuneTools slot names
		Assert.assertEquals(Integer.valueOf(10828), s.getEquipment().get("head"));
		Assert.assertEquals(Integer.valueOf(21295), s.getEquipment().get("cape"));
		Assert.assertEquals(Integer.valueOf(6585), s.getEquipment().get("neck"));
		Assert.assertEquals(Integer.valueOf(11802), s.getEquipment().get("weapon"));
		Assert.assertEquals(Integer.valueOf(21021), s.getEquipment().get("body"));
		Assert.assertEquals(Integer.valueOf(22322), s.getEquipment().get("shield"));
		Assert.assertEquals(Integer.valueOf(4585), s.getEquipment().get("legs"));
		Assert.assertEquals(Integer.valueOf(7462), s.getEquipment().get("hands"));
		Assert.assertEquals(Integer.valueOf(13239), s.getEquipment().get("feet"));
		Assert.assertEquals(Integer.valueOf(12785), s.getEquipment().get("ring"));
		Assert.assertEquals(Integer.valueOf(11212), s.getEquipment().get("ammo"));
		// weapon flagged fuzzy in the export
		Assert.assertTrue(s.isFuzzy(11802));
		// has both weapon and shield -> not two-handed
		Assert.assertFalse(s.isTwoHanded());

		// inventory positions preserved: shark(0), empty(1), pot(2)
		Assert.assertEquals(3, s.getInventory().size());
		Assert.assertEquals(385, s.getInventory().get(0).getItemId());
		Assert.assertEquals(6, s.getInventory().get(0).getQty());
		Assert.assertEquals(0, s.getInventory().get(1).getItemId());
		Assert.assertEquals(2434, s.getInventory().get(2).getItemId());

		// rune pouch, three runes with quantities
		Assert.assertEquals(3, s.getRunePouch().size());
		Assert.assertEquals(560, s.getRunePouch().get(0).getItemId());
		Assert.assertEquals(1000, s.getRunePouch().get(0).getQty());
		Assert.assertEquals(566, s.getRunePouch().get(2).getItemId());
		Assert.assertEquals(500, s.getRunePouch().get(2).getQty());
	}

	/** A two-handed export (weapon present, shield slot null) is detected as two-handed. */
	@Test
	public void detectsTwoHandedFromEmptyShieldSlot()
	{
		String exported = "{\"setup\":{"
			+ "\"inv\":[],"
			+ "\"eq\":[null,null,null,{\"id\":11802},null,null,null,null,null,null,null,null,null,null],"
			+ "\"name\":\"2h\""
			+ "},\"layout\":[]}";
		GearSetup s = InventorySetupsBridge.fromExportString(exported);
		Assert.assertNotNull(s);
		Assert.assertTrue(s.isTwoHanded());
		Assert.assertEquals(Integer.valueOf(11802), s.getEquipment().get("weapon"));
		Assert.assertFalse(s.getEquipment().containsKey("shield"));
	}
}
