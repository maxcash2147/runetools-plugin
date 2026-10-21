package com.runetools;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.function.IntUnaryOperator;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

public class BankOrganizerTest
{
	// A placeholder (here 14000 + id) names the item it holds a place for, like ItemManager.canonicalize.
	private static final IntUnaryOperator CANON = id -> id > 14000 ? id - 14000 : id;
	private static final int FILLER = ItemID.BANK_FILLER;

	@Test
	public void slicesTheBankIntoItsNumberedTabsThenTheMainTab()
	{
		// Tab 1 holds 3 items, tab 2 is empty, tab 3 holds 2, everything after is the main tab.
		int[] bank = {4151, FILLER, 14995, 11802, 11804, 1, 2, FILLER};
		int[] counts = {3, 0, 2, 0, 0, 0, 0, 0, 0};
		List<int[]> tabs = BankOrganizer.nativeTabs(bank, counts, CANON);
		assertEquals(10, tabs.size());
		assertArrayEquals(new int[]{4151, -1, 995}, tabs.get(0));       // filler keeps its gap, placeholder is its item
		assertArrayEquals(new int[0], tabs.get(1));
		assertArrayEquals(new int[]{11802, 11804}, tabs.get(2));
		assertArrayEquals(new int[]{1, 2, -1}, tabs.get(9));
	}

	@Test
	public void varbitsCountingPastTheBankAreCappedAtWhatIsThere()
	{
		List<int[]> tabs = BankOrganizer.nativeTabs(new int[]{5, 6}, new int[]{4, 3}, CANON);
		assertArrayEquals(new int[]{5, 6}, tabs.get(0));
		assertArrayEquals(new int[0], tabs.get(1));
		assertArrayEquals(new int[0], tabs.get(2));
	}

	@Test
	public void miscHoldsWhatTheLayoutDoesNotPlaceOnceInBankOrder()
	{
		List<Integer> bank = Arrays.asList(995, 4151, FILLER, 14385, 385, 0, 2434, 4151, -1);
		List<Integer> misc = BankOrganizer.unplaced(bank, new HashSet<>(Collections.singletonList(4151)), CANON);
		assertEquals(Arrays.asList(995, 385, 2434), misc);
	}
}
