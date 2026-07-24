package com.runetools;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.MatteBorder;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

/**
 * Side panel modelled on Inventory Setups: a list of setups (click to open) and a per-setup detail
 * view with the authentic OSRS equipment cross (blank corners, quiver slot above ammo), Inventory,
 * Rune pouch, Spellbook, Additional Filtered Items and Notes. Every slot right-clicks to Toggle
 * Fuzzy / Update from the live container / Update from the in-game Item Search / Remove.
 */
class RuneToolsPanel extends PluginPanel
{
	private static final Color BLANK = ColorScheme.DARK_GRAY_COLOR;    // corner/placeholder cells blend in
	private static final Color SLOT = ColorScheme.DARKER_GRAY_COLOR;   // real slots read as darker boxes
	private static final String[] SPELLBOOK_NAMES = {"Standard", "Ancient", "Lunar", "Arceuus"};
	private static final GearSetupItem EMPTY_ITEM = new GearSetupItem(0, 0);   // positional inventory placeholder

	// OSRS worn-equipment layout. "" = blank corner, "quiver" = the conditional Dizana's ammo slot.
	private static final String[][] EQUIP_GRID = {
		{"", "head", "quiver"},
		{"cape", "neck", "ammo"},
		{"weapon", "body", "shield"},
		{"", "legs", ""},
		{"hands", "feet", "ring"},
	};
	private static final Map<String, EquipmentInventorySlot> EQUIP_SLOT = new HashMap<>();

	static
	{
		EQUIP_SLOT.put("head", EquipmentInventorySlot.HEAD);
		EQUIP_SLOT.put("cape", EquipmentInventorySlot.CAPE);
		EQUIP_SLOT.put("neck", EquipmentInventorySlot.AMULET);
		EQUIP_SLOT.put("weapon", EquipmentInventorySlot.WEAPON);
		EQUIP_SLOT.put("body", EquipmentInventorySlot.BODY);
		EQUIP_SLOT.put("shield", EquipmentInventorySlot.SHIELD);
		EQUIP_SLOT.put("legs", EquipmentInventorySlot.LEGS);
		EQUIP_SLOT.put("hands", EquipmentInventorySlot.GLOVES);
		EQUIP_SLOT.put("feet", EquipmentInventorySlot.BOOTS);
		EQUIP_SLOT.put("ring", EquipmentInventorySlot.RING);
		EQUIP_SLOT.put("ammo", EquipmentInventorySlot.AMMO);
	}

	// Row action icons, drawn so we don't have to ship image assets.
	private static final ImageIcon FILTER_ON = new ImageIcon(funnel(new Color(0x4C, 0xC2, 0x6A)));
	private static final ImageIcon FILTER_ON_HOVER = new ImageIcon(funnel(new Color(0x74, 0xE0, 0x90)));
	private static final ImageIcon FILTER_OFF = new ImageIcon(funnel(new Color(0x86, 0x86, 0x86)));
	private static final ImageIcon FILTER_OFF_HOVER = new ImageIcon(funnel(new Color(0xC2, 0xC2, 0xC2)));
	private static final ImageIcon DELETE_ICON = new ImageIcon(cross(new Color(0x96, 0x96, 0x96)));
	private static final ImageIcon DELETE_HOVER = new ImageIcon(cross(new Color(0xD6, 0x45, 0x41)));

