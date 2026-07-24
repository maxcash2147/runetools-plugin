package com.runetools;

import java.awt.GridLayout;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;

/**
 * A plain grid of {@link SlotLabel}s (e.g. Inventory 7x4, Rune pouch 1x4). The panel wraps it with a
 * centred caption; this class is just the fixed slot matrix. The 1px gaps show the darker grid
 * background, so each slot reads as its own box - the way Inventory Setups looks.
 */
final class ItemGrid extends JPanel
{
	private final SlotLabel[] slots;

	ItemGrid(int rows, int cols)
	{
		super(new GridLayout(rows, cols, 1, 1));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		slots = new SlotLabel[rows * cols];
		for (int i = 0; i < slots.length; i++)
		{
			slots[i] = new SlotLabel(ColorScheme.DARKER_GRAY_COLOR);
			add(slots[i]);
		}
	}

	SlotLabel slot(int i)
	{
		return (i >= 0 && i < slots.length) ? slots[i] : null;
	}

	int count()
	{
		return slots.length;
	}

	void clearAll()
	{
		for (SlotLabel s : slots)
		{
			s.clear();
		}
	}
}
