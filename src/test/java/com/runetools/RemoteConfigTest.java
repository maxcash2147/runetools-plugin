package com.runetools;

import org.junit.Assert;
import org.junit.Test;

/** The site's settings can only make the plugin do less, and nothing it sends can break or redirect it. */
public class RemoteConfigTest
{
	@Test
	public void defaultsAreThePluginsOwnBehaviour()
	{
		RemoteConfig d = RemoteConfig.defaults();
		for (String f : RemoteConfig.FEATURES)
		{
			Assert.assertTrue(f, d.on(f));
		}
		Assert.assertEquals(20_000L, d.limitMs("sync_s"));
		Assert.assertEquals(30_000L, d.limitMs("poll_min_s"));
		Assert.assertEquals(300_000L, d.limitMs("poll_max_s"));
		Assert.assertEquals(10_000L, d.limitMs("live_gap_s"));
		Assert.assertEquals(1_800_000L, d.limitMs("config_s"));
		Assert.assertEquals("/api/plugin/trades", d.path("trades"));
		Assert.assertFalse(d.deleteViaPost());
		Assert.assertNull(d.message());
		Assert.assertFalse(d.tooOld(PluginVersion.VERSION));
	}

	@Test
	public void anythingButAJsonObjectIsIgnored()
	{
		Assert.assertNull(RemoteConfig.parse(null));
		Assert.assertNull(RemoteConfig.parse(""));
		Assert.assertNull(RemoteConfig.parse("<html>404 Not Found</html>"));
		Assert.assertNull(RemoteConfig.parse("[1,2,3]"));
		Assert.assertNull(RemoteConfig.parse("\"features\""));
		Assert.assertNull(RemoteConfig.parse("{\"features\": "));
	}

	@Test
	public void featuresCanOnlyBeSwitchedOff()
	{
		RemoteConfig c = RemoteConfig.parse("{\"features\": {\"trades\": false, \"state\": true, \"bank\": \"false\", "
			+ "\"ca\": 0, \"live\": null, \"made_up\": false}}");
		Assert.assertNotNull(c);
		Assert.assertFalse(c.on("trades"));
		Assert.assertTrue(c.on("state"));
		Assert.assertTrue("a string is not a boolean", c.on("bank"));
		Assert.assertTrue("a number is not a boolean", c.on("ca"));
		Assert.assertTrue(c.on("live"));
		Assert.assertTrue(c.on("setups_write"));
	}

	@Test
	public void limitsCanOnlySlowThePluginDown()
	{
		RemoteConfig c = RemoteConfig.parse("{\"limits\": {\"sync_s\": 5, \"poll_min_s\": 120, "
			+ "\"poll_max_s\": 999999999, \"live_gap_s\": \"60\", \"config_s\": -1, \"backoff_max_s\": 1e308}}");
		Assert.assertNotNull(c);
		Assert.assertEquals("shorter than the built-in pace is ignored", 20_000L, c.limitMs("sync_s"));
		Assert.assertEquals(120_000L, c.limitMs("poll_min_s"));
		Assert.assertEquals("capped at a day", 86_400_000L, c.limitMs("poll_max_s"));
		Assert.assertEquals("not a number", 10_000L, c.limitMs("live_gap_s"));
		Assert.assertEquals(1_800_000L, c.limitMs("config_s"));
		Assert.assertEquals(86_400_000L, c.limitMs("backoff_max_s"));
	}

	@Test
	public void clampKeepsBetweenDefaultAndCeiling()
	{
		Assert.assertEquals(10L, RemoteConfig.clampMillis(1L, 10L, 100L));
		Assert.assertEquals(50L, RemoteConfig.clampMillis(50L, 10L, 100L));
		Assert.assertEquals(100L, RemoteConfig.clampMillis(5000L, 10L, 100L));
	}

	@Test
	public void pathsStayUnderThePluginApi()
	{
		Assert.assertTrue(RemoteConfig.validPath("/api/plugin/trades"));
		Assert.assertTrue(RemoteConfig.validPath("/api/plugin/v2/trades"));
		Assert.assertTrue(RemoteConfig.validPath("/api/plugin/trades_v2.json"));
		Assert.assertFalse(RemoteConfig.validPath(null));
		Assert.assertFalse(RemoteConfig.validPath(""));
		Assert.assertFalse(RemoteConfig.validPath("/api/plugin/"));
		Assert.assertFalse(RemoteConfig.validPath("/api/plugin/trades/"));
		Assert.assertFalse(RemoteConfig.validPath("/api/auth/keys"));
		Assert.assertFalse(RemoteConfig.validPath("/api/plugin/../auth/keys"));
		Assert.assertFalse(RemoteConfig.validPath("/api/plugin/..%2Fauth"));
		Assert.assertFalse(RemoteConfig.validPath("https://evil.example/api/plugin/trades"));
		Assert.assertFalse(RemoteConfig.validPath("//evil.example/api/plugin/trades"));
		Assert.assertFalse(RemoteConfig.validPath("/api/plugin//trades"));
		Assert.assertFalse(RemoteConfig.validPath("/api/plugin/trades?x=1"));
		Assert.assertFalse(RemoteConfig.validPath("/api/plugin/trades#x"));
		Assert.assertFalse(RemoteConfig.validPath("/api/plugin/tra des"));
		Assert.assertFalse(RemoteConfig.validPath("/api/plugin/trades\\x"));
		Assert.assertFalse(RemoteConfig.validPath("/api/plugin/@evil.example"));
		Assert.assertFalse(RemoteConfig.validPath("/API/plugin/trades"));
	}

