package com.runetools;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridLayout;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;

/** A captioned grid of {@link SlotLabel}s — Equipment (5x3), Inventory (7x4), Rune pouch (1x4). */
final class ItemGrid extends JPanel
{
	private final SlotLabel[] slots;

	ItemGrid(String caption, int rows, int cols)
	{
		super(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
		setAlignmentX(Component.LEFT_ALIGNMENT);

		JLabel cap = new JLabel(caption);
		cap.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		JPanel grid = new JPanel(new GridLayout(rows, cols, 1, 1));
		grid.setBackground(ColorScheme.DARK_GRAY_COLOR);
		slots = new SlotLabel[rows * cols];
		for (int i = 0; i < slots.length; i++)
		{
			slots[i] = new SlotLabel(ColorScheme.DARKER_GRAY_COLOR);
			grid.add(slots[i]);
		}

		JPanel gridWrap = new JPanel(new BorderLayout());
		gridWrap.add(grid, BorderLayout.WEST);   // keep slots their natural size, left-aligned

		add(cap, BorderLayout.NORTH);
		add(gridWrap, BorderLayout.CENTER);
	}

	SlotLabel slot(int i)
	{
		return (i >= 0 && i < slots.length) ? slots[i] : null;
	}

	void clearAll()
	{
		for (SlotLabel s : slots)
		{
			s.clear();
		}
	}
}
