package com.runetools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
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
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
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
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.MatteBorder;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.Filepath;
import net.runelite.client.util.LinkBrowser;

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

	// Community: the site's Discord link (it redirects to the current invite, so an expired invite can be
	// replaced without a plugin update) + Discord's brand "blurple" for the panel's Discord button.
	private static final String DISCORD_URL = "https://runetools.com/discord";

	// View titles, in sentence case like the site's own pages.
	private static final String TITLE_TRADES = "My trades";
	private static final String TITLE_SETUPS = "My setups";
	private static final String TITLE_BANK = "Bank layouts";
	private static final String TITLE_CA = "Combat Achievements";
	private static final String TITLE_CLOG = "Collection Log";
	private static final String CA_WAITING = "Not read yet.";
	private static final String CLOG_WAITING = "Open your Collection Log in-game.";
	private static final Color DISCORD_BLURPLE = new Color(0x58, 0x65, 0xF2);
	private static final Color DISCORD_BLURPLE_HOVER = new Color(0x6E, 0x7A, 0xF5);
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

	/** A small white Discord glyph (a stylised two-eyed "Clyde" face) for the community button. */
	private static BufferedImage discordGlyph()
	{
		int w = 18, h = 14;
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(Color.WHITE);
		g.fillRoundRect(1, 2, w - 2, h - 4, 9, 9);   // rounded face body
		g.fillOval(1, h - 6, 5, 5);                  // bottom "legs"
		g.fillOval(w - 6, h - 6, 5, 5);
		g.setColor(DISCORD_BLURPLE);                 // eyes cut back to the button's blurple
		g.fillOval(5, 5, 3, 4);
		g.fillOval(w - 8, 5, 3, 4);
		g.dispose();
		return img;
	}

	private static final ImageIcon DISCORD_ICON = new ImageIcon(discordGlyph());

	/** A clean, flat, full-width panel button with a subtle hover - nicer than the default grey L&F. */
	private static JButton flatButton(String text)
	{
		JButton b = new JButton(text);
		b.setFocusPainted(false);
		b.setForeground(Color.WHITE);
		b.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		b.setBorder(new CompoundBorder(
			new LineBorder(ColorScheme.MEDIUM_GRAY_COLOR, 1),
			new EmptyBorder(7, 10, 7, 10)));
		b.setAlignmentX(Component.LEFT_ALIGNMENT);
		b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
		b.setCursor(new Cursor(Cursor.HAND_CURSOR));
		b.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				b.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				b.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			}
		});
		return b;
	}

	/** The prominent "Join our Discord" button - Discord blurple with the glyph, opens the invite. */
	private static JButton discordButton()
	{
		JButton b = new JButton("Join our Discord", DISCORD_ICON);
		b.setFocusPainted(false);
		b.setForeground(Color.WHITE);
		b.setBackground(DISCORD_BLURPLE);
		b.setFont(b.getFont().deriveFont(Font.BOLD));
		b.setBorder(new EmptyBorder(9, 10, 9, 10));
		b.setHorizontalAlignment(SwingConstants.CENTER);
		b.setIconTextGap(8);
		b.setAlignmentX(Component.LEFT_ALIGNMENT);
		b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
		b.setCursor(new Cursor(Cursor.HAND_CURSOR));
		b.setToolTipText("Join the RuneTools Discord community");
		b.addActionListener(e -> LinkBrowser.browse(DISCORD_URL));
		b.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				b.setBackground(DISCORD_BLURPLE_HOVER);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				b.setBackground(DISCORD_BLURPLE);
			}
		});
		return b;
	}

	// status/linked carry server-supplied strings (display name, linked RSNs) - disable Swing's
	// auto-HTML so a "<html><img src=...>" value can't beacon the user's IP (see noHtml()). The status
	// line is the exception: it needs HTML to wrap, so every message is escaped first (wrapped()).
	// The status line is background sync health (home view); what the player's own actions did goes in
	// the message strip, which every view shows until it is dismissed or the next action replaces it.
	private final JLabel status = new JLabel(wrapped("Not connected", STATUS_WIDTH));
	private final JPanel messageStrip = new JPanel(new BorderLayout(4, 0));
	private final JLabel messageLabel = new JLabel(" ");
	private final JPanel noticeStrip = new JPanel(new BorderLayout(4, 0));
	private final JLabel noticeLabel = new JLabel(" ");
	private final JButton noticeLink = new JButton("Open");
	private volatile Runnable onNoticeLink = () -> { };
	private final JLabel linked = noHtml(new JLabel(" "));
	private final JLabel pending = new JLabel("0 unsynced trades");
	private final JLabel lastSync = new JLabel("Last sync: never");
	private JButton signIn;   // the primary CTA: "Connect to RuneTools" when unlinked, "Open RuneTools" when linked

	private final ItemManager itemManager;
	private final Consumer<GearSetup> onFilter;
	private final Runnable onClearFilter;
	private final Consumer<String> onAddCurrent;
	private final Consumer<GearSetup> onUpdateFromGear;
	private final Consumer<GearSetup> onPush;
	private final Consumer<List<GearSetup>> onImportSetups;   // save setups imported from Inventory Setups
	private final LongConsumer onDelete;
	private final SlotEditor editor;
	private final Runnable onOpenHistory;
	private final Runnable onOrganizeBank;
	private final Runnable onRemoveTabs;
	private final Consumer<String> onConvertTab;
	private final Runnable onExportTag;
	private volatile Runnable onShareBank = () -> { };
	private volatile Runnable onRemoveImportedBank = () -> { };
	private volatile Runnable onOpened = () -> { };

	private final JPanel bankSection = new JPanel();

	private final JPanel caSection = new JPanel();
	private final JLabel caGate = new JLabel(" ");   // why nothing syncs here, when nothing does
	private final JLabel caOverall = new JLabel(wrapped(CA_WAITING, STATUS_WIDTH));
	private final JLabel caNextTier = new JLabel(" ");
	private final JPanel caTiersPanel = new JPanel();

	private final JPanel collectionSection = new JPanel();
	private final JLabel clogGate = new JLabel(" ");
	private final JLabel clogOverall = new JLabel(wrapped(CLOG_WAITING, STATUS_WIDTH));
	private final JProgressBar clogOverallBar = new JProgressBar();
	private final JPanel clogTabsPanel = new JPanel();
	private final JLabel clogHint = new JLabel(" ");

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
	private final JButton addCurrent = new JButton("Add current");
	private final JPanel setupsTools = new JPanel();   // holds addCurrent + the Inventory Setups sync buttons

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
	private final Map<Integer, String> itemNames = new HashMap<>();   // EDT only: names already looked up
	private GearSetup current;
	private long activeSetupId = -1;
	private List<GearSetup> lastSetups = Collections.emptyList();

	RuneToolsPanel(Consumer<GearSetup> onFilter, Runnable onClearFilter,
				   ItemManager itemManager, Consumer<String> onAddCurrent,
				   Consumer<GearSetup> onUpdateFromGear, Consumer<GearSetup> onPush, LongConsumer onDelete,
				   SlotEditor editor, Runnable onOpenHistory, Runnable onOrganizeBank, Runnable onRemoveTabs,
				   Consumer<String> onConvertTab, Runnable onExportTag, Runnable onOpenSite, Runnable onCheckDps,
				   Consumer<List<GearSetup>> onImportSetups)
	{
		this.onImportSetups = onImportSetups;
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
		title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
		title.setForeground(ColorScheme.BRAND_ORANGE);
		title.setAlignmentX(Component.LEFT_ALIGNMENT);

		// Stacked rather than a grid, so a status message that wraps onto a second line gets the room.
		JPanel rows = new JPanel();
		rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
		rows.setBorder(BorderFactory.createEmptyBorder(10, 0, 6, 0));
		status.setFont(status.getFont().deriveFont(Font.BOLD));
		pending.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		lastSync.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		for (JLabel row : new JLabel[]{status, pending, lastSync})
		{
			row.setAlignmentX(Component.LEFT_ALIGNMENT);
			if (rows.getComponentCount() > 0)
			{
				rows.add(Box.createVerticalStrut(4));
			}
			rows.add(row);
		}
		rows.setAlignmentX(Component.LEFT_ALIGNMENT);

		signIn = flatButton("Open RuneTools  ↗");
		signIn.setToolTipText("Open runetools.com signed in as this character. The first time, approve it in your browser.");
		signIn.addActionListener(e -> onOpenSite.run());

		JButton dps = flatButton("Check DPS");
		dps.setToolTipText("Open the DPS calculator with the gear you're wearing.");
		dps.addActionListener(e -> onCheckDps.run());

		JButton discord = discordButton();

		addCurrent.setAlignmentX(Component.LEFT_ALIGNMENT);
		addCurrent.setToolTipText("Save what you're wearing and carrying as a new setup.");
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
		homeHeader.add(rows);
		homeHeader.add(signIn);
		homeHeader.add(Box.createVerticalStrut(5));
		homeHeader.add(dps);
		homeHeader.add(Box.createVerticalStrut(10));
		homeHeader.add(discord);
		homeHeader.add(Box.createVerticalStrut(12));
		homeHeader.add(headerSep);

		// Home menu - pick a section to open it (keeps the panel from being one long scroll).
		homeMenu.setLayout(new BoxLayout(homeMenu, BoxLayout.Y_AXIS));
		homeMenu.setAlignmentX(Component.LEFT_ALIGNMENT);
		homeMenu.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
		homeMenu.add(menuCard(TITLE_TRADES, this::showTrades));
		homeMenu.add(Box.createVerticalStrut(8));
		homeMenu.add(menuCard(TITLE_SETUPS, this::showSetups));
		homeMenu.add(Box.createVerticalStrut(8));
		homeMenu.add(menuCard(TITLE_BANK, this::showBank));
		homeMenu.add(Box.createVerticalStrut(8));
		homeMenu.add(menuCard(TITLE_CA, this::showCa));
		homeMenu.add(Box.createVerticalStrut(8));
		homeMenu.add(menuCard(TITLE_CLOG, this::showCollection));
		homeMenu.add(Box.createVerticalStrut(8));

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

		body.add(buildNoticeStrip());
		body.add(buildMessageStrip());
		body.add(homeHeader);
		body.add(homeMenu);
		body.add(backBar);
		body.add(buildTradesSection());
		body.add(buildBankSection());
		body.add(buildCaSection());
		body.add(buildCollectionSection());
		JButton importIs = flatButton("Import setups");
		importIs.setToolTipText("Paste a setup copied with Inventory Setups' Export.");
		importIs.addActionListener(e -> doImportFromInventorySetups());
		JButton copyAllIs = flatButton("Export all");
		copyAllIs.setToolTipText("Save every setup to a file for Inventory Setups' Mass Import.");
		copyAllIs.addActionListener(e -> doExportAllToFile());
		setupsTools.setLayout(new BoxLayout(setupsTools, BoxLayout.Y_AXIS));
		setupsTools.setAlignmentX(Component.LEFT_ALIGNMENT);
		setupsTools.add(addCurrent);
		setupsTools.add(Box.createVerticalStrut(6));
		setupsTools.add(importIs);
		setupsTools.add(Box.createVerticalStrut(4));
		setupsTools.add(copyAllIs);
		body.add(setupsTools);
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
		refresh.setToolTipText("Replace the gear, inventory, rune pouch, quiver and spellbook with what you have now.");
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
				detailTitle.setToolTipText(plainTip(n.trim()));
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
		JButton isCopy = flatButton("Copy setup");
		isCopy.setToolTipText("Copy for Inventory Setups' Import.");
		isCopy.addActionListener(e ->
		{
			if (current != null)
			{
				copyToClipboard(InventorySetupsBridge.toImportString(current), isCopy);
			}
		});

		// wire the fixed grids' menus once (images/fuzzy get set per-setup on open)
		for (int i = 0; i < inventoryGrid.count(); i++)
		{
			final int idx = i;
			wireMenu(inventoryGrid.slot(i), "Inventory",
				target -> editor.readInvSlot(idx, target),
				(id, q) -> setInv(idx, id, q), () -> setInv(idx, -1, 0), true);
		}
		for (int i = 0; i < runePouchGrid.count(); i++)
		{
			final int idx = i;
			wireMenu(runePouchGrid.slot(i), null, null,
				(id, q) -> setPouch(idx, id, q), () -> setPouch(idx, -1, 0), true);
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
		addRow(isCopy, c, true);
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
						(id, q) -> setEquip(k, id), () -> setEquip(k, -1), false);
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
	 * update-from-container item; {@code quantities} adds "Set quantity" (equipment stores no
	 * quantity). Async results (item search / live-container reads) are guarded so a pick that arrives
	 * after the user has navigated to a different setup never writes into the wrong one.
	 */
	private void wireMenu(SlotLabel slot, String container, Consumer<SlotEditor.ItemPick> containerRead,
						  SlotEditor.ItemPick setter, Runnable remove, boolean quantities)
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
				final int had = slot.getItemId();
				final int hadQty = slot.getQty();
				// A search picks an item, not an amount: keep the slot's amount when it's the same item.
				editor.searchItem("Set slot to", guarded((id, q) -> setter.accept(id, id == had ? hadQty : q)));
			}
		});
		m.add(us);
		if (quantities)
		{
			JMenuItem qty = new JMenuItem("Set quantity");
			qty.addActionListener(e ->
			{
				if (current == null || slot.getItemId() <= 0)
				{
					return;
				}
				final int id = slot.getItemId();
				String in = JOptionPane.showInputDialog(this, "Quantity:", String.valueOf(Math.max(1, slot.getQty())));
				Integer q = parseQty(in);
				if (q != null && current != null && slot.getItemId() == id)
				{
					setter.accept(id, q);
				}
				else if (in != null && q == null)
				{
					showMessage("Enter a whole number from 1 to 2,147,483,647.", false);
				}
			});
			m.add(qty);
		}
		JMenuItem rm = new JMenuItem("Remove Item from Slot");
		rm.addActionListener(e -> remove.run());
		m.add(rm);
	}

	/** A typed quantity, or null when it isn't a whole number from 1 up. Commas are allowed. */
	static Integer parseQty(String in)
	{
		if (in == null)
		{
			return null;
		}
		String t = in.trim().replace(",", "");
		if (t.isEmpty() || t.length() > 10 || !t.chars().allMatch(Character::isDigit))
		{
			return null;
		}
		long v = Long.parseLong(t);
		return v >= 1 && v <= Integer.MAX_VALUE ? (int) v : null;
	}

	/** Wrap a setter so a late async result only applies if the setup that started it is still open. */
	private SlotEditor.ItemPick guarded(SlotEditor.ItemPick setter)
	{
		final long forId = current != null ? current.getId() : Long.MIN_VALUE;
		return (id, q) ->
		{
			if (current != null && current.getId() == forId)
			{
				setter.accept(id, q);
			}
		};
	}

	/** Show an item in a slot, with its name in the tooltip once known. */
	private void setSlot(SlotLabel slot, int id, int qty)
	{
		slot.setItem(itemManager, id, qty);
		if (id <= 0)
		{
			return;
		}
		String known = itemNames.get(id);
		if (known != null)
		{
			slot.setName(id, known);
			return;
		}
		editor.itemName(id, name ->
		{
			if (name != null)
			{
				if (itemNames.size() > 5000)
				{
					itemNames.clear();
				}
				itemNames.put(id, name);
				slot.setName(id, name);
			}
		});
	}

	// -- per-setup rendering ------------------------------------------------- //

	private void openSetup(GearSetup s)
	{
		onOpened.run();   // as above: an edit made on the site shows up within seconds
		current = s;
		String title = s.getName() != null ? s.getName() : "Setup";
		detailTitle.setText(title);
		detailTitle.setToolTipText(plainTip(title));   // full name on hover when the row truncates it
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
		missingLabel.setText(wrappedCenter("Checking your bank…", STATUS_WIDTH));
		final long forId = s.getId();
		missingComputer.accept(s, result ->
		{
			if (current != null && current.getId() == forId)
			{
				missingLabel.setText(wrappedCenter(result.summary, STATUS_WIDTH));
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
				setSlot(e.getValue(), id, 1);
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
				setSlot(s, inv.get(i).getItemId(), inv.get(i).getQty());
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
				setSlot(s, r.get(i).getItemId(), r.get(i).getQty());
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
			wireMenu(s, null, null, (id, q) -> setAdditional(idx, id, q), () -> setAdditional(idx, -1, 0), true);
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
				setSlot(s, add.get(i).getItemId(), add.get(i).getQty());
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
				setSlot(quiverSlot, q.get(0).getItemId(), q.get(0).getQty());
				quiverSlot.setFuzzy(current.isFuzzy(q.get(0).getItemId()));
			}
			else
			{
				quiverSlot.clear();
			}
			wireMenu(quiverSlot, null, null, (id, n) -> setQuiver(id, n), () -> setQuiver(-1, 0), true);
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
				setSlot(slot, id, 1);
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

	private void setInv(int i, int id, int qty)
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
		inv.set(i, id > 0 ? new GearSetupItem(id, Math.max(1, qty)) : EMPTY_ITEM);
		while (!inv.isEmpty() && inv.get(inv.size() - 1).getItemId() <= 0)
		{
			inv.remove(inv.size() - 1);   // trim trailing empties so we don't store dead slots
		}
		fillInventory();
		pushUpdate();
	}

	private void setPouch(int i, int id, int qty)
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
			GearSetupItem it = new GearSetupItem(id, Math.max(1, qty));
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

	private void setAdditional(int i, int id, int qty)
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
			GearSetupItem it = new GearSetupItem(id, Math.max(1, qty));
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

	private void setQuiver(int id, int qty)
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
			q.add(new GearSetupItem(id, Math.max(1, qty)));
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
			JLabel empty = new JLabel(wrapped("No setups yet.", STATUS_WIDTH));
			empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			empty.setAlignmentX(Component.LEFT_ALIGNMENT);
			overviewPanel.add(empty);
		}
		else
		{
			// Setups in a collection sit under its name, each labelled by its role (SetupGroups).
			for (SetupGroups.Row r : SetupGroups.rows(lastSetups))
			{
				overviewPanel.add(r.setup == null ? collectionHeader(r.header) : overviewRow(r));
				overviewPanel.add(Box.createVerticalStrut(4));
			}
		}
		overviewPanel.revalidate();
		overviewPanel.repaint();
	}

	/** A collection's name above its setups. The name comes from the site: plain text only. */
	private static JComponent collectionHeader(String name)
	{
		JLabel h = noHtml(new JLabel(name));
		h.setFont(h.getFont().deriveFont(Font.BOLD));
		h.setForeground(ColorScheme.BRAND_ORANGE);
		h.setAlignmentX(Component.LEFT_ALIGNMENT);
		h.setBorder(BorderFactory.createEmptyBorder(6, 2, 0, 0));
		h.setMaximumSize(new Dimension(Integer.MAX_VALUE, h.getPreferredSize().height + 6));
		return h;
	}

	private JPanel overviewRow(SetupGroups.Row r)
	{
		GearSetup s = r.setup;
		boolean active = s.getId() == activeSetupId;
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setOpaque(true);
		row.setBackground(active ? ColorScheme.DARKER_GRAY_HOVER_COLOR : ColorScheme.DARKER_GRAY_COLOR);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
		row.setBorder(BorderFactory.createCompoundBorder(
			new MatteBorder(0, active ? 3 : 0, 0, 0, ColorScheme.BRAND_ORANGE),
			BorderFactory.createEmptyBorder(7, active ? 6 : 9, 7, 6)));

		JLabel name = noHtml(new JLabel(r.label));
		name.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		name.setToolTipText(plainTip(r.tooltip));   // a role's row names the setup it opens
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
			active ? "Filtering your bank to this setup. Click to stop." : "Filter your bank to this setup",
			() -> toggleFilter(s)));
		actions.add(iconLabel(DELETE_ICON, DELETE_HOVER, "Delete this setup", () -> confirmDelete(s)));

		row.add(name, BorderLayout.CENTER);
		row.add(actions, BorderLayout.EAST);
		if (!r.indented)
		{
			return row;
		}
		// Inside a collection: set in a little under its header.
		JPanel wrap = new JPanel(new BorderLayout());
		wrap.setOpaque(false);
		wrap.setAlignmentX(Component.LEFT_ALIGNMENT);
		wrap.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 0));
		wrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
		wrap.add(row, BorderLayout.CENTER);
		return wrap;
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
	private JPanel menuCard(String title, Runnable onClick)
	{
		JPanel card = new JPanel(new BorderLayout(8, 0));
		card.setOpaque(true);
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(BorderFactory.createEmptyBorder(11, 12, 11, 12));
		card.setAlignmentX(Component.LEFT_ALIGNMENT);
		card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

		JLabel t = new JLabel(title);
		t.setFont(t.getFont().deriveFont(Font.BOLD, 14f));
		t.setForeground(ColorScheme.BRAND_ORANGE);

		JLabel chev = new JLabel("›");
		chev.setFont(chev.getFont().deriveFont(Font.BOLD, 18f));
		chev.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		card.add(t, BorderLayout.CENTER);
		card.add(chev, BorderLayout.EAST);
		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
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

	private enum View
	{
		HOME, TRADES, SETUPS, DETAIL, BANK, CA, CLOG
	}

	private View view = View.HOME;
	private long viewSetupId = -1;

	/**
	 * Show one view. A different view (or another setup) starts at the top, so a long view's scroll
	 * position never carries over; the same one refreshed in place keeps its place.
	 */
	private void show(View v, String title)
	{
		if (v != View.DETAIL)
		{
			current = null;
		}
		long setupId = v == View.DETAIL && current != null ? current.getId() : -1;
		boolean changed = v != view || setupId != viewSetupId;
		view = v;
		viewSetupId = setupId;
		homeHeader.setVisible(v == View.HOME);
		homeMenu.setVisible(v == View.HOME);
		backBar.setVisible(v != View.HOME && v != View.DETAIL);
		if (title != null)
		{
			backTitle.setText(title);
		}
		tradesSection.setVisible(v == View.TRADES);
		bankSection.setVisible(v == View.BANK);
		caSection.setVisible(v == View.CA);
		collectionSection.setVisible(v == View.CLOG);
		setupsTools.setVisible(v == View.SETUPS);
		overviewPanel.setVisible(v == View.SETUPS);
		detailPanel.setVisible(v == View.DETAIL);
		revalidate();
		repaint();
		if (changed)
		{
			scrollToTop();
		}
	}

	private void scrollToTop()
	{
		JScrollPane sp = getScrollPane();
		if (sp != null)
		{
			SwingUtilities.invokeLater(() -> sp.getVerticalScrollBar().setValue(0));
		}
	}

	private void showHome()
	{
		show(View.HOME, null);
	}

	private void showTrades()
	{
		show(View.TRADES, TITLE_TRADES);
	}

	private void showBank()
	{
		show(View.BANK, TITLE_BANK);
	}

	private void showCa()
	{
		show(View.CA, TITLE_CA);
	}

	private void showCollection()
	{
		show(View.CLOG, TITLE_CLOG);
	}

	private void showSetups()
	{
		onOpened.run();   // about to use the setups: fetch any edits made on the site (rate-limited)
		show(View.SETUPS, TITLE_SETUPS);
	}

	private void showDetail()
	{
		show(View.DETAIL, null);
	}

	// -- Bank Organizer section ---------------------------------------------- //

	private JPanel buildBankSection()
	{
		bankSection.setLayout(new BoxLayout(bankSection, BoxLayout.Y_AXIS));
		bankSection.setAlignmentX(Component.LEFT_ALIGNMENT);

		JButton organize = new JButton("Organize bank");
		organize.setAlignmentX(Component.LEFT_ALIGNMENT);
		organize.setToolTipText("Sort your bank into category tabs (Bank Tags). Nothing in your bank is moved.");
		organize.addActionListener(e ->
		{
			if (onOrganizeBank != null)
			{
				onOrganizeBank.run();
			}
		});

		JButton remove = new JButton("Remove tabs");
		remove.setAlignmentX(Component.LEFT_ALIGNMENT);
		remove.setToolTipText("Delete the tabs Organize bank made. Your own tabs stay.");
		remove.addActionListener(e ->
		{
			if (onRemoveTabs != null)
			{
				onRemoveTabs.run();
			}
		});

		JButton convert = new JButton("Save tab as tag");
		convert.setAlignmentX(Component.LEFT_ALIGNMENT);
		convert.setToolTipText("Turn the numbered bank tab you're viewing into a Bank Tags tag.");
		convert.addActionListener(e ->
		{
			String name = JOptionPane.showInputDialog(this, "Tag name:", "Save tab as tag", JOptionPane.PLAIN_MESSAGE);
			if (name != null && !name.trim().isEmpty() && onConvertTab != null)
			{
				onConvertTab.accept(name.trim());
			}
		});

		JButton export = new JButton("Export tab");
		export.setAlignmentX(Component.LEFT_ALIGNMENT);
		export.setToolTipText("Publish the tag tab you're viewing to your RuneTools profile.");
		export.addActionListener(e ->
		{
			if (onExportTag != null)
			{
				onExportTag.run();
			}
		});

		JButton shareBank = new JButton("Share whole bank");
		shareBank.setAlignmentX(Component.LEFT_ALIGNMENT);
		shareBank.setToolTipText("Publish every bank tab as one layout others can import.");
		shareBank.addActionListener(e -> onShareBank.run());

		JButton removeImported = new JButton("Remove imported");
		removeImported.setAlignmentX(Component.LEFT_ALIGNMENT);
		removeImported.setToolTipText("Delete the tabs made by the last whole bank layout you imported.");
		removeImported.addActionListener(e -> onRemoveImportedBank.run());

		bankSection.add(caption("Organizer"));
		bankSection.add(organize);
		bankSection.add(Box.createVerticalStrut(6));
		bankSection.add(remove);
		bankSection.add(Box.createVerticalStrut(12));
		bankSection.add(separator());
		bankSection.add(caption("One tab"));
		bankSection.add(convert);
		bankSection.add(Box.createVerticalStrut(6));
		bankSection.add(export);
		bankSection.add(Box.createVerticalStrut(12));
		bankSection.add(separator());
		bankSection.add(caption("Whole bank"));
		bankSection.add(shareBank);
		bankSection.add(Box.createVerticalStrut(6));
		bankSection.add(removeImported);
		return bankSection;
	}

	/** A one-word sub-heading inside a view. */
	private static JLabel caption(String text)
	{
		JLabel cap = new JLabel(text);
		cap.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		cap.setAlignmentX(Component.LEFT_ALIGNMENT);
		cap.setBorder(BorderFactory.createEmptyBorder(8, 0, 6, 0));
		return cap;
	}

	private static JPanel separator()
	{
		JPanel sep = new JPanel();
		sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
		sep.setPreferredSize(new Dimension(10, 1));
		sep.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
		sep.setAlignmentX(Component.LEFT_ALIGNMENT);
		return sep;
	}

	/** A bank action's result: shown in the message strip like every other action's. */
	void setBankStatus(String text, boolean ok)
	{
		showMessage(text, ok);
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

		gateStyle(clogGate);

		clogOverall.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		clogOverall.setAlignmentX(Component.LEFT_ALIGNMENT);
		clogOverall.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));

		clogOverallBar.setStringPainted(true);
		clogOverallBar.setForeground(ColorScheme.BRAND_ORANGE);
		clogOverallBar.setAlignmentX(Component.LEFT_ALIGNMENT);
		clogOverallBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
		clogOverallBar.setVisible(false);

		clogTabsPanel.setLayout(new BoxLayout(clogTabsPanel, BoxLayout.Y_AXIS));
		clogTabsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		clogTabsPanel.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

		clogHint.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		clogHint.setAlignmentX(Component.LEFT_ALIGNMENT);
		clogHint.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
		clogHint.setToolTipText("Opening your Collection Log in-game reads the whole log. Pages you open add their names and quantities.");

		collectionSection.add(clogGate);
		collectionSection.add(clogOverall);
		collectionSection.add(clogOverallBar);
		collectionSection.add(clogTabsPanel);
		collectionSection.add(clogHint);
		return collectionSection;
	}

	/** Refresh the Collection Log section (overall + per-tab bars, pages read). Any thread. */
	void setCollectionLog(int obtained, int total, List<ClogTabRow> tabs, int syncedPages)
	{
		SwingUtilities.invokeLater(() ->
		{
			clogOverall.setText(wrapped(total > 0 ? String.format("%,d / %,d collected", obtained, total)
				: CLOG_WAITING, STATUS_WIDTH));
			clogOverallBar.setVisible(total > 0);
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
			clogHint.setText(wrapped("Pages read: " + syncedPages, STATUS_WIDTH));
			clogTabsPanel.revalidate();
			clogTabsPanel.repaint();
		});
	}

	/**
	 * Why the Combat Achievements and Collection Log views can't sync, or null when they can: "Sync
	 * off", "Connect this character", ... Shown in place of advice that wouldn't work. Any thread.
	 */
	void setGates(String caWhy, String clogWhy)
	{
		SwingUtilities.invokeLater(() ->
		{
			gate(caGate, caWhy);
			gate(clogGate, clogWhy);
		});
	}

	private static void gate(JLabel label, String why)
	{
		label.setVisible(why != null);
		label.setText(why != null ? wrapped(why, STATUS_WIDTH) : " ");
	}

	private static void gateStyle(JLabel label)
	{
		label.setForeground(ColorScheme.BRAND_ORANGE);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		label.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
		label.setVisible(false);
	}

	private JPanel buildCaSection()
	{
		caSection.setLayout(new BoxLayout(caSection, BoxLayout.Y_AXIS));
		caSection.setAlignmentX(Component.LEFT_ALIGNMENT);

		gateStyle(caGate);

		caOverall.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		caOverall.setAlignmentX(Component.LEFT_ALIGNMENT);
		caOverall.setBorder(BorderFactory.createEmptyBorder(0, 0, 2, 0));

		caNextTier.setForeground(ColorScheme.BRAND_ORANGE);
		caNextTier.setAlignmentX(Component.LEFT_ALIGNMENT);
		caNextTier.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));

		caTiersPanel.setLayout(new BoxLayout(caTiersPanel, BoxLayout.Y_AXIS));
		caTiersPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

		caSection.add(caGate);
		caSection.add(caOverall);
		caSection.add(caNextTier);
		caSection.add(caTiersPanel);
		return caSection;
	}

	/** Refresh the CA section (tier bars, points-to-next-tier). Any thread. */
	void setCaProgress(String overall, String nextTier, List<CaTierRow> tiers)
	{
		SwingUtilities.invokeLater(() ->
		{
			caOverall.setText(wrapped(overall, STATUS_WIDTH));
			caNextTier.setText(nextTier != null ? wrapped(nextTier, STATUS_WIDTH) : " ");
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
			caTiersPanel.revalidate();
			caTiersPanel.repaint();
		});
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

		JLabel profitCap = new JLabel("Session profit");
		profitCap.setToolTipText("Estimate: this session's buys matched to sells, after GE tax.");
		profitCap.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		profitCap.setAlignmentX(Component.LEFT_ALIGNMENT);

		sessionProfit.setFont(sessionProfit.getFont().deriveFont(Font.BOLD, 16f));
		sessionProfit.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		sessionProfit.setAlignmentX(Component.LEFT_ALIGNMENT);

		offerSummary.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		offerSummary.setAlignmentX(Component.LEFT_ALIGNMENT);

		JLabel recentCap = new JLabel("Recent trades");
		recentCap.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		recentCap.setBorder(BorderFactory.createEmptyBorder(10, 0, 3, 0));
		recentCap.setAlignmentX(Component.LEFT_ALIGNMENT);

		recentTradesPanel.setLayout(new BoxLayout(recentTradesPanel, BoxLayout.Y_AXIS));
		recentTradesPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

		JButton history = new JButton("Full history ↗");
		history.setAlignmentX(Component.LEFT_ALIGNMENT);
		history.setToolTipText("Your trade history on runetools.com");
		history.addActionListener(e -> { if (onOpenHistory != null) { onOpenHistory.run(); } });

		tradesSection.add(profitCap);
		tradesSection.add(sessionProfit);
		tradesSection.add(Box.createVerticalStrut(4));
		tradesSection.add(offerSummary);
		tradesSection.add(recentCap);
		tradesSection.add(recentTradesPanel);
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
			JLabel none = new JLabel("No trades yet.");
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

	/** What "Share whole bank" and "Remove imported" do. */
	void setBankLayoutActions(Runnable share, Runnable removeImported)
	{
		this.onShareBank = share;
		this.onRemoveImportedBank = removeImported;
	}

	/**
	 * Run when the player opens this side panel, the setups view or a setup (the plugin uses it to
	 * fetch fresh setups, at most once every few seconds).
	 */
	void setOnOpened(Runnable r)
	{
		onOpened = r != null ? r : () -> { };
	}

	@Override
	public void onActivate()
	{
		onOpened.run();
	}

	// -- status setters ------------------------------------------------------ //

	// Whether the status line last showed a problem (red), so a later successful sync can clear it.
	private volatile boolean statusError;

	boolean isStatusError()
	{
		return statusError;
	}

	/** Background sync health, on the home view. Any thread. */
	void setStatus(String text, boolean ok)
	{
		statusError = !ok;
		SwingUtilities.invokeLater(() ->
		{
			// leading dot picks up the status colour (green ok / red not); long messages wrap
			status.setText(wrapped("●  " + (text != null ? text : ""), STATUS_WIDTH));
			status.setForeground(ok ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.PROGRESS_ERROR_COLOR);
			status.setToolTipText(null);
		});
	}

	/**
	 * What something the player did came to (saved, refused, the pairing code, ...). Shown above every
	 * view until it is dismissed or the next result replaces it; background syncs never clear it.
	 * Any thread.
	 */
	void showMessage(String text, boolean ok)
	{
		SwingUtilities.invokeLater(() ->
		{
			if (text == null || text.trim().isEmpty())
			{
				messageStrip.setVisible(false);
			}
			else
			{
				messageLabel.setText(wrapped(text, MESSAGE_WIDTH));
				messageLabel.setForeground(ok ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.PROGRESS_ERROR_COLOR);
				messageStrip.setVisible(true);
			}
			revalidate();
			repaint();
		});
	}

	void clearMessage()
	{
		showMessage(null, true);
	}

	/**
	 * A notice from the site (its remote config), or the update-required line. {@code onLink} opens its
	 * link and is null when it has none. Any thread.
	 */
	void setNotice(String text, String level, Runnable onLink)
	{
		SwingUtilities.invokeLater(() ->
		{
			if (text == null || text.trim().isEmpty())
			{
				noticeStrip.setVisible(false);
			}
			else
			{
				noticeLabel.setText(wrapped(text, MESSAGE_WIDTH));
				noticeLabel.setForeground("error".equals(level) ? ColorScheme.PROGRESS_ERROR_COLOR
					: "warn".equals(level) ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR);
				onNoticeLink = onLink != null ? onLink : () -> { };
				noticeLink.setVisible(onLink != null);
				noticeStrip.setVisible(true);
			}
			revalidate();
			repaint();
		});
	}

	private JPanel buildMessageStrip()
	{
		messageStrip.setAlignmentX(Component.LEFT_ALIGNMENT);
		messageStrip.setBorder(BorderFactory.createCompoundBorder(
			new LineBorder(ColorScheme.MEDIUM_GRAY_COLOR, 1), new EmptyBorder(5, 6, 5, 4)));
		messageStrip.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		JButton close = new JButton("×");
		close.setMargin(new Insets(0, 4, 0, 4));
		close.setToolTipText("Dismiss");
		close.setFocusPainted(false);
		close.addActionListener(e -> clearMessage());
		JPanel closeCol = new JPanel(new BorderLayout());
		closeCol.setOpaque(false);
		closeCol.add(close, BorderLayout.NORTH);
		messageStrip.add(messageLabel, BorderLayout.CENTER);
		messageStrip.add(closeCol, BorderLayout.EAST);
		messageStrip.setVisible(false);
		JPanel wrap = new JPanel(new BorderLayout());
		wrap.setAlignmentX(Component.LEFT_ALIGNMENT);
		wrap.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
		wrap.add(messageStrip, BorderLayout.CENTER);
		hideWithChild(wrap, messageStrip);
		return wrap;
	}

	private JPanel buildNoticeStrip()
	{
		noticeStrip.setAlignmentX(Component.LEFT_ALIGNMENT);
		noticeStrip.setBorder(BorderFactory.createCompoundBorder(
			new LineBorder(ColorScheme.BRAND_ORANGE, 1), new EmptyBorder(5, 6, 5, 4)));
		noticeStrip.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		noticeLink.setMargin(new Insets(0, 4, 0, 4));
		noticeLink.setFocusPainted(false);
		noticeLink.addActionListener(e -> onNoticeLink.run());
		noticeLink.setVisible(false);
		JPanel linkCol = new JPanel(new BorderLayout());
		linkCol.setOpaque(false);
		linkCol.add(noticeLink, BorderLayout.NORTH);
		noticeStrip.add(noticeLabel, BorderLayout.CENTER);
		noticeStrip.add(linkCol, BorderLayout.EAST);
		noticeStrip.setVisible(false);
		JPanel wrap = new JPanel(new BorderLayout());
		wrap.setAlignmentX(Component.LEFT_ALIGNMENT);
		wrap.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
		wrap.add(noticeStrip, BorderLayout.CENTER);
		hideWithChild(wrap, noticeStrip);
		return wrap;
	}

	/** Keep a wrapper (and its bottom gap) hidden whenever the strip inside it is. */
	private static void hideWithChild(JPanel wrap, JComponent child)
	{
		wrap.setVisible(child.isVisible());
		child.addComponentListener(new java.awt.event.ComponentAdapter()
		{
			@Override
			public void componentShown(java.awt.event.ComponentEvent e)
			{
				wrap.setVisible(true);
			}

			@Override
			public void componentHidden(java.awt.event.ComponentEvent e)
			{
				wrap.setVisible(false);
			}
		});
	}

	/**
	 * Reflect the current character's link state on the primary button so there's always an obvious
	 * fix when disconnected: it reads "Connect to RuneTools" when unlinked and "Open RuneTools" once
	 * linked. Both do the same thing: open the site signed in, or start pairing when this character
	 * has no working key yet.
	 */
	void setConnectButton(boolean linked)
	{
		SwingUtilities.invokeLater(() ->
		{
			if (signIn != null)
			{
				signIn.setText(linked ? "Open RuneTools  ↗" : "Connect to RuneTools");
			}
		});
	}

	private void copyToClipboard(String text, JButton feedbackButton)
	{
		if (text == null)
		{
			return;
		}
		try
		{
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
		}
		catch (RuntimeException ex)
		{
			showMessage("Couldn't use the clipboard.", false);
			return;
		}
		if (feedbackButton != null)
		{
			final String orig = feedbackButton.getText();
			feedbackButton.setText("Copied");
			Timer t = new Timer(1600, ev -> feedbackButton.setText(orig));
			t.setRepeats(false);
			t.start();
		}
	}

	/** Most setups one paste may import (the site keeps 300 per account). */
	private static final int MAX_IMPORT = 300;

	/**
	 * Paste one Inventory Setups export string (or a JSON array of them, as a Mass Export file holds)
	 * and save each as a RuneTools setup. Only reading happens here: the plugin checks the character
	 * can save, sends them, and reports what was really saved in the message strip.
	 */
	private void doImportFromInventorySetups()
	{
		JTextArea ta = new JTextArea(8, 28);
		ta.setLineWrap(true);
		int ok = JOptionPane.showConfirmDialog(this, new JScrollPane(ta),
			"Paste from Inventory Setups' Export", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
		if (ok != JOptionPane.OK_OPTION)
		{
			return;
		}
		String raw = ta.getText() != null ? ta.getText().trim() : "";
		if (raw.isEmpty())
		{
			return;
		}
		List<GearSetup> parsed = parseInventorySetups(raw, MAX_IMPORT);
		if (parsed.isEmpty())
		{
			showMessage("Couldn't read that. Paste the text Inventory Setups' Export copied.", false);
			return;
		}
		onImportSetups.accept(parsed);
	}

	/** Every setup in an Inventory Setups export string or JSON array of them, up to {@code max}. */
	static List<GearSetup> parseInventorySetups(String raw, int max)
	{
		List<GearSetup> out = new ArrayList<>();
		try
		{
			if (raw.startsWith("["))
			{
				JsonArray arr = new JsonParser().parse(raw).getAsJsonArray();
				for (JsonElement el : arr)
				{
					if (out.size() >= max)
					{
						break;
					}
					GearSetup g = InventorySetupsBridge.fromExportString(el.toString());
					if (g != null)
					{
						out.add(g);
					}
				}
			}
			else
			{
				GearSetup g = InventorySetupsBridge.fromExportString(raw);
				if (g != null)
				{
					out.add(g);
				}
			}
		}
		catch (RuntimeException ignored)
		{
			out.clear();
		}
		return out;
	}

	/**
	 * Save every setup to a file Inventory Setups' Mass Import reads (a JSON array of its export
	 * objects), through RuneLite's own file chooser.
	 */
	private void doExportAllToFile()
	{
		if (lastSetups == null || lastSetups.isEmpty())
		{
			showMessage("No setups to export.", false);
			return;
		}
		String json = InventorySetupsBridge.toMassExport(lastSetups);
		int n = lastSetups.size();
		try
		{
			List<Filepath> picked = new Filepath.Chooser()
				.setIsSave()
				.setAcceptsFiles()
				.setDialogTitle("Export setups for Inventory Setups")
				.addExtensionFilter("JSON file", "json")
				.setDefaultExtension("json")
				.setFileName("runetools_setups.json")
				.showDialog(this);
			if (picked == null || picked.isEmpty())
			{
				return;
			}
			Filepath file = picked.get(0);
			file.write(json);
			showMessage("Saved " + n + " setup" + (n == 1 ? "" : "s") + " to " + file.getFileName()
				+ ". Use Mass Import Setups in Inventory Setups.", true);
		}
		catch (IOException | RuntimeException ex)
		{
			showMessage("Couldn't save the file.", false);
		}
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
	// Wrapping widths for HTML labels, inside the panel's 205 px of content: the status lines, and the
	// message and notice strips beside their buttons. In points, not px: Swing's HTML renderer scales a
	// px width by 1.3, so "185px" came out wider than the panel and every wrapped line was clipped.
	private static final int STATUS_WIDTH = 180;
	private static final int MESSAGE_WIDTH = 140;

	/**
	 * Plain text as a label that wraps at {@code width} instead of being cut off. The text is escaped,
	 * so a server-supplied message is shown as text and can never become markup.
	 */
	static String wrapped(String text, int width)
	{
		if (text == null || text.trim().isEmpty())
		{
			return " ";
		}
		return "<html><div style='width:" + width + "pt'>" + htmlEscape(text) + "</div></html>";
	}

	private static String wrappedCenter(String text, int width)
	{
		if (text == null || text.trim().isEmpty())
		{
			return " ";
		}
		return "<html><div style='width:" + width + "pt;text-align:center'>" + htmlEscape(text) + "</div></html>";
	}

	/**
	 * A tooltip that shows {@code s} as plain text. A label's html.disable doesn't reach its tooltip,
	 * and Swing renders a tooltip that starts with "<html>" as HTML (remote images included), so a
	 * setup name from the site is escaped inside one. Null or empty gives no tooltip.
	 */
	static String plainTip(String s)
	{
		return s == null || s.isEmpty() ? null : "<html>" + htmlEscape(s) + "</html>";
	}

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