	@Test
	public void aBadPathFallsBackToTheBuiltInOne()
	{
		RemoteConfig c = RemoteConfig.parse("{\"paths\": {\"trades\": \"/api/plugin/v2/trades\", "
			+ "\"state\": \"https://evil.example/x\", \"setups\": 5, \"made_up\": \"/api/plugin/x\"}}");
		Assert.assertNotNull(c);
		Assert.assertEquals("/api/plugin/v2/trades", c.path("trades"));
		Assert.assertEquals("/api/plugin/state", c.path("state"));
		Assert.assertEquals("/api/plugin/setups", c.path("setups"));
	}

	@Test
	public void deleteMethodIsDeleteUnlessPostIsAsked()
	{
		Assert.assertTrue(RemoteConfig.parse("{\"delete_method\": \"POST\"}").deleteViaPost());
		Assert.assertTrue(RemoteConfig.parse("{\"paths\": {\"delete_method\": \"post\"}}").deleteViaPost());
		Assert.assertFalse(RemoteConfig.parse("{\"delete_method\": \"DELETE\"}").deleteViaPost());
		Assert.assertFalse(RemoteConfig.parse("{\"delete_method\": \"PATCH\"}").deleteViaPost());
		Assert.assertFalse(RemoteConfig.parse("{\"delete_method\": true}").deleteViaPost());
	}

	@Test
	public void aNoticeIsPlainShortTextWithAnOptionalLinkOnTheSite()
	{
		StringBuilder longText = new StringBuilder();
		for (int i = 0; i < 300; i++)
		{
			longText.append('x');
		}
		RemoteConfig c = RemoteConfig.parse("{\"message\": {\"id\": \"m1\", \"level\": \"warn\", "
			+ "\"text\": \"Sync is slow today.\\u202e\", \"link\": \"/status\"}}");
		RemoteConfig.Message m = c.message();
		Assert.assertNotNull(m);
		Assert.assertEquals("m1", m.id);
		Assert.assertEquals("warn", m.level);
		Assert.assertEquals("Sync is slow today.", m.text);
		Assert.assertEquals("/status", m.link);

		RemoteConfig.Message capped = RemoteConfig.parse("{\"message\": {\"text\": \"" + longText + "\", "
			+ "\"level\": \"shout\", \"link\": \"https://evil.example\"}}").message();
		Assert.assertEquals(200, capped.text.length());
		Assert.assertEquals("info", capped.level);
		Assert.assertNull(capped.link);
		Assert.assertFalse(capped.id.isEmpty());

		Assert.assertNull(RemoteConfig.parse("{\"message\": {\"id\": \"m2\"}}").message());
		Assert.assertNull(RemoteConfig.parse("{\"message\": \"hello\"}").message());
	}

	@Test
	public void noticeLinksAreSitePathsOnly()
	{
		Assert.assertTrue(RemoteConfig.validLink("/status"));
		Assert.assertTrue(RemoteConfig.validLink("/news?id=4#top"));
		Assert.assertFalse(RemoteConfig.validLink("https://evil.example"));
		Assert.assertFalse(RemoteConfig.validLink("//evil.example"));
		Assert.assertFalse(RemoteConfig.validLink("javascript:alert(1)"));
		Assert.assertFalse(RemoteConfig.validLink("/a/../b"));
		Assert.assertFalse(RemoteConfig.validLink("/a\\b"));
		Assert.assertFalse(RemoteConfig.validLink("/a b"));
		Assert.assertFalse(RemoteConfig.validLink(""));
	}

	@Test
	public void minVersionPausesOlderBuildsOnly()
	{
		Assert.assertTrue(RemoteConfig.parse("{\"min_version\": \"99.0.0\"}").tooOld(PluginVersion.VERSION));
		Assert.assertFalse(RemoteConfig.parse("{\"min_version\": \"" + PluginVersion.VERSION + "\"}")
			.tooOld(PluginVersion.VERSION));
		Assert.assertFalse(RemoteConfig.parse("{\"min_version\": \"0.9\"}").tooOld(PluginVersion.VERSION));
		Assert.assertFalse("garbage is ignored", RemoteConfig.parse("{\"min_version\": \"latest\"}")
			.tooOld(PluginVersion.VERSION));
		Assert.assertFalse(RemoteConfig.parse("{\"min_version\": 2}").tooOld(PluginVersion.VERSION));
	}

	@Test
	public void theRawAnswerIsKeptForTheCache()
	{
		String raw = "{\"features\": {\"trades\": false}}";
		RemoteConfig c = RemoteConfig.parse(raw);
		Assert.assertEquals(raw, c.raw());
		Assert.assertFalse(RemoteConfig.parse(c.raw()).on("trades"));
	}
}
