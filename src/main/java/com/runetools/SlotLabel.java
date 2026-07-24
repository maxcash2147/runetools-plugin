package com.runetools;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.FontManager;

/**
 * One inventory/equipment slot in the setups panel: an item (or spellbook) sprite, a NE fuzzy "*"
 * indicator, and a right-click menu the panel fills. Mirrors Inventory Setups' slot - the popup
 * mouse listener is attached to BOTH the panel and the image label, otherwise the image swallows
 * the right-click and no menu ever appears (the bug in the first cut).
 */
final class SlotLabel extends JPanel
{
	static final int W = 46;
	static final int H = 42;

	private final JLabel image = new JLabel();
	private final JLabel fuzzyStar = new JLabel();
	private final JPopupMenu menu = new JPopupMenu();
	private int itemId = -1;

	SlotLabel(Color bg)
	{
		setLayout(new GridBagLayout());
		setPreferredSize(new Dimension(W, H));
		setMinimumSize(new Dimension(W, H));
		setMaximumSize(new Dimension(W, H));   // else a BoxLayout parent (e.g. Spellbook) stretches it wider
		setBackground(bg);

		fuzzyStar.setFont(FontManager.getRunescapeSmallFont());
		fuzzyStar.setForeground(Color.YELLOW);
		image.setHorizontalAlignment(JLabel.CENTER);

		add(image);
		add(fuzzyStar, new GridBagConstraints(0, 0, 1, 1, 1, 1,
			GridBagConstraints.NORTHEAST, GridBagConstraints.NONE, new Insets(0, 0, 0, 0), 0, 0));

		MouseAdapter popup = new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				maybeShow(e);
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				maybeShow(e);
			}

			private void maybeShow(MouseEvent e)
			{
				if (e.isPopupTrigger() && menu.getComponentCount() > 0)
				{
					menu.show(e.getComponent(), e.getX(), e.getY());
				}
			}
		};
		addMouseListener(popup);
		image.addMouseListener(popup);
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

	JPopupMenu menu()
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
