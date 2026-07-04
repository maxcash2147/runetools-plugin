package com.runetools;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

/**
 * Side panel modelled on Inventory Setups: a list of setups (click to open) and a per-setup detail
 * view rendering Inventory, Rune pouch, Equipment, Spellbook, Additional filtered items and Notes
 * from real sprites — with per-item right-click "Toggle fuzzy" and in-game create/edit/delete.
 */
class RuneToolsPanel extends PluginPanel
{
	private static final String[] EQUIP_LAYOUT = {
		null, "head", null,
		"cape", "neck", "ammo",
		"weapon", "body", "shield",
		null, "legs", null,
		"hands", "feet", "ring",
	};
	private static final String[] SPELLBOOK_NAMES = {"Standard", "Ancient", "Lunar", "Arceuus"};

	private final JLabel status = new JLabel("Not connected");
	private final JLabel linked = new JLabel(" ");
	private final JLabel pending = new JLabel("0 unsynced trades");
	private final JLabel lastSync = new JLabel("Last sync: never");

	private final ItemManager itemManager;
	private final Consumer<GearSetup> onFilter;
	private final Runnable onClearFilter;
	private final Consumer<String> onAddCurrent;
	private final Consumer<GearSetup> onUpdateFromGear;
	private final Consumer<GearSetup> onPush;
	private final LongConsumer onDelete;

	private final JLabel overviewHeader = new JLabel("Gear setups");
	private final JPanel overviewPanel = new JPanel();
	private final JButton addCurrent = new JButton("+ Add current setup");

	private final JPanel detailPanel = new JPanel();
	private final JLabel detailTitle = new JLabel();
	private final ItemGrid equipmentGrid = new ItemGrid("Equipment", 5, 3);
	private final ItemGrid inventoryGrid = new ItemGrid("Inventory", 7, 4);
	private final ItemGrid runePouchGrid = new ItemGrid("Rune pouch", 1, 4);
	private final ItemGrid spellbookGrid = new ItemGrid("Spellbook", 1, 1);
	private final JPanel additionalWrap = new JPanel(new BorderLayout());
	private final JPanel additionalGrid = new JPanel(new GridLayout(0, 4, 1, 1));
	private final JTextArea notesArea = new JTextArea(4, 0);

	private BufferedImage[] spellbookIcons;
	private GearSetup current;

