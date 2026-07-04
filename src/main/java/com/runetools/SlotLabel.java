package com.runetools;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.image.BufferedImage;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.FontManager;

/**
 * One bank slot in the setups panel: an item (or spellbook) sprite, a NE fuzzy "*" indicator, and
 * a right-click popup the panel fills with "Toggle fuzzy" (or spellbook choices). Mirrors
 * Inventory Setups' slot.
 */
final class SlotLabel extends JPanel
{
	static final int W = 42;
	static final int H = 38;

	private final JLabel image = new JLabel();
	private final JLabel fuzzyStar = new JLabel();
	private final JPopupMenu menu = new JPopupMenu();
	private int itemId = -1;

	SlotLabel(Color bg)
	{
		super(new GridBagLayout());
		setPreferredSize(new Dimension(W, H));
		setMinimumSize(new Dimension(W, H));
		setBackground(bg);
		fuzzyStar.setFont(FontManager.getRunescapeSmallFont());
		fuzzyStar.setForeground(Color.YELLOW);
		image.setHorizontalAlignment(JLabel.CENTER);
		add(image);
		add(fuzzyStar, new GridBagConstraints(0, 0, 1, 1, 1, 1,
			GridBagConstraints.NORTHEAST, GridBagConstraints.NONE, new Insets(0, 0, 0, 0), 0, 0));
		setComponentPopupMenu(menu);
	}

	void setItem(ItemManager itemManager, int id, int qty)
	{
		this.itemId = id;
		if (itemManager == null || id <= 0)
		{
			clear();
			return;
		}
		itemManager.getImage(id, qty, qty > 1).addTo(image);
		setToolTipText("Item " + id + (qty > 1 ? " x" + qty : ""));
	}

	/** Render a non-item sprite (e.g. a spellbook icon). */
	void setImage(BufferedImage img, String tip)
	{
		this.itemId = -1;
		image.setIcon(img == null ? null : new ImageIcon(img));
		setToolTipText(tip);
	}

	void setFuzzy(boolean f)
	{
		fuzzyStar.setText(f ? "*" : "");
	}

	int getItemId()
	{
		return itemId;
	}

	JPopupMenu popup()
	{
		return menu;
	}

	void clear()
	{
		image.setIcon(null);
		itemId = -1;
		setToolTipText(null);
		fuzzyStar.setText("");
	}
}