	private static BufferedImage funnel(Color color)
	{
		BufferedImage img = new BufferedImage(14, 14, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(color);
		g.fillPolygon(new int[]{2, 12, 8, 8, 6, 6}, new int[]{3, 3, 8, 12, 12, 8}, 6);
		g.dispose();
		return img;
	}

	private static BufferedImage cross(Color color)
	{
		BufferedImage img = new BufferedImage(14, 14, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(color);
		g.setStroke(new BasicStroke(2f));
		g.drawLine(4, 4, 10, 10);
		g.drawLine(10, 4, 4, 10);
		g.dispose();
		return img;
	}

	// status/linked carry server-supplied strings (display name, linked RSNs) - disable Swing's
	// auto-HTML so a "<html><img src=...>" value can't beacon the user's IP (see noHtml()).
	private final JLabel status = noHtml(new JLabel("Not connected"));
	private final JLabel linked = noHtml(new JLabel(" "));
	private final JLabel pending = new JLabel("0 unsynced trades");
	private final JLabel lastSync = new JLabel("Last sync: never");

	private final ItemManager itemManager;
	private final Consumer<GearSetup> onFilter;
	private final Runnable onClearFilter;
	private final Consumer<String> onAddCurrent;
	private final Consumer<GearSetup> onUpdateFromGear;
	private final Consumer<GearSetup> onPush;
	private final LongConsumer onDelete;
	private final SlotEditor editor;
	private final Runnable onOpenHistory;
	private final Runnable onOrganizeBank;
	private final Runnable onRemoveTabs;
	private final Consumer<String> onConvertTab;
	private final Runnable onExportTag;

	private final JPanel bankSection = new JPanel();
	// bankStatus echoes imported-layout names (server-supplied) - keep it plain-text only.
	private final JLabel bankStatus = noHtml(new JLabel(" "));

	private final JPanel caSection = new JPanel();
	private final JLabel caOverall = new JLabel("Waiting for a Combat Achievements read…");
	private final JLabel caNextTier = new JLabel(" ");
	private final JPanel caTiersPanel = new JPanel();
	private final JPanel caPlanPanel = new JPanel();

	private final JPanel collectionSection = new JPanel();
	private final JLabel clogOverall = new JLabel("Open your Collection Log in-game to sync it.");
	private final JProgressBar clogOverallBar = new JProgressBar();
	private final JPanel clogTabsPanel = new JPanel();
	private final JLabel clogHint = new JLabel(" ");   // static HTML hint (not server text)

	private final JLabel buyLimitsCap = new JLabel("Buy limits (4h window)");
	private final JPanel buyLimitsPanel = new JPanel();
	// Live reset labels + their reset times, ticked once a minute so the countdown moves between the
	// 20s server polls (the used/limit counts still come from the poll; only the "resets in" text ticks).
	private final List<BuyLimitReset> buyLimitResets = new ArrayList<>();
	private final Timer buyLimitTicker = new Timer(60_000, e -> tickBuyLimitResets());

	private final JLabel missingLabel = new JLabel(" ", JLabel.CENTER);
	private BiConsumer<GearSetup, Consumer<MissingResult>> missingComputer;

	private final JPanel tradesSection = new JPanel();
	private final JLabel sessionProfit = new JLabel("-");
	private final JLabel offerSummary = new JLabel("No active GE offers");
	private final JPanel recentTradesPanel = new JPanel();

	private final JPanel homeHeader = new JPanel();
	private final JPanel homeMenu = new JPanel();
	private final JPanel backBar = new JPanel();
	private final JLabel backTitle = new JLabel();
	private final JPanel overviewPanel = new JPanel();
	private final JButton addCurrent = new JButton("+ Add current setup");

	private final JPanel detailPanel = new JPanel(new GridBagLayout());
	// detailTitle shows the (server-supplied) setup name - plain-text only.
	private final JLabel detailTitle = noHtml(new JLabel());
	private final Map<String, SlotLabel> equipSlots = new LinkedHashMap<>();
	private SlotLabel quiverSlot;
	private boolean quiverActive;
	private final ItemGrid inventoryGrid = new ItemGrid(7, 4);
	private final ItemGrid runePouchGrid = new ItemGrid(1, 4);
	private JPanel runePouchSection;
	private final SlotLabel spellbookSlot = new SlotLabel(SLOT);
	private final JPanel additionalGrid = new JPanel(new GridLayout(0, 4, 1, 1));
	private final List<SlotLabel> additionalSlots = new ArrayList<>();
	private final JTextArea notesArea = new JTextArea(4, 0);

	private BufferedImage[] spellbookIcons;
	private GearSetup current;
	private long activeSetupId = -1;
	private List<GearSetup> lastSetups = Collections.emptyList();

	RuneToolsPanel(Runnable onTestConnection, Consumer<GearSetup> onFilter, Runnable onClearFilter,
				   ItemManager itemManager, Consumer<String> onAddCurrent,
				   Consumer<GearSetup> onUpdateFromGear, Consumer<GearSetup> onPush, LongConsumer onDelete,
				   SlotEditor editor, Runnable onOpenHistory, Runnable onOrganizeBank, Runnable onRemoveTabs,
				   Consumer<String> onConvertTab, Runnable onExportTag)
	{
		this.onFilter = onFilter;
		this.onClearFilter = onClearFilter;
		this.itemManager = itemManager;
		this.onAddCurrent = onAddCurrent;
		this.onUpdateFromGear = onUpdateFromGear;
		this.onPush = onPush;
		this.onDelete = onDelete;
		this.editor = editor;
		this.onOpenHistory = onOpenHistory;
		this.onOrganizeBank = onOrganizeBank;
		this.onRemoveTabs = onRemoveTabs;
		this.onConvertTab = onConvertTab;
		this.onExportTag = onExportTag;

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

		JPanel headerSep = new JPanel();
		headerSep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
		headerSep.setPreferredSize(new Dimension(10, 1));
		headerSep.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
		headerSep.setAlignmentX(Component.LEFT_ALIGNMENT);

		// Home header (title + connection status) - shown only on the home menu.
		homeHeader.setLayout(new BoxLayout(homeHeader, BoxLayout.Y_AXIS));
		homeHeader.setAlignmentX(Component.LEFT_ALIGNMENT);
		homeHeader.add(title);
		homeHeader.add(blurb);
		homeHeader.add(rows);
		homeHeader.add(test);
		homeHeader.add(Box.createVerticalStrut(12));
		homeHeader.add(headerSep);

		// Home menu - pick a section to open it (keeps the panel from being one long scroll).
		homeMenu.setLayout(new BoxLayout(homeMenu, BoxLayout.Y_AXIS));
		homeMenu.setAlignmentX(Component.LEFT_ALIGNMENT);
		homeMenu.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
		homeMenu.add(menuCard("Grand Exchange", "Session P/L, recent flips & open offers", this::showTrades));
		homeMenu.add(Box.createVerticalStrut(8));
		homeMenu.add(menuCard("Gear Setups", "Filter your bank to a saved loadout", this::showSetups));
		homeMenu.add(Box.createVerticalStrut(8));
		homeMenu.add(menuCard("Bank Organizer", "One-click sort your bank into category tabs", this::showBank));
		homeMenu.add(Box.createVerticalStrut(8));
		homeMenu.add(menuCard("Combat Achievements", "Tier progress, points & your plan", this::showCa));
		homeMenu.add(Box.createVerticalStrut(8));
		homeMenu.add(menuCard("Collection Log", "Sync a browsable replica to the site", this::showCollection));

		// Back bar for the sub-pages.
		backBar.setLayout(new BorderLayout(6, 0));
		backBar.setAlignmentX(Component.LEFT_ALIGNMENT);
		backBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
		backBar.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
		JButton homeBack = new JButton("←");
		homeBack.setMargin(new Insets(0, 6, 0, 6));
		homeBack.setToolTipText("Back to menu");
		homeBack.addActionListener(e -> showHome());
		backTitle.setFont(backTitle.getFont().deriveFont(Font.BOLD, 14f));
		backBar.add(homeBack, BorderLayout.WEST);
		backBar.add(backTitle, BorderLayout.CENTER);

		body.add(homeHeader);
		body.add(homeMenu);
		body.add(backBar);
		body.add(buildTradesSection());
		body.add(buildBankSection());
		body.add(buildCaSection());
		body.add(buildCollectionSection());
		body.add(addCurrent);
		body.add(Box.createVerticalStrut(6));
		body.add(overviewPanel);
		body.add(detailPanel);
		add(body, BorderLayout.NORTH);

		linked.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		pending.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		lastSync.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		setSetups(Collections.emptyList());
		showHome();
	}

	// -- detail view assembly ----------------------------------------------- //

	private void buildDetail()
	{
		// Fill the width of the panel so the centred sections have room to centre in.
		detailPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		detailPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
		detailPanel.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));

		// header row 1: back + title, given its own full width so the name isn't squeezed to "V..."
		JPanel titleBar = new JPanel(new BorderLayout(6, 0));
		JButton back = new JButton("←");
		back.setMargin(new Insets(0, 6, 0, 6));
		back.addActionListener(e -> showSetups());
		detailTitle.setFont(detailTitle.getFont().deriveFont(Font.BOLD, 14f));
		detailTitle.setHorizontalAlignment(JLabel.LEFT);
		titleBar.add(back, BorderLayout.WEST);
		titleBar.add(detailTitle, BorderLayout.CENTER);

		// header rows 2-3: two readable rows of actions - [Refresh | Filter] then [Rename | Delete]
		JButton refresh = new JButton("↻ Refresh");
		refresh.setToolTipText("<html>Update this setup to match what you're wearing and carrying "
			+ "<b>right now</b>: worn gear, inventory, rune pouch, quiver and spellbook. Your name, "
			+ "notes and fuzzy flags are kept.</html>");
		refresh.addActionListener(e ->
		{
			if (current == null)
			{
				return;
			}
			int ok = JOptionPane.showConfirmDialog(this,
				"Update this setup to your current worn gear, inventory,\nrune pouch, quiver and spellbook?",
				"Refresh setup from game", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
			if (ok == JOptionPane.OK_OPTION)
			{
				onUpdateFromGear.accept(current);
			}
		});
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
				detailTitle.setToolTipText(n.trim());
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
				showSetups();
				onDelete.accept(id);
			}
		});
		JButton filter = new JButton("Filter");
		filter.addActionListener(e -> { if (current != null) { onFilter.accept(current); activeSetupId = current.getId(); } });
		JPanel actionBtns = new JPanel(new GridLayout(1, 0, 4, 0));   // Refresh | Filter
		actionBtns.add(refresh);
		actionBtns.add(filter);
		JPanel manageBtns = new JPanel(new GridLayout(1, 0, 4, 0));   // Rename | Delete
		manageBtns.add(rename);
		manageBtns.add(delete);

		// wire the fixed grids' menus once (images/fuzzy get set per-setup on open)
		for (int i = 0; i < inventoryGrid.count(); i++)
		{
			final int idx = i;
			wireMenu(inventoryGrid.slot(i), "Inventory",
				target -> editor.readInvSlot(idx, target),
				id -> setInv(idx, id), () -> setInv(idx, -1));
		}
		for (int i = 0; i < runePouchGrid.count(); i++)
		{
			final int idx = i;
			wireMenu(runePouchGrid.slot(i), null, null,
				id -> setPouch(idx, id), () -> setPouch(idx, -1));
		}
		additionalGrid.setBackground(BLANK);

		// spellbook: a single slot with the book choices
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
			spellbookSlot.menu().add(mi);
		}

		// notes
		JPanel notesWrap = new JPanel(new BorderLayout());
		JLabel notesCap = new JLabel("Notes", JLabel.CENTER);
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
		JScrollPane notesScroll = new JScrollPane(notesArea,
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		// pin a real height so GridBag can't shrink it to a sliver; width is filled by the layout
		notesScroll.setPreferredSize(new Dimension(10, 90));
		notesScroll.setMinimumSize(new Dimension(10, 90));
		notesWrap.add(notesCap, BorderLayout.NORTH);
		notesWrap.add(notesScroll, BorderLayout.CENTER);

		// Clear (unfilter) stays at the bottom by the missing-items summary; Filter is up top
		JPanel clearRow = new JPanel(new GridLayout(1, 0, 6, 0));
		JButton clear = new JButton("Clear");
		clear.addActionListener(e -> { onClearFilter.run(); activeSetupId = -1; });
		clearRow.add(clear);

		runePouchSection = captioned("Rune Pouch", runePouchGrid);

		// stack the rows: full-width ones fill, grids centre
		GridBagConstraints c = new GridBagConstraints();
		c.gridx = 0;
		c.gridwidth = GridBagConstraints.REMAINDER;
		c.weightx = 1;
		c.gridy = 0;
		addRow(titleBar, c, true);
		addRow(actionBtns, c, true);
		addRow(manageBtns, c, true);
		addRow(captioned("Equipment", buildEquipment()), c, false);
		addRow(captioned("Inventory", inventoryGrid), c, false);
		addRow(runePouchSection, c, false);
		addRow(captioned("Spellbook", spellbookSlot), c, false);
		addRow(captioned("Additional Filtered Items", additionalGrid), c, false);
		addRow(notesWrap, c, true);
		addRow(clearRow, c, true);
		missingLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		addRow(missingLabel, c, true);
	}

	private void addRow(Component comp, GridBagConstraints c, boolean fill)
	{
		c.gridy++;
		c.fill = fill ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
		c.anchor = fill ? GridBagConstraints.NORTH : GridBagConstraints.CENTER;
		c.insets = new Insets(10, 0, 0, 0);
		detailPanel.add(comp, c);
	}

	/** A centred caption over a (centred) body - the shape of every Inventory Setups section. */
	private JPanel captioned(String title, JComponent body)
	{
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		JLabel cap = new JLabel(title);
		cap.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		cap.setAlignmentX(Component.CENTER_ALIGNMENT);
		body.setAlignmentX(Component.CENTER_ALIGNMENT);
		p.add(cap);
		p.add(Box.createVerticalStrut(3));
		p.add(body);
		return p;
	}

	private JPanel buildEquipment()
	{
		JPanel eq = new JPanel(new GridLayout(5, 3, 1, 1));
		eq.setBackground(BLANK);
		for (String[] row : EQUIP_GRID)
		{
			for (String key : row)
			{
				if (key.isEmpty())
				{
					eq.add(blankCell());
				}
				else if (key.equals("quiver"))
				{
					quiverSlot = new SlotLabel(BLANK);
					eq.add(quiverSlot);
				}
				else
				{
					final String k = key;
					SlotLabel s = new SlotLabel(SLOT);
					equipSlots.put(k, s);
					EquipmentInventorySlot es = EQUIP_SLOT.get(k);
					wireMenu(s, "Equipment",
						target -> editor.readEquipSlot(es.getSlotIdx(), target),
						id -> setEquip(k, id), () -> setEquip(k, -1));
					eq.add(s);
				}
			}
		}
		return eq;
	}

	private JPanel blankCell()
	{
		JPanel p = new JPanel();
		p.setBackground(BLANK);
		p.setPreferredSize(new Dimension(SlotLabel.W, SlotLabel.H));
		return p;
	}

	/**
	 * Build the standard right-click menu for a slot. {@code containerRead}==null omits the
	 * update-from-container item. Async results (item search / live-container reads) are guarded so a
	 * pick that arrives after the user has navigated to a different setup never writes into the wrong one.
	 */
	private void wireMenu(SlotLabel slot, String container, Consumer<IntConsumer> containerRead,
						  IntConsumer setter, Runnable remove)
	{
		JPopupMenu m = slot.menu();
		m.removeAll();
		JMenuItem fuzzy = new JMenuItem("Toggle Fuzzy");
		fuzzy.addActionListener(e ->
		{
			if (current != null && slot.getItemId() > 0)
			{
				slot.setFuzzy(current.toggleFuzzy(slot.getItemId()));
				pushUpdate();
			}
		});
		m.add(fuzzy);
		if (container != null && containerRead != null)
		{
			JMenuItem uc = new JMenuItem("Update Slot from " + container);
			uc.addActionListener(e ->
			{
				if (current != null)
				{
					containerRead.accept(guarded(setter));
				}
			});
			m.add(uc);
		}
		JMenuItem us = new JMenuItem("Update Slot from Search");
		us.addActionListener(e ->
		{
			if (current != null)
			{
				editor.searchItem("Set slot to", guarded(setter));
			}
		});
		m.add(us);
		JMenuItem rm = new JMenuItem("Remove Item from Slot");
		rm.addActionListener(e -> remove.run());
		m.add(rm);
	}

	/** Wrap a setter so a late async result only applies if the setup that started it is still open. */
	private IntConsumer guarded(IntConsumer setter)
	{
		final long forId = current != null ? current.getId() : Long.MIN_VALUE;
		return id ->
		{
			if (current != null && current.getId() == forId)
			{
				setter.accept(id);
			}
		};
	}

	// -- per-setup rendering ------------------------------------------------- //

	private void openSetup(GearSetup s)
	{
		current = s;
		String title = s.getName() != null ? s.getName() : "Setup";
		detailTitle.setText(title);
		detailTitle.setToolTipText(title);   // full name on hover when the row truncates it
		fillEquipment();
		fillInventory();
		boolean pouch = s.getRunePouch() != null && !s.getRunePouch().isEmpty();
		runePouchSection.setVisible(pouch);
		if (pouch)
		{
			fillPouch();
		}
		renderSpellbook();
		fillAdditional();
		refreshQuiver();
		// don't stomp text the user is mid-typing (a background poll can re-render the open setup)
		if (!notesArea.isFocusOwner())
		{
			notesArea.setText(s.getNotes() == null ? "" : s.getNotes());
		}
		refreshMissing(s);
		showDetail();
	}

	/** Kick off the bank-diff ("missing N items · cost") for the open setup; result is guarded. */
	private void refreshMissing(GearSetup s)
	{
		missingLabel.setToolTipText(null);
		if (missingComputer == null)
		{
			missingLabel.setText(" ");
			return;
		}
		missingLabel.setText("Checking your bank for missing items…");
		final long forId = s.getId();
		missingComputer.accept(s, result ->
		{
			if (current != null && current.getId() == forId)
			{
				missingLabel.setText(result.summary);
				missingLabel.setToolTipText(missingTooltip(result.items));
			}
		});
	}

	/** HTML breakdown of missing items (name, qty, price) for the missing-items row tooltip, or null. */
	private static String missingTooltip(List<MissingItem> items)
	{
		if (items == null || items.isEmpty())
		{
			return null;
		}
		StringBuilder sb = new StringBuilder("<html>");
		int shown = 0;
		for (MissingItem mi : items)
		{
			if (shown >= 40)
			{
				sb.append("+").append(items.size() - shown).append(" more");
				break;
			}
			sb.append(htmlEscape(mi.name));
			if (mi.qty > 1)
			{
				sb.append(" x").append(fmtGp(mi.qty));
			}
			sb.append(" - ").append(fmtGp(mi.price)).append(" gp<br>");
			shown++;
		}
		return sb.append("</html>").toString();
	}

	private void fillEquipment()
	{
		for (Map.Entry<String, SlotLabel> e : equipSlots.entrySet())
		{
			Integer id = (current != null && current.getEquipment() != null)
				? current.getEquipment().get(e.getKey()) : null;
			if (id != null && id > 0)
			{
				e.getValue().setItem(itemManager, id, 1);
				e.getValue().setFuzzy(current.isFuzzy(id));
			}
			else
			{
				e.getValue().clear();
			}
		}
	}

	private void fillInventory()
	{
		List<GearSetupItem> inv = current != null ? current.getInventory() : null;
		for (int i = 0; i < inventoryGrid.count(); i++)
		{
			SlotLabel s = inventoryGrid.slot(i);
			if (inv != null && i < inv.size())
			{
				s.setItem(itemManager, inv.get(i).getItemId(), inv.get(i).getQty());
				s.setFuzzy(current.isFuzzy(inv.get(i).getItemId()));
			}
			else
			{
				s.clear();
			}
		}
	}

	private void fillPouch()
	{
		List<GearSetupItem> r = current != null ? current.getRunePouch() : null;
		for (int i = 0; i < runePouchGrid.count(); i++)
		{
			SlotLabel s = runePouchGrid.slot(i);
			if (r != null && i < r.size())
			{
				s.setItem(itemManager, r.get(i).getItemId(), r.get(i).getQty());
				s.setFuzzy(current.isFuzzy(r.get(i).getItemId()));
			}
			else
			{
				s.clear();
			}
		}
	}

	private void fillAdditional()
	{
		List<GearSetupItem> add = current != null ? current.getAdditional() : null;
		int count = add != null ? add.size() : 0;
		// round up to a multiple of 4, always leaving a spare empty row (matches Inventory Setups)
		int total = (count / 4 + 1) * 4;
		while (additionalSlots.size() < total)
		{
			final int idx = additionalSlots.size();
			SlotLabel s = new SlotLabel(SLOT);
			wireMenu(s, null, null, id -> setAdditional(idx, id), () -> setAdditional(idx, -1));
			additionalSlots.add(s);
			additionalGrid.add(s);
		}
		while (additionalSlots.size() > total)
		{
			additionalGrid.remove(additionalSlots.remove(additionalSlots.size() - 1));
		}
		for (int i = 0; i < additionalSlots.size(); i++)
		{
			SlotLabel s = additionalSlots.get(i);
			if (add != null && i < count)
			{
				s.setItem(itemManager, add.get(i).getItemId(), add.get(i).getQty());
				s.setFuzzy(current.isFuzzy(add.get(i).getItemId()));
			}
			else
			{
				s.clear();
			}
		}
		additionalGrid.revalidate();
		additionalGrid.repaint();
	}

	private void renderSpellbook()
	{
		int book = current != null ? current.getSpellbook() : -1;
		if (book >= 0 && spellbookIcons != null && book < spellbookIcons.length)
		{
			spellbookSlot.setImage(spellbookIcons[book], SPELLBOOK_NAMES[book] + " spellbook");
		}
		else
		{
			spellbookSlot.setImage(null, book >= 0 && book < SPELLBOOK_NAMES.length
				? SPELLBOOK_NAMES[book] : "No spellbook");
		}
	}

	/** Show the extra ammo slot above AMMO when the setup carries a quiver (or the cape is one). */
	private void refreshQuiver()
	{
		if (current == null)
		{
			showQuiver(false);
			return;
		}
		boolean hasAmmo = current.getQuiver() != null && !current.getQuiver().isEmpty();
		if (hasAmmo)
		{
			showQuiver(true);
			return;
		}
		Integer cape = current.getEquipment() != null ? current.getEquipment().get("cape") : null;
		if (cape != null && cape > 0)
		{
			final long forSetup = current.getId();
			editor.isQuiver(cape, isQ ->
			{
				if (current != null && current.getId() == forSetup)
				{
					showQuiver(isQ);
				}
			});
		}
		else
		{
			showQuiver(false);
		}
	}

	private void showQuiver(boolean on)
	{
		quiverActive = on;
		if (on)
		{
			quiverSlot.setBackground(SLOT);
			List<GearSetupItem> q = current != null ? current.getQuiver() : null;
			if (q != null && !q.isEmpty())
			{
				quiverSlot.setItem(itemManager, q.get(0).getItemId(), q.get(0).getQty());
				quiverSlot.setFuzzy(current.isFuzzy(q.get(0).getItemId()));
			}
			else
			{
				quiverSlot.clear();
			}
			wireMenu(quiverSlot, null, null, id -> setQuiver(id), () -> setQuiver(-1));
		}
		else
		{
			quiverSlot.clear();
			quiverSlot.setBackground(BLANK);
			quiverSlot.menu().removeAll();
		}
		quiverSlot.revalidate();
		quiverSlot.repaint();
	}

	// -- edits (all run on the EDT; they mutate `current` then push) --------- //

	private void setEquip(String key, int id)
	{
		if (current == null)
		{
			return;
		}
		SlotLabel slot = equipSlots.get(key);
		if (id > 0)
		{
			current.getEquipment().put(key, id);
			if (slot != null)
			{
				slot.setItem(itemManager, id, 1);
				slot.setFuzzy(current.isFuzzy(id));
			}
		}
		else
		{
			current.getEquipment().remove(key);
			if (slot != null)
			{
				slot.clear();
			}
		}
		if ("cape".equals(key))
		{
			refreshQuiver();
		}
		pushUpdate();
	}

	private void setInv(int i, int id)
	{
		if (current == null)
		{
			return;
		}
		List<GearSetupItem> inv = current.getInventory();
		// positional: the item lands on the slot you clicked, padding earlier slots with empties
		while (inv.size() <= i)
		{
			inv.add(EMPTY_ITEM);
		}
		inv.set(i, id > 0 ? new GearSetupItem(id, 1) : EMPTY_ITEM);
		while (!inv.isEmpty() && inv.get(inv.size() - 1).getItemId() <= 0)
		{
			inv.remove(inv.size() - 1);   // trim trailing empties so we don't store dead slots
		}
		fillInventory();
		pushUpdate();
	}

	private void setPouch(int i, int id)
	{
		if (current == null)
		{
			return;
		}
		List<GearSetupItem> r = current.getRunePouch();
		if (r == null)
		{
			r = new ArrayList<>();
			current.setRunePouch(r);
		}
		if (id > 0)
		{
			GearSetupItem it = new GearSetupItem(id, 1);
			if (i < r.size())
			{
				r.set(i, it);
			}
			else
			{
				r.add(it);
			}
		}
		else if (i < r.size())
		{
			r.remove(i);
		}
		boolean pouch = !r.isEmpty();
		runePouchSection.setVisible(pouch);
		if (pouch)
		{
			fillPouch();
		}
		pushUpdate();
	}

	private void setAdditional(int i, int id)
	{
		if (current == null)
		{
			return;
		}
		List<GearSetupItem> add = current.getAdditional();
		if (add == null)
		{
			add = new ArrayList<>();
			current.setAdditional(add);
		}
		if (id > 0)
		{
			GearSetupItem it = new GearSetupItem(id, 1);
			if (i < add.size())
			{
				add.set(i, it);
			}
			else
			{
				add.add(it);
			}
		}
		else if (i < add.size())
		{
			add.remove(i);
		}
		fillAdditional();
		pushUpdate();
	}

	private void setQuiver(int id)
	{
		if (current == null)
		{
			return;
		}
		List<GearSetupItem> q = current.getQuiver();
		if (q == null)
		{
			q = new ArrayList<>();
			current.setQuiver(q);
		}
		q.clear();
		if (id > 0)
		{
			q.add(new GearSetupItem(id, 1));
		}
		refreshQuiver();
		pushUpdate();
	}

	private void pushUpdate()
	{
		if (current != null && current.getId() > 0)
		{
			onPush.accept(current);
		}
	}

	// -- overview list ------------------------------------------------------- //

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
			lastSetups = setups != null ? setups : Collections.emptyList();
			if (activeSetupId != -1 && lastSetups.stream().noneMatch(x -> x.getId() == activeSetupId))
			{
				activeSetupId = -1;   // the active setup was deleted elsewhere
			}
			renderOverview();
			// if the open setup vanished from a sync, drop back to the list; else refresh it in place
			if (current != null && detailPanel.isVisible()
				&& lastSetups.stream().noneMatch(x -> x.getId() == current.getId()))
			{
				showSetups();
			}
			else if (current != null && detailPanel.isVisible())
			{
				lastSetups.stream().filter(x -> x.getId() == current.getId()).findFirst().ifPresent(this::openSetup);
			}
		});
	}

	private void renderOverview()
	{
		overviewPanel.removeAll();
		if (lastSetups.isEmpty())
		{
			JLabel empty = new JLabel("<html>No setups yet - press <b>+ Add current setup</b> or build one on gecharts.com.</html>");
			empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			empty.setAlignmentX(Component.LEFT_ALIGNMENT);
			overviewPanel.add(empty);
		}
		else
		{
			for (GearSetup s : lastSetups)
			{
				overviewPanel.add(overviewRow(s));
				overviewPanel.add(Box.createVerticalStrut(4));
			}
		}
		overviewPanel.revalidate();
		overviewPanel.repaint();
	}

	private JPanel overviewRow(GearSetup s)
	{
		boolean active = s.getId() == activeSetupId;
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setOpaque(true);
		row.setBackground(active ? ColorScheme.DARKER_GRAY_HOVER_COLOR : ColorScheme.DARKER_GRAY_COLOR);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
		row.setBorder(BorderFactory.createCompoundBorder(
			new MatteBorder(0, active ? 3 : 0, 0, 0, ColorScheme.BRAND_ORANGE),
			BorderFactory.createEmptyBorder(7, active ? 6 : 9, 7, 6)));

		JLabel name = noHtml(new JLabel(s.getName() != null ? s.getName() : "Setup"));
		name.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		name.setToolTipText("Open this setup");
		name.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				openSetup(s);
			}
		});

		JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		actions.setOpaque(false);
		actions.add(iconLabel(active ? FILTER_ON : FILTER_OFF, active ? FILTER_ON_HOVER : FILTER_OFF_HOVER,
			active ? "Filtering your bank to this setup - click to stop" : "Filter your bank to this setup",
			() -> toggleFilter(s)));
		actions.add(iconLabel(DELETE_ICON, DELETE_HOVER, "Delete this setup", () -> confirmDelete(s)));

		row.add(name, BorderLayout.CENTER);
		row.add(actions, BorderLayout.EAST);
		return row;
	}

	private JLabel iconLabel(ImageIcon icon, ImageIcon hover, String tip, Runnable onClick)
	{
		JLabel lbl = new JLabel(icon);
		lbl.setToolTipText(tip);
		lbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		lbl.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				if (SwingUtilities.isLeftMouseButton(e))
				{
					onClick.run();
				}
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				lbl.setIcon(hover);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				lbl.setIcon(icon);
			}
		});
		return lbl;
	}

	private void toggleFilter(GearSetup s)
	{
		if (activeSetupId == s.getId())
		{
			onClearFilter.run();
			activeSetupId = -1;
		}
		else
		{
			onFilter.accept(s);
			activeSetupId = s.getId();
		}
		renderOverview();
	}

	private void confirmDelete(GearSetup s)
	{
		if (JOptionPane.showConfirmDialog(this, "Delete \"" + s.getName() + "\"?", "Delete setup",
			JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION)
		{
			if (activeSetupId == s.getId())
			{
				activeSetupId = -1;
			}
			onDelete.accept(s.getId());
		}
	}

	/** A clickable home-menu card that opens a section. */
	private JPanel menuCard(String title, String subtitle, Runnable onClick)
	{
		JPanel card = new JPanel(new BorderLayout(8, 0));
		card.setOpaque(true);
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(BorderFactory.createEmptyBorder(11, 12, 11, 12));
		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 62));
		card.setAlignmentX(Component.LEFT_ALIGNMENT);
		card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

		JPanel textCol = new JPanel();
		textCol.setOpaque(false);
		textCol.setLayout(new BoxLayout(textCol, BoxLayout.Y_AXIS));
		JLabel t = new JLabel(title);
		t.setFont(t.getFont().deriveFont(Font.BOLD, 14f));
		t.setForeground(ColorScheme.BRAND_ORANGE);
		t.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel sub = new JLabel(subtitle);
		sub.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		sub.setAlignmentX(Component.LEFT_ALIGNMENT);
		textCol.add(t);
		textCol.add(Box.createVerticalStrut(2));
		textCol.add(sub);

		JLabel chev = new JLabel("›");
		chev.setFont(chev.getFont().deriveFont(Font.BOLD, 18f));
		chev.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		card.add(textCol, BorderLayout.CENTER);
		card.add(chev, BorderLayout.EAST);
		card.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				onClick.run();
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				card.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			}
		});
		return card;
	}

	private void showHome()
	{
		current = null;
		homeHeader.setVisible(true);
		homeMenu.setVisible(true);
		backBar.setVisible(false);
		tradesSection.setVisible(false);
		bankSection.setVisible(false);
		caSection.setVisible(false);
		collectionSection.setVisible(false);
		addCurrent.setVisible(false);
		overviewPanel.setVisible(false);
		detailPanel.setVisible(false);
		revalidate();
		repaint();
	}

	private void showTrades()
	{
		current = null;
		homeHeader.setVisible(false);
		homeMenu.setVisible(false);
		backBar.setVisible(true);
		backTitle.setText("Grand Exchange");
		tradesSection.setVisible(true);
		bankSection.setVisible(false);
		caSection.setVisible(false);
		collectionSection.setVisible(false);
		addCurrent.setVisible(false);
		overviewPanel.setVisible(false);
		detailPanel.setVisible(false);
		revalidate();
		repaint();
	}

	private void showBank()
	{
		current = null;
		homeHeader.setVisible(false);
		homeMenu.setVisible(false);
		backBar.setVisible(true);
		backTitle.setText("Bank Organizer");
		tradesSection.setVisible(false);
		bankSection.setVisible(true);
		caSection.setVisible(false);
		collectionSection.setVisible(false);
		addCurrent.setVisible(false);
		overviewPanel.setVisible(false);
		detailPanel.setVisible(false);
		revalidate();
		repaint();
	}

	private void showCa()
	{
		current = null;
		homeHeader.setVisible(false);
		homeMenu.setVisible(false);
		backBar.setVisible(true);
		backTitle.setText("Combat Achievements");
		tradesSection.setVisible(false);
		bankSection.setVisible(false);
		caSection.setVisible(true);
		collectionSection.setVisible(false);
		addCurrent.setVisible(false);
		overviewPanel.setVisible(false);
		detailPanel.setVisible(false);
		revalidate();
		repaint();
	}

	private void showCollection()
	{
		current = null;
		homeHeader.setVisible(false);
		homeMenu.setVisible(false);
		backBar.setVisible(true);
		backTitle.setText("Collection Log");
		tradesSection.setVisible(false);
		bankSection.setVisible(false);
		caSection.setVisible(false);
		collectionSection.setVisible(true);
		addCurrent.setVisible(false);
		overviewPanel.setVisible(false);
		detailPanel.setVisible(false);
		revalidate();
		repaint();
	}

	private void showSetups()
	{
		current = null;
		homeHeader.setVisible(false);
		homeMenu.setVisible(false);
		backBar.setVisible(true);
		backTitle.setText("Gear Setups");
		tradesSection.setVisible(false);
		bankSection.setVisible(false);
		caSection.setVisible(false);
		collectionSection.setVisible(false);
		addCurrent.setVisible(true);
		overviewPanel.setVisible(true);
		detailPanel.setVisible(false);
		revalidate();
		repaint();
	}

	private void showDetail()
	{
		homeHeader.setVisible(false);
		homeMenu.setVisible(false);
		backBar.setVisible(false);
		tradesSection.setVisible(false);
		bankSection.setVisible(false);
		caSection.setVisible(false);
		collectionSection.setVisible(false);
		addCurrent.setVisible(false);
		overviewPanel.setVisible(false);
		detailPanel.setVisible(true);
		revalidate();
		repaint();
	}

	// -- Bank Organizer section ---------------------------------------------- //

	private JPanel buildBankSection()
	{
		bankSection.setLayout(new BoxLayout(bankSection, BoxLayout.Y_AXIS));
		bankSection.setAlignmentX(Component.LEFT_ALIGNMENT);

		JLabel cap = new JLabel("Bank Organizer");
		cap.setFont(cap.getFont().deriveFont(Font.BOLD, 13f));
		cap.setAlignmentX(Component.LEFT_ALIGNMENT);

		JLabel blurb = new JLabel("<html>Adds an <b>All</b> tab (your whole bank) followed by "
			+ "<b>Gear</b> (grouped by combat style), <b>Teleports</b>, <b>Supplies</b>, "
			+ "<b>Skilling</b> and <b>Misc</b> tabs - laid out and value-sorted, placed first in your "
			+ "tab bar. It only changes how Bank Tags <i>displays</i> your bank; <b>nothing is ever "
			+ "moved</b>, so it's fully within the rules. Open your bank once so we can read it, then "
			+ "organize.</html>");
		blurb.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		blurb.setAlignmentX(Component.LEFT_ALIGNMENT);
		blurb.setBorder(BorderFactory.createEmptyBorder(6, 0, 8, 0));

		JButton organize = new JButton("Organize my bank");
		organize.setAlignmentX(Component.LEFT_ALIGNMENT);
		organize.setToolTipText("Build category tabs from the items currently in your bank");
		organize.addActionListener(e ->
		{
			if (onOrganizeBank != null)
			{
				onOrganizeBank.run();
			}
		});

		JButton remove = new JButton("Remove organized tabs");
		remove.setAlignmentX(Component.LEFT_ALIGNMENT);
		remove.setToolTipText("Delete the tabs RuneTools created (leaves your own tabs alone)");
		remove.addActionListener(e ->
		{
			if (onRemoveTabs != null)
			{
				onRemoveTabs.run();
			}
		});

		JPanel shareSep = new JPanel();
		shareSep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
		shareSep.setPreferredSize(new Dimension(10, 1));
		shareSep.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
		shareSep.setAlignmentX(Component.LEFT_ALIGNMENT);

		JLabel shareBlurb = new JLabel("<html><b>Share a layout.</b> Turn a numbered bank tab into a "
			+ "Bank Tag, then export it to your gecharts.com profile - others can view it and import "
			+ "it into their own RuneLite.</html>");
		shareBlurb.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		shareBlurb.setAlignmentX(Component.LEFT_ALIGNMENT);
		shareBlurb.setBorder(BorderFactory.createEmptyBorder(10, 0, 8, 0));

		JButton convert = new JButton("Save current bank tab as a tag");
		convert.setAlignmentX(Component.LEFT_ALIGNMENT);
		convert.setToolTipText("Turn the numbered bank tab you're viewing into a shareable Bank Tag");
		convert.addActionListener(e ->
		{
			String name = JOptionPane.showInputDialog(this,
				"Open the numbered bank tab you want, then name the tag:", "Bank tab -> tag",
				JOptionPane.PLAIN_MESSAGE);
			if (name != null && !name.trim().isEmpty() && onConvertTab != null)
			{
				onConvertTab.accept(name.trim());
			}
		});

		JButton export = new JButton("Export current bank tab to site");
		export.setAlignmentX(Component.LEFT_ALIGNMENT);
		export.setToolTipText("Publish the bank tag you're viewing to your gecharts.com profile");
		export.addActionListener(e ->
		{
			if (onExportTag != null)
			{
				onExportTag.run();
			}
		});

		bankStatus.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		bankStatus.setAlignmentX(Component.LEFT_ALIGNMENT);
		bankStatus.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

		bankSection.add(cap);
		bankSection.add(blurb);
		bankSection.add(organize);
		bankSection.add(Box.createVerticalStrut(6));
		bankSection.add(remove);
		bankSection.add(Box.createVerticalStrut(12));
		bankSection.add(shareSep);
		bankSection.add(shareBlurb);
		bankSection.add(convert);
		bankSection.add(Box.createVerticalStrut(6));
		bankSection.add(export);
		bankSection.add(bankStatus);
		return bankSection;
	}

	void setBankStatus(String text, boolean ok)
	{
		SwingUtilities.invokeLater(() ->
		{
			bankStatus.setText(text);
			bankStatus.setForeground(ok ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.PROGRESS_ERROR_COLOR);
		});
	}

	// -- Combat Achievements section ------------------------------------------ //

	/** One Collection Log tab's obtained/total counts. */
	static final class ClogTabRow
	{
		final String name;
		final int obtained;
		final int total;

		ClogTabRow(String name, int obtained, int total)
		{
			this.name = name;
			this.obtained = obtained;
			this.total = total;
		}
	}

	static final class CaTierRow
	{
		final String label;
		final int done;
		final int total;
		final int earned;
		final int possible;

		CaTierRow(String label, int done, int total, int earned, int possible)
		{
			this.label = label;
			this.done = done;
			this.total = total;
			this.earned = earned;
			this.possible = possible;
		}
	}

	/** One item's 4h GE buy-limit usage for the Grand Exchange section. */
	static final class BuyLimitRow
	{
		final int itemId;
		final String name;
		final int used;
		final int limit;
		final long resetsAtSec;

		BuyLimitRow(int itemId, String name, int used, int limit, long resetsAtSec)
		{
			this.itemId = itemId;
			this.name = name;
			this.used = used;
			this.limit = limit;
			this.resetsAtSec = resetsAtSec;
		}
	}

	/** A rendered reset label plus the epoch second it counts down to (ticked client-side). */
	private static final class BuyLimitReset
	{
		final JLabel label;
		final long resetsAtSec;

		BuyLimitReset(JLabel label, long resetsAtSec)
		{
			this.label = label;
			this.resetsAtSec = resetsAtSec;
		}
	}

	/** One item a setup needs but the player doesn't own, for the missing-items breakdown tooltip. */
	static final class MissingItem
	{
		final String name;
		final long qty;
		final long price;

		MissingItem(String name, long qty, long price)
		{
			this.name = name;
			this.qty = qty;
			this.price = price;
		}
	}

	/** Result of the setup bank-diff: a one-line summary plus the per-item breakdown for the tooltip. */
	static final class MissingResult
	{
		final String summary;
		final List<MissingItem> items;

		MissingResult(String summary, List<MissingItem> items)
		{
			this.summary = summary;
			this.items = items != null ? items : Collections.emptyList();
		}
	}

	private JPanel buildCollectionSection()
	{
		collectionSection.setLayout(new BoxLayout(collectionSection, BoxLayout.Y_AXIS));
		collectionSection.setAlignmentX(Component.LEFT_ALIGNMENT);

		JLabel cap = new JLabel("Collection Log");
		cap.setFont(cap.getFont().deriveFont(Font.BOLD, 13f));
		cap.setAlignmentX(Component.LEFT_ALIGNMENT);

		clogOverall.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		clogOverall.setAlignmentX(Component.LEFT_ALIGNMENT);
		clogOverall.setBorder(BorderFactory.createEmptyBorder(6, 0, 4, 0));

		clogOverallBar.setStringPainted(true);
		clogOverallBar.setForeground(ColorScheme.BRAND_ORANGE);
		clogOverallBar.setAlignmentX(Component.LEFT_ALIGNMENT);
		clogOverallBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));

		clogTabsPanel.setLayout(new BoxLayout(clogTabsPanel, BoxLayout.Y_AXIS));
		clogTabsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		clogTabsPanel.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

		clogHint.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		clogHint.setAlignmentX(Component.LEFT_ALIGNMENT);
		clogHint.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));

		collectionSection.add(cap);
		collectionSection.add(clogOverall);
		collectionSection.add(clogOverallBar);
		collectionSection.add(clogTabsPanel);
		collectionSection.add(clogHint);
		return collectionSection;
	}

	/** Refresh the Collection Log section (overall + per-tab bars, coverage hint). Any thread. */
	void setCollectionLog(int obtained, int total, List<ClogTabRow> tabs, int syncedPages)
	{
		SwingUtilities.invokeLater(() ->
		{
			clogOverall.setText(total > 0 ? "Overall: " + obtained + " / " + total + " collected"
				: "Open your Collection Log in-game to sync it.");
			clogOverallBar.setMaximum(Math.max(total, 1));
			clogOverallBar.setValue(obtained);
			clogOverallBar.setString(total > 0 ? Math.round(100f * obtained / total) + "%" : "-");
			clogTabsPanel.removeAll();
			if (tabs != null)
			{
				for (ClogTabRow r : tabs)
				{
					JPanel row = new JPanel(new BorderLayout(8, 0));
					row.setOpaque(false);
					row.setAlignmentX(Component.LEFT_ALIGNMENT);
					row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
					JLabel name = new JLabel(r.name);
					name.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
					name.setPreferredSize(new Dimension(80, 18));
					JProgressBar bar = new JProgressBar(0, Math.max(r.total, 1));
					bar.setValue(r.obtained);
					bar.setStringPainted(true);
					bar.setString(r.obtained + "/" + r.total);
					bar.setForeground(ColorScheme.BRAND_ORANGE);
					row.add(name, BorderLayout.WEST);
					row.add(bar, BorderLayout.CENTER);
					clogTabsPanel.add(row);
					clogTabsPanel.add(Box.createVerticalStrut(3));
				}
			}
			clogHint.setText("<html>Synced " + syncedPages + " page" + (syncedPages == 1 ? "" : "s")
				+ " on this account. Open more Collection Log pages in-game to fill in their items on "
				+ "the site.</html>");
			clogTabsPanel.revalidate();
			clogTabsPanel.repaint();
		});
	}

	private JPanel buildCaSection()
	{
		caSection.setLayout(new BoxLayout(caSection, BoxLayout.Y_AXIS));
		caSection.setAlignmentX(Component.LEFT_ALIGNMENT);

		JLabel cap = new JLabel("Combat Achievements");
		cap.setFont(cap.getFont().deriveFont(Font.BOLD, 13f));
		cap.setAlignmentX(Component.LEFT_ALIGNMENT);

		caOverall.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		caOverall.setAlignmentX(Component.LEFT_ALIGNMENT);
		caOverall.setBorder(BorderFactory.createEmptyBorder(6, 0, 2, 0));

		caNextTier.setForeground(ColorScheme.BRAND_ORANGE);
		caNextTier.setAlignmentX(Component.LEFT_ALIGNMENT);
		caNextTier.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));

		caTiersPanel.setLayout(new BoxLayout(caTiersPanel, BoxLayout.Y_AXIS));
		caTiersPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

		JLabel planCap = new JLabel("My plan");
		planCap.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		planCap.setBorder(BorderFactory.createEmptyBorder(10, 0, 3, 0));
		planCap.setAlignmentX(Component.LEFT_ALIGNMENT);

		caPlanPanel.setLayout(new BoxLayout(caPlanPanel, BoxLayout.Y_AXIS));
		caPlanPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

		caSection.add(cap);
		caSection.add(caOverall);
		caSection.add(caNextTier);
		caSection.add(caTiersPanel);
		caSection.add(planCap);
		caSection.add(caPlanPanel);
		setCaPlanInternal(Collections.emptyList());
		return caSection;
	}

	/** Refresh the CA section (tier bars, points-to-next-tier, plan). Any thread. */
	void setCaProgress(String overall, String nextTier, List<CaTierRow> tiers, List<String> planLines)
	{
		SwingUtilities.invokeLater(() ->
		{
			caOverall.setText(overall);
			caNextTier.setText(nextTier != null ? nextTier : " ");
			caTiersPanel.removeAll();
			for (CaTierRow t : tiers)
			{
				JPanel row = new JPanel(new BorderLayout(8, 0));
				row.setOpaque(false);
				row.setAlignmentX(Component.LEFT_ALIGNMENT);
				row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
				JLabel name = new JLabel(t.label);
				name.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				name.setPreferredSize(new Dimension(88, 18));
				JProgressBar bar = new JProgressBar(0, Math.max(t.total, 1));
				bar.setValue(t.done);
				bar.setStringPainted(true);
				bar.setString(t.done + "/" + t.total + " · " + t.earned + " pts");
				bar.setForeground(ColorScheme.BRAND_ORANGE);
				row.add(name, BorderLayout.WEST);
				row.add(bar, BorderLayout.CENTER);
				caTiersPanel.add(row);
				caTiersPanel.add(Box.createVerticalStrut(3));
			}
			setCaPlanInternal(planLines);
			caTiersPanel.revalidate();
			caTiersPanel.repaint();
		});
	}

	private void setCaPlanInternal(List<String> planLines)
	{
		caPlanPanel.removeAll();
		if (planLines == null || planLines.isEmpty())
		{
			JLabel none = new JLabel("<html>No planned tasks - build a plan on the site's "
				+ "Combat Achievements page and it shows up here.</html>");
			none.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			none.setAlignmentX(Component.LEFT_ALIGNMENT);
			caPlanPanel.add(none);
		}
		else
		{
			for (String line : planLines)
			{
				// Names come from the trusted game cache, but escape anyway so this HTML label can
				// never become an img-src beacon if server-derived text is ever routed through it.
				JLabel l = new JLabel("<html>" + htmlEscape(line) + "</html>");
				l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				l.setAlignmentX(Component.LEFT_ALIGNMENT);
				l.setBorder(BorderFactory.createEmptyBorder(0, 0, 3, 0));
				caPlanPanel.add(l);
			}
		}
		caPlanPanel.revalidate();
		caPlanPanel.repaint();
	}

	/** Refresh the GE section's 4h buy-limit rows. Any thread. */
	void setBuyLimits(List<BuyLimitRow> rows)
	{
		SwingUtilities.invokeLater(() ->
		{
			boolean any = rows != null && !rows.isEmpty();
			buyLimitsCap.setVisible(any);
			buyLimitsPanel.setVisible(any);
			buyLimitsPanel.removeAll();
			buyLimitResets.clear();
			if (any)
			{
				for (BuyLimitRow b : rows)
				{
					JPanel row = new JPanel(new BorderLayout(6, 0));
					row.setOpaque(false);
					row.setAlignmentX(Component.LEFT_ALIGNMENT);
					row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
					JLabel icon = new JLabel();
					icon.setPreferredSize(new Dimension(26, 24));
					if (itemManager != null && b.itemId > 0)
					{
						itemManager.getImage(b.itemId).addTo(icon);
					}
					JLabel text = noHtml(new JLabel(b.name + " - " + fmtGp(b.used) + "/" + fmtGp(b.limit)));
					text.setForeground(b.used >= b.limit
						? ColorScheme.PROGRESS_ERROR_COLOR : ColorScheme.LIGHT_GRAY_COLOR);
					JLabel reset = new JLabel(resetText(b.resetsAtSec));
					reset.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
					buyLimitResets.add(new BuyLimitReset(reset, b.resetsAtSec));
					row.add(icon, BorderLayout.WEST);
					row.add(text, BorderLayout.CENTER);
					row.add(reset, BorderLayout.EAST);
					buyLimitsPanel.add(row);
				}
			}
			// Tick only while limits are on screen; the countdown moves without waiting for the next poll.
			if (any && !buyLimitTicker.isRunning())
			{
				buyLimitTicker.start();
			}
			else if (!any && buyLimitTicker.isRunning())
			{
				buyLimitTicker.stop();
			}
			buyLimitsPanel.revalidate();
			buyLimitsPanel.repaint();
		});
	}

	/** Recompute every visible "resets in" label from its target time. Runs on the EDT (Swing Timer). */
	private void tickBuyLimitResets()
	{
		for (BuyLimitReset r : buyLimitResets)
		{
			r.label.setText(resetText(r.resetsAtSec));
		}
	}

	private static String resetText(long resetsAtSec)
	{
		if (resetsAtSec <= 0)
		{
			return "";
		}
		long mins = Math.max(0, (resetsAtSec - System.currentTimeMillis() / 1000L) / 60);
		return mins > 0 ? "resets in " + (mins / 60) + "h " + (mins % 60) + "m" : "resets now";
	}

	/** Stop the buy-limit countdown timer so the panel can be garbage-collected once the plugin stops. */
	void onShutdown()
	{
		SwingUtilities.invokeLater(buyLimitTicker::stop);
	}

	void setMissingComputer(BiConsumer<GearSetup, Consumer<MissingResult>> computer)
	{
		this.missingComputer = computer;
	}

	// -- Grand Exchange (trades) section ------------------------------------- //

	private JPanel buildTradesSection()
	{
		tradesSection.setLayout(new BoxLayout(tradesSection, BoxLayout.Y_AXIS));
		tradesSection.setAlignmentX(Component.LEFT_ALIGNMENT);

		JLabel cap = new JLabel("Grand Exchange");
		cap.setFont(cap.getFont().deriveFont(Font.BOLD, 13f));
		cap.setAlignmentX(Component.LEFT_ALIGNMENT);

		JLabel profitCap = new JLabel("Session profit (est.)");
		profitCap.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		profitCap.setAlignmentX(Component.LEFT_ALIGNMENT);

		sessionProfit.setFont(sessionProfit.getFont().deriveFont(Font.BOLD, 16f));
		sessionProfit.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		sessionProfit.setAlignmentX(Component.LEFT_ALIGNMENT);

		offerSummary.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		offerSummary.setAlignmentX(Component.LEFT_ALIGNMENT);

		JLabel recentCap = new JLabel("Recent flips");
		recentCap.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		recentCap.setBorder(BorderFactory.createEmptyBorder(10, 0, 3, 0));
		recentCap.setAlignmentX(Component.LEFT_ALIGNMENT);

		recentTradesPanel.setLayout(new BoxLayout(recentTradesPanel, BoxLayout.Y_AXIS));
		recentTradesPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

		JButton history = new JButton("View full history ↗");
		history.setAlignmentX(Component.LEFT_ALIGNMENT);
		history.setToolTipText("Open your Grand Exchange history on gecharts.com");
		history.addActionListener(e -> { if (onOpenHistory != null) { onOpenHistory.run(); } });

		tradesSection.add(cap);
		tradesSection.add(Box.createVerticalStrut(6));
		tradesSection.add(profitCap);
		tradesSection.add(sessionProfit);
		tradesSection.add(Box.createVerticalStrut(4));
		tradesSection.add(offerSummary);
		buyLimitsCap.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		buyLimitsCap.setBorder(BorderFactory.createEmptyBorder(10, 0, 3, 0));
		buyLimitsCap.setAlignmentX(Component.LEFT_ALIGNMENT);
		buyLimitsCap.setVisible(false);
		buyLimitsPanel.setLayout(new BoxLayout(buyLimitsPanel, BoxLayout.Y_AXIS));
		buyLimitsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		buyLimitsPanel.setVisible(false);

		tradesSection.add(recentCap);
		tradesSection.add(recentTradesPanel);
		tradesSection.add(buyLimitsCap);
		tradesSection.add(buyLimitsPanel);
		tradesSection.add(Box.createVerticalStrut(10));
		tradesSection.add(history);

		setRecentTradesInternal(Collections.emptyList());
		return tradesSection;
	}

	void setSessionProfit(long profit)
	{
		SwingUtilities.invokeLater(() ->
		{
			sessionProfit.setText((profit >= 0 ? "+" : "") + fmtGp(profit) + " gp");
			sessionProfit.setForeground(profit > 0 ? ColorScheme.PROGRESS_COMPLETE_COLOR
				: profit < 0 ? ColorScheme.PROGRESS_ERROR_COLOR : ColorScheme.LIGHT_GRAY_COLOR);
		});
	}

	void setOfferSummary(int active)
	{
		SwingUtilities.invokeLater(() -> offerSummary.setText(active <= 0
			? "No active GE offers"
			: active + (active == 1 ? " active GE offer" : " active GE offers")));
	}

	void setRecentTrades(List<Trade> trades)
	{
		SwingUtilities.invokeLater(() -> setRecentTradesInternal(trades));
	}

	private void setRecentTradesInternal(List<Trade> trades)
	{
		recentTradesPanel.removeAll();
		if (trades == null || trades.isEmpty())
		{
			JLabel none = new JLabel("No flips yet this session.");
			none.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			none.setAlignmentX(Component.LEFT_ALIGNMENT);
			recentTradesPanel.add(none);
		}
		else
		{
			for (Trade t : trades)
			{
				recentTradesPanel.add(tradeRow(t));
			}
		}
		recentTradesPanel.revalidate();
		recentTradesPanel.repaint();
	}

	private JComponent tradeRow(Trade t)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setOpaque(false);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(26, 24));
		if (itemManager != null && t.getItemId() > 0)
		{
			itemManager.getImage(t.getItemId(), t.getQty(), t.getQty() > 1).addTo(icon);
		}
		boolean buy = "buy".equals(t.getSide());
		JLabel text = new JLabel((buy ? "Bought " : "Sold ") + fmtGp(t.getQty()) + " @ " + fmtGp(t.getPrice()));
		text.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		row.add(icon, BorderLayout.WEST);
		row.add(text, BorderLayout.CENTER);
		return row;
	}

	private static String fmtGp(long v)
	{
		return String.format("%,d", v);
	}

	// -- status setters ------------------------------------------------------ //

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

	/**
	 * Disable Swing's automatic HTML rendering on a label. Swing treats any text starting with
	 * "&lt;html&gt;" as HTML and its renderer fetches &lt;img src=...&gt; URLs on paint - so an
	 * unescaped server-supplied string in a label is a remote-image (IP-leak) beacon. Use this for
	 * every label that ever shows a string that originated on the server.
	 */
	private static JLabel noHtml(JLabel label)
	{
		label.putClientProperty("html.disable", Boolean.TRUE);
		return label;
	}

	/** Escape the HTML metacharacters so a string can be safely embedded inside an intentional &lt;html&gt; label. */
	private static String htmlEscape(String s)
	{
		if (s == null)
		{
			return "";
		}
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
