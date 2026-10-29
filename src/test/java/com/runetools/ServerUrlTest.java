package com.runetools;

import org.junit.Assert;
import org.junit.Test;

/** Old and hand-typed Server URL settings still reach the site; a broken one is refused, not thrown. */
public class ServerUrlTest
{
	private static String norm(String s)
	{
		return RuneToolsApiClient.normalizeServerUrl(s);
	}

	@Test
	public void blankIsTheDefault()
	{
		Assert.assertEquals("https://runetools.com", norm(null));
		Assert.assertEquals("https://runetools.com", norm("  "));
	}

	@Test
	public void theOldAddressesMoveToRuneTools()
	{
		Assert.assertEquals("https://runetools.com", norm("https://gecharts.com"));
		Assert.assertEquals("https://runetools.com", norm("https://www.gecharts.com/"));
		Assert.assertEquals("https://runetools.com", norm("http://gecharts.com"));
		Assert.assertEquals("https://runetools.com", norm("https://www.runetools.com"));
		Assert.assertEquals("https://runetools.com", norm("www.runetools.com"));
	}

	@Test
	public void aMissingSchemeIsAddedAndHttpIsUpgraded()
	{
		Assert.assertEquals("https://runetools.com", norm("runetools.com"));
		Assert.assertEquals("https://runetools.com", norm("https://runetools.com/"));
		Assert.assertEquals("https://localhost:5173", norm("http://localhost:5173"));
		Assert.assertEquals("https://localhost:5173", norm("localhost:5173"));
		Assert.assertEquals("https://staging.runetools.com", norm("staging.runetools.com"));
	}

	@Test
	public void anythingElseIsRefused()
	{
		Assert.assertNull(norm("javascript:alert(1)"));
		Assert.assertNull(norm("ftp://runetools.com"));
		Assert.assertNull(norm("https://"));
		Assert.assertNull(norm("https://run etools.com"));
		Assert.assertNull(norm("https://runetools.com/?x=1"));
		Assert.assertNull(norm("https://user@evil.example"));
		Assert.assertNull(norm("not a url at all"));
	}
}
