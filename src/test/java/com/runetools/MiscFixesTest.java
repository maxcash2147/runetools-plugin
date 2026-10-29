package com.runetools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

/** Smaller release fixes that are plain logic. */
public class MiscFixesTest
{
	@Test
	public void aSetupNameInATooltipIsPlainText()
	{
		String evil = "<html><img src=http://example.invalid/p.png>";
		String tip = RuneToolsPanel.plainTip(evil);
		Assert.assertEquals("<html>&lt;html&gt;&lt;img src=http://example.invalid/p.png&gt;</html>", tip);
		Assert.assertFalse(tip.contains("<img"));
		Assert.assertEquals("<html>Tank &amp; Spec</html>", RuneToolsPanel.plainTip("Tank & Spec"));
		Assert.assertNull(RuneToolsPanel.plainTip(null));
		Assert.assertNull(RuneToolsPanel.plainTip(""));
	}

	@Test
	public void theImportedLayoutCapCountsOnlyImports()
	{
		JsonArray rows = new JsonArray();
		for (int i = 0; i < 150; i++)
		{
			JsonObject own = new JsonObject();   // the player's own exports, oldest first
			own.addProperty("id", "own" + i);
			rows.add(own);
		}
		for (int i = 0; i < 3; i++)
		{
			JsonObject imp = new JsonObject();
			imp.addProperty("id", "imp" + i);
			imp.addProperty("source_code", "ABC" + i);
			rows.add(imp);
		}
		JsonObject legacy = new JsonObject();
		legacy.addProperty("id", "old");
		legacy.addProperty("imported", true);
		rows.add(legacy);
		JsonObject blank = new JsonObject();
		blank.addProperty("id", "blank");
		blank.addProperty("source_code", " ");
		rows.add(blank);

		List<JsonObject> got = RuneToolsPlugin.newestImported(rows, 100);
		Assert.assertEquals(4, got.size());
		Assert.assertEquals("imp0", got.get(0).get("id").getAsString());
		Assert.assertEquals("old", got.get(3).get("id").getAsString());

		List<JsonObject> capped = RuneToolsPlugin.newestImported(rows, 2);
		Assert.assertEquals("the newest two, oldest first", "imp2", capped.get(0).get("id").getAsString());
		Assert.assertEquals("old", capped.get(1).get("id").getAsString());
		Assert.assertTrue(RuneToolsPlugin.newestImported(null, 100).isEmpty());
	}

	@Test
	public void theBankIsLeftOutWhenAsked()
	{
		JsonArray bank = new JsonArray();
		bank.add(995);
		AccountSnapshot s = AccountSnapshot.empty().toBuilder().rsn("Zezima").accountHash("1").bank(bank).build();
		Assert.assertTrue(RuneToolsApiClient.stateBody(s, true).has("bank"));
		Assert.assertFalse(RuneToolsApiClient.stateBody(s, false).has("bank"));
	}

	@Test
	public void quantitiesAreWholeNumbersFromOne()
	{
		Assert.assertEquals(Integer.valueOf(1000), RuneToolsPanel.parseQty("1,000"));
		Assert.assertEquals(Integer.valueOf(5), RuneToolsPanel.parseQty(" 5 "));
		Assert.assertEquals(Integer.valueOf(Integer.MAX_VALUE), RuneToolsPanel.parseQty("2147483647"));
		Assert.assertNull(RuneToolsPanel.parseQty("0"));
		Assert.assertNull(RuneToolsPanel.parseQty("-3"));
		Assert.assertNull(RuneToolsPanel.parseQty("2147483648"));
		Assert.assertNull(RuneToolsPanel.parseQty("1.5"));
		Assert.assertNull(RuneToolsPanel.parseQty("ten"));
		Assert.assertNull(RuneToolsPanel.parseQty(null));
	}

	@Test
	public void wrappedLabelsUsePointsAndEscapeText()
	{
		String html = RuneToolsPanel.wrapped("<img src=x> & more", 180);
		Assert.assertTrue(html, html.contains("width:180pt"));
		Assert.assertFalse(html, html.contains("px"));
		Assert.assertTrue(html.contains("&lt;img src=x&gt; &amp; more"));
		Assert.assertEquals(" ", RuneToolsPanel.wrapped("  ", 180));
	}

	@Test
	public void theImportSummaryReportsWhatWasReallySaved()
	{
		Assert.assertEquals("Imported 1 setup.", SetupManager.importSummary(1, 1, 0, null));
		Assert.assertEquals("Imported 5 setups.", SetupManager.importSummary(5, 5, 0, null));
		Assert.assertEquals("Imported 2 of 5. 1 failed. Too many requests. Try again in a minute.",
			SetupManager.importSummary(5, 2, 1, "Too many requests. Try again in a minute."));
		Assert.assertEquals("Imported 0 of 3.", SetupManager.importSummary(3, 0, 0, null));
	}

	@Test
	public void setupCachesAreKeptPerAccount()
	{
		Assert.assertNotEquals(SetupManager.setupsKey("111"), SetupManager.setupsKey("222"));
		Assert.assertNotEquals(SetupManager.etagKey("111"), SetupManager.etagKey("222"));
		Assert.assertNotEquals(SetupManager.setupsKey("111"), SetupManager.etagKey("111"));
	}

	@Test
	public void debouncedWritesAreReadBackBeforeTheyLand()
	{
		Map<String, String> stored = new HashMap<>();
		List<String> writes = new ArrayList<>();
		DebouncedConfig d = new DebouncedConfig((k, v) ->
		{
			writes.add(k);
			if (v == null)
			{
				stored.remove(k);
			}
			else
			{
				stored.put(k, v);
			}
		}, stored::get, null, 0L);
		d.set("pending", "[1]");
		Assert.assertEquals("[1]", stored.get("pending"));
		d.set("pending", null);
		Assert.assertNull(d.get("pending"));
		Assert.assertEquals(2, writes.size());
	}

	@Test
	public void massExportIsAnArrayOfImportStrings()
	{
		GearSetup a = new GearSetup();
		a.setName("A");
		GearSetup b = new GearSetup();
		b.setName("B");
		List<GearSetup> list = new ArrayList<>();
		list.add(a);
		list.add(b);
		JsonArray arr = new JsonParser().parse(InventorySetupsBridge.toMassExport(list)).getAsJsonArray();
		Assert.assertEquals(2, arr.size());
		Assert.assertEquals("B", arr.get(1).getAsJsonObject().getAsJsonObject("setup").get("name").getAsString());
		Assert.assertTrue(arr.get(0).getAsJsonObject().has("layout"));
		// ...which is what this plugin's own import reads back, too.
		Assert.assertEquals(2, RuneToolsPanel.parseInventorySetups(InventorySetupsBridge.toMassExport(list), 300).size());
	}

	@Test
	public void combatAchievementsPastTheKnownVarpsAreUnreadable()
	{
		Assert.assertTrue(CombatAchievements.readable(0));
		Assert.assertTrue(CombatAchievements.readable(671));
		Assert.assertFalse(CombatAchievements.readable(672));
		Assert.assertFalse(CombatAchievements.readable(-1));
	}
}
