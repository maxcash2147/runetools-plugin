package com.runetools;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;
import org.junit.Assert;
import org.junit.Test;

public class PluginVersionTest
{
	@Test
	public void versionMatchesThePluginProperties() throws Exception
	{
		Properties p = new Properties();
		try (Reader r = Files.newBufferedReader(Paths.get("runelite-plugin.properties"), StandardCharsets.UTF_8))
		{
			p.load(r);
		}
		Assert.assertEquals("PluginVersion.VERSION must equal runelite-plugin.properties' version",
			p.getProperty("version"), PluginVersion.VERSION);
	}

	@Test
	public void apiLevelIsPositive()
	{
		Assert.assertTrue(PluginVersion.API_LEVEL >= 1);
	}

	@Test
	public void versionsCompareNumerically()
	{
		Assert.assertEquals(0, PluginVersion.compare("1.0.0", "1.0.0"));
		Assert.assertEquals(0, PluginVersion.compare("1.0", "1.0.0"));
		Assert.assertTrue(PluginVersion.compare("1.0.9", "1.0.10") < 0);
		Assert.assertTrue(PluginVersion.compare("1.2.0", "1.1.99") > 0);
		Assert.assertTrue(PluginVersion.compare("1.0.0", "2") < 0);
		Assert.assertEquals(0, PluginVersion.compare(null, "0"));
		Assert.assertEquals(0, PluginVersion.compare("x.y", "0.0"));
	}
}