	RuneToolsPanel(Runnable onTestConnection, Consumer<GearSetup> onFilter, Runnable onClearFilter,
				   ItemManager itemManager, Consumer<String> onAddCurrent,
				   Consumer<GearSetup> onUpdateFromGear, Consumer<GearSetup> onPush, LongConsumer onDelete)
	{
		this.onFilter = onFilter;
		this.onClearFilter = onClearFilter;
		this.itemManager = itemManager;
		this.onAddCurrent = onAddCurrent;
		this.onUpdateFromGear = onUpdateFromGear;
		this.onPush = onPush;
		this.onDelete = onDelete;

		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));

		JLabel title = new JLabel("RuneTools");
		title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
		title.setAlignmentX(Component.LEFT_ALIGNMENT);

		JLabel blurb = new JLabel("<html>Syncs your GE trades and account to gecharts.com, and your "
			+ "saved gear setups back into the game.</html>");
		blurb.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		blurb.setAlignmentX(Component.LEFT_ALIGNMENT);

		JPanel rows = new JPanel(new GridLayout(0, 1, 0, 4));
		rows.setBorder(BorderFactory.createEmptyBorder(10, 0, 6, 0));
		rows.add(status);
		rows.add(linked);
		rows.add(pending);
		rows.add(lastSync);
		rows.setAlignmentX(Component.LEFT_ALIGNMENT);

		JButton test = new JButton("Test connection");
		test.addActionListener(e -> onTestConnection.run());
		test.setAlignmentX(Component.LEFT_ALIGNMENT);

		overviewHeader.setFont(overviewHeader.getFont().deriveFont(Font.BOLD, 13f));
		overviewHeader.setBorder(BorderFactory.createEmptyBorder(14, 0, 4, 0));
		overviewHeader.setAlignmentX(Component.LEFT_ALIGNMENT);

		addCurrent.setAlignmentX(Component.LEFT_ALIGNMENT);
		addCurrent.addActionListener(e ->
		{
			String name = JOptionPane.showInputDialog(this, "Setup name:", "New setup", JOptionPane.PLAIN_MESSAGE);
			if (name != null && !name.trim().isEmpty())
			{
				onAddCurrent.accept(name.trim());
			}
		});

		overviewPanel.setLayout(new BoxLayout(overviewPanel, BoxLayout.Y_AXIS));
		overviewPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

		buildDetail();

		body.add(title);
		body.add(blurb);
		body.add(rows);
		body.add(test);
		body.add(overviewHeader);
		body.add(addCurrent);
		body.add(overviewPanel);
		body.add(detailPanel);
		add(body, BorderLayout.NORTH);

		linked.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		pending.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		lastSync.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		setSetups(java.util.Collections.emptyList());
		showOverview();
	}

	private void buildDetail()
	{
		detailPanel.setLayout(new BoxLayout(detailPanel, BoxLayout.Y_AXIS));
		detailPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		detailPanel.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));

		JPanel header = new JPanel(new BorderLayout(6, 0));
		header.setAlignmentX(Component.LEFT_ALIGNMENT);
		JButton back = new JButton("←");
		back.setMargin(new java.awt.Insets(0, 6, 0, 6));
		back.addActionListener(e -> showOverview());
		detailTitle.setFont(detailTitle.getFont().deriveFont(Font.BOLD, 13f));
		JPanel headBtns = new JPanel(new GridLayout(1, 0, 4, 0));
		JButton rename = new JButton("Rename");
		rename.addActionListener(e ->
		{
			if (current == null)
			{
				return;
			}
			String n = JOptionPane.showInputDialog(this, "Rename setup:", current.getName());
			if (n != null && !n.trim().isEmpty())
			{
				current.setName(n.trim());
				detailTitle.setText(n.trim());
				pushUpdate();
			}
		});
		JButton delete = new JButton("Delete");
		delete.addActionListener(e ->
		{
			if (current == null)
			{
				return;
			}
			if (JOptionPane.showConfirmDialog(this, "Delete \"" + current.getName() + "\"?", "Delete setup",
				JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION)
			{
				long id = current.getId();
				showOverview();
				onDelete.accept(id);
			}
		});
		headBtns.add(rename);
		headBtns.add(delete);
		header.add(back, BorderLayout.WEST);
		header.add(detailTitle, BorderLayout.CENTER);
		header.add(headBtns, BorderLayout.EAST);

		// Additional filtered items (dynamic grid)
		additionalWrap.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
		additionalWrap.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel addCap = new JLabel("Additional filtered items");
		addCap.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		additionalGrid.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JPanel addWrap = new JPanel(new BorderLayout());
		addWrap.add(additionalGrid, BorderLayout.WEST);
		additionalWrap.add(addCap, BorderLayout.NORTH);
		additionalWrap.add(addWrap, BorderLayout.CENTER);
		additionalWrap.setVisible(false);

		// Notes
		JPanel notesWrap = new JPanel(new BorderLayout());
		notesWrap.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
		notesWrap.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel notesCap = new JLabel("Notes");
		notesCap.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		notesArea.setLineWrap(true);
		notesArea.setWrapStyleWord(true);
		notesArea.addFocusListener(new FocusAdapter()
		{
			@Override
			public void focusLost(FocusEvent e)
			{
				if (current != null)
				{
					current.setNotes(notesArea.getText());
					pushUpdate();
				}
			}
		});
		notesWrap.add(notesCap, BorderLayout.NORTH);
		notesWrap.add(new JScrollPane(notesArea), BorderLayout.CENTER);

		// Spellbook popup (single slot)
		SlotLabel sb = spellbookGrid.slot(0);
		String[] menuNames = {"Standard", "Ancient", "Lunar", "Arceuus", "None"};
		for (int i = 0; i < menuNames.length; i++)
		{
			final int book = (i == 4) ? -1 : i;
			JMenuItem mi = new JMenuItem("Set " + menuNames[i]);
			mi.addActionListener(e ->
			{
				if (current != null)
				{
					current.setSpellbook(book);
					renderSpellbook();
					pushUpdate();
				}
			});
			sb.popup().add(mi);
		}

		JPanel buttons = new JPanel(new GridLayout(1, 0, 6, 0));
		buttons.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
		buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
		JButton filter = new JButton("Filter");
		filter.addActionListener(e -> { if (current != null) onFilter.accept(current); });
		JButton clear = new JButton("Clear");
		clear.addActionListener(e -> onClearFilter.run());
		JButton updateGear = new JButton("Update from gear");
		updateGear.setToolTipText("Overwrite this setup with your current equipment + inventory");
		updateGear.addActionListener(e -> { if (current != null) onUpdateFromGear.accept(current); });
		buttons.add(filter);
		buttons.add(clear);
		buttons.add(updateGear);

		for (Component c : new Component[]{equipmentGrid, inventoryGrid, runePouchGrid, spellbookGrid})
		{
			((JPanel) c).setAlignmentX(Component.LEFT_ALIGNMENT);
		}

		// IS order: Inventory, Rune pouch, Equipment, Spellbook, Additional, Notes, buttons
		detailPanel.add(header);
		detailPanel.add(inventoryGrid);
		detailPanel.add(runePouchGrid);
		detailPanel.add(equipmentGrid);
		detailPanel.add(spellbookGrid);
		detailPanel.add(additionalWrap);
		detailPanel.add(notesWrap);
		detailPanel.add(buttons);
	}

	void setSpellbookIcons(BufferedImage[] icons)
	{
		SwingUtilities.invokeLater(() ->
		{
			this.spellbookIcons = icons;
			if (current != null)
			{
				renderSpellbook();
			}
		});
	}

	void setSetups(List<GearSetup> setups)
	{
		SwingUtilities.invokeLater(() ->
		{
			overviewPanel.removeAll();
			if (setups == null || setups.isEmpty())
			{
				JLabel empty = new JLabel("No setups yet — add one here or on gecharts.com.");
				empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				empty.setAlignmentX(Component.LEFT_ALIGNMENT);
				overviewPanel.add(empty);
			}
			else
			{
				for (GearSetup s : setups)
				{
					overviewPanel.add(overviewRow(s));
				}
			}
			// if the open setup vanished from a sync, and its detail is showing, drop back to the list
			if (current != null && detailPanel.isVisible()
				&& (setups == null || setups.stream().noneMatch(x -> x.getId() == current.getId())))
			{
				showOverview();
			}
			else if (current != null && detailPanel.isVisible())
			{
				// refresh the open setup from the latest data (e.g. after a push re-pull)
				setups.stream().filter(x -> x.getId() == current.getId()).findFirst().ifPresent(this::openSetup);
			}
			overviewPanel.revalidate();
			overviewPanel.repaint();
		});
	}

	private JPanel overviewRow(GearSetup s)
	{
		JPanel row = new JPanel(new BorderLayout());
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 26));
		row.setBorder(BorderFactory.createEmptyBorder(3, 2, 3, 2));
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		JLabel name = new JLabel(s.getName() != null ? s.getName() : "Setup");
		JLabel chev = new JLabel("›");
		chev.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		row.add(name, BorderLayout.CENTER);
		row.add(chev, BorderLayout.EAST);
		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				openSetup(s);
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				row.setBackground(ColorScheme.DARK_GRAY_HOVER_COLOR);
				row.setOpaque(true);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				row.setOpaque(false);
				row.repaint();
			}
		});
		return row;
	}

	private void openSetup(GearSetup s)
	{
		current = s;
		detailTitle.setText(s.getName() != null ? s.getName() : "Setup");

		for (int i = 0; i < EQUIP_LAYOUT.length; i++)
		{
			SlotLabel slot = equipmentGrid.slot(i);
			if (slot == null)
			{
				continue;
			}
			String key = EQUIP_LAYOUT[i];
			Integer id = (key != null && s.getEquipment() != null) ? s.getEquipment().get(key) : null;
			if (id != null && id > 0)
			{
				slot.setItem(itemManager, id, 1);
				bindFuzzy(slot, id);
			}
			else
			{
				slot.clear();
				slot.popup().removeAll();
			}
		}

		fillGrid(inventoryGrid, s.getInventory(), 28);
		boolean hasPouch = s.getRunePouch() != null && !s.getRunePouch().isEmpty();
		runePouchGrid.setVisible(hasPouch);
		if (hasPouch)
		{
			fillGrid(runePouchGrid, s.getRunePouch(), 4);
		}
		renderSpellbook();
		fillAdditional(s.getAdditional());
		notesArea.setText(s.getNotes() == null ? "" : s.getNotes());

		showDetail();
	}

	private void fillGrid(ItemGrid grid, List<GearSetupItem> items, int cap)
	{
		grid.clearAll();
		if (items == null)
		{
			return;
		}
		for (int i = 0; i < items.size() && i < cap; i++)
		{
			SlotLabel slot = grid.slot(i);
			if (slot != null)
			{
				slot.setItem(itemManager, items.get(i).getItemId(), items.get(i).getQty());
				bindFuzzy(slot, items.get(i).getItemId());
			}
		}
	}

	private void fillAdditional(List<GearSetupItem> add)
	{
		additionalGrid.removeAll();
		boolean any = add != null && !add.isEmpty();
		additionalWrap.setVisible(any);
		if (any)
		{
			for (GearSetupItem it : add)
			{
				SlotLabel s = new SlotLabel(ColorScheme.DARKER_GRAY_COLOR);
				s.setItem(itemManager, it.getItemId(), it.getQty());
				bindFuzzy(s, it.getItemId());
				additionalGrid.add(s);
			}
		}
		additionalGrid.revalidate();
		additionalGrid.repaint();
	}

	private void renderSpellbook()
	{
		SlotLabel sb = spellbookGrid.slot(0);
		int book = current != null ? current.getSpellbook() : -1;
		if (book >= 0 && spellbookIcons != null && book < spellbookIcons.length)
		{
			sb.setImage(spellbookIcons[book], SPELLBOOK_NAMES[book] + " spellbook");
		}
		else
		{
			sb.setImage(null, book >= 0 && book < SPELLBOOK_NAMES.length ? SPELLBOOK_NAMES[book] : "No spellbook");
		}
	}

	private void bindFuzzy(SlotLabel slot, int itemId)
	{
		slot.popup().removeAll();
		if (itemId <= 0)
		{
			slot.setFuzzy(false);
			return;
		}
		slot.setFuzzy(current != null && current.isFuzzy(itemId));
		JMenuItem mi = new JMenuItem("Toggle fuzzy");
		mi.addActionListener(e ->
		{
			if (current == null)
			{
				return;
			}
			slot.setFuzzy(current.toggleFuzzy(itemId));
			pushUpdate();
		});
		slot.popup().add(mi);
	}

	private void pushUpdate()
	{
		if (current != null && current.getId() > 0)
		{
			onPush.accept(current);
		}
	}

	private void showOverview()
	{
		current = null;
		overviewHeader.setVisible(true);
		overviewPanel.setVisible(true);
		addCurrent.setVisible(true);
		detailPanel.setVisible(false);
		revalidate();
		repaint();
	}

	private void showDetail()
	{
		overviewHeader.setVisible(false);
		overviewPanel.setVisible(false);
		addCurrent.setVisible(false);
		detailPanel.setVisible(true);
		revalidate();
		repaint();
	}

	void setStatus(String text, boolean ok)
	{
		SwingUtilities.invokeLater(() ->
		{
			status.setText(text);
			status.setForeground(ok ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.PROGRESS_ERROR_COLOR);
		});
	}

	void setLinked(String rsnList)
	{
		SwingUtilities.invokeLater(() -> linked.setText(
			rsnList == null || rsnList.isEmpty() ? " " : "Linked: " + rsnList));
	}

	void setPending(int n)
	{
		SwingUtilities.invokeLater(() -> pending.setText(n + " unsynced trade" + (n == 1 ? "" : "s")));
	}

	void setLastSync(String text)
	{
		SwingUtilities.invokeLater(() -> lastSync.setText("Last sync: " + text));
	}
}
