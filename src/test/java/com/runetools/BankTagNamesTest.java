package com.runetools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BankTagNamesTest
{
	@Test
	public void everyTagThisPluginMakesStartsWithTheRuneToolsPrefix()
	{
		assertEquals("runetools_", BankTagNames.PREFIX);
		assertTrue(BankFilterController.TAG_PREFIX.startsWith(BankTagNames.PREFIX));
		assertEquals("runetools_Gear", BankTagNames.of(BankCategorizer.Category.GEAR.label));
	}

	@Test
	public void onlyPrefixedTagsAreOursSoAPlayersOwnTagIsNeverTouched()
	{
		assertTrue(BankTagNames.ours("runetools_gear"));       // Bank Tags keeps tag names lower case
		assertTrue(BankTagNames.ours("RuneTools_All"));
		assertFalse(BankTagNames.ours("gear"));
		assertFalse(BankTagNames.ours("misc"));
		assertFalse(BankTagNames.ours("all"));
		assertFalse(BankTagNames.ours("my runetools_ tab"));
		assertFalse(BankTagNames.ours(""));
		assertFalse(BankTagNames.ours(null));
	}

	@Test
	public void theNameAPlayerReadsLeavesThePrefixOff()
	{
		assertEquals("barrows", BankTagNames.shown("runetools_barrows"));
		assertEquals("barrows", BankTagNames.shown("barrows"));
	}
}
