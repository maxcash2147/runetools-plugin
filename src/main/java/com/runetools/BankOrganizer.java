package com.runetools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.banktags.BankTagsService;
import net.runelite.client.plugins.banktags.TagManager;
import net.runelite.client.plugins.banktags.tabs.Layout;
import net.runelite.client.plugins.banktags.tabs.LayoutManager;
import net.runelite.client.plugins.banktags.tabs.TabManager;
import net.runelite.client.plugins.banktags.tabs.TagTab;

/**
 * One-click bank organiser. Reads the items currently in the bank, asks {@link BankCategorizer}
 * to bucket them, and materialises one Bank Tags tab per category - each with the relevant items
 * tagged and a saved {@link Layout} that packs them into a tidy grid.
 *
 * <p>This never moves a single item in the real bank: like Inventory Setups' bank layouts it only
 * changes how core Bank Tags <em>renders</em> the tab, so it's purely client-side and rules-safe.
 * Every method runs on the client thread.
 *
 * <p>Tabs we create are remembered in config ({@link #KEY_OWNED}) so a re-organise cleanly rebuilds
 * our own tabs (and a "remove" cleanly deletes them) without disturbing tabs the user made.
 */
@Slf4j
class BankOrganizer
{
	private static final String GROUP = RuneToolsConfig.GROUP;
	private static final String KEY_OWNED = "organizedTabs";
	private static final String KEY_FILED = "organizedFiledItems";   // ids we've filed (for File-new)
	private static final String ALL_TAG = "All";   // a full-bank tab shown before the category tabs
	// Core Bank Tags config: the ordered CSV of tag-tab names the bank rebuilds its tab bar from.
	private static final int COLS = 8;
	private static final int MAX_LAYOUT_CELLS = 4096;   // hard cap on an imported layout's grid size

	private final BankTagsService bankTags;
	private final TagManager tagManager;
	private final LayoutManager layoutManager;
	private final TabManager tabManager;
	private final ItemManager itemManager;
	private final ClientThread clientThread;
	private final ConfigManager configManager;
	private final BankCategorizer categorizer;

	BankOrganizer(BankTagsService bankTags, TagManager tagManager, LayoutManager layoutManager,
				  TabManager tabManager, ItemManager itemManager, ClientThread clientThread,
				  ConfigManager configManager)
	{
		this.bankTags = bankTags;
		this.tagManager = tagManager;
		this.layoutManager = layoutManager;
		this.tabManager = tabManager;
		this.itemManager = itemManager;
		this.clientThread = clientThread;
		this.configManager = configManager;
		this.categorizer = new BankCategorizer(itemManager);
	}

	/** How a run treats tabs that already exist. */
	enum Mode
	{
		/** Regenerate every RuneTools tab from scratch (discards manual edits to them). */
		REBUILD,
		/** Keep existing tabs/edits untouched; only file items gained since the last organise. */
		FILE_NEW
	}

	/** True if we've already built our tabs (so the caller can prompt Rebuild vs File-new). */
	boolean hasOrganized()
	{
		return !ownedTags().isEmpty();
	}

	/**
	 * Organise the bank. {@code onStatus} is called (on the client thread) with a human status line.
	 * {@link Mode#REBUILD} rebuilds every tab; {@link Mode#FILE_NEW} only files newly-gained items,
	 * leaving existing tabs and any manual edits to them alone.
	 */
	void organize(List<Integer> bankItemIds, Mode mode, Consumer<String> onStatus)
	{
		clientThread.invoke(() ->
		{
			String msg = mode == Mode.FILE_NEW ? fileNew(bankItemIds) : rebuild(bankItemIds);
			if (onStatus != null)
			{
				onStatus.accept(msg);
			}
		});
	}

	/** Full (re)build: every tab regenerated from scratch. Resets the "already filed" set. */
	private String rebuild(List<Integer> bankItemIds)
	{
		List<BankCategorizer.CategoryTab> tabs = categorizer.categorize(bankItemIds);
		if (tabs.isEmpty())
		{
			return "Your bank looks empty - nothing to organize.";
		}

		// Desired tags this run: an "All" full-bank tab first, then one per category.
		List<String> owned = new ArrayList<>();
		owned.add(ALL_TAG);
		for (BankCategorizer.CategoryTab ct : tabs)
		{
			owned.add(ct.category.label);
		}

		// Remove only the tabs we previously owned that we no longer want; keep the rest IN PLACE
		// (re-adding a still-wanted tab would append it to the end of the bar - the "jumps to the
		// bottom on re-organise" bug).
		for (String tag : ownedTags())
		{
			if (!owned.contains(tag))
			{
				tagManager.removeTag(tag);
				layoutManager.removeLayout(tag);
				tabManager.remove(tag);
			}
		}

		// "All" tab: tag every bank item, no custom layout, so it mirrors your whole bank.
		Set<Integer> filed = new LinkedHashSet<>();
		tagManager.removeTag(ALL_TAG);
		for (Integer raw : bankItemIds)
		{
			if (raw != null && raw > 0)
			{
				int id = itemManager.canonicalize(raw);
				tagManager.addTag(id, ALL_TAG, false);
				filed.add(id);
			}
		}
		layoutManager.removeLayout(ALL_TAG);
		ensureTab(ALL_TAG, mostValuable(bankItemIds));

		for (BankCategorizer.CategoryTab ct : tabs)
		{
			String tag = ct.category.label;
			tagManager.removeTag(tag);                 // refresh membership (adds new items, drops gone)
			for (List<Integer> group : ct.groups)
			{
				for (int id : group)
				{
					tagManager.addTag(id, tag, false);
				}
			}
			layoutManager.saveLayout(new Layout(tag, packGroups(ct.groups)));
			ensureTab(tag, ct.iconItemId);
		}

		reorderLiveTabsFirst(owned);                   // put ours first (in place for existing, front for new)
		tabManager.save();                             // persist icons + tab order through Bank Tags' own API
		configManager.setConfiguration(GROUP, KEY_OWNED, String.join(",", owned));
		writeFiled(filed);

		bankTags.openBankTag(ALL_TAG, BankTagsService.OPTION_ALLOW_MODIFICATIONS);
		log.debug("RuneTools: organised bank into {} tab(s)", owned.size());
		return "Organized into " + owned.size() + " tabs. Open your bank to see it.";
	}

	/**
	 * Additive: file only the items the bank has gained since the last organise. Existing tabs, their
	 * layouts and any manual edits are left untouched - new items are appended after a tab's content,
	 * and a brand-new category gets a fresh tab. Falls back to a full build if we've never organised.
	 */
	private String fileNew(List<Integer> bankItemIds)
	{
		if (!hasOrganized())
		{
			return rebuild(bankItemIds);
		}

		Set<Integer> filed = readFiled();
		List<Integer> fresh = new ArrayList<>();
		Set<Integer> freshSeen = new HashSet<>();
		for (Integer raw : bankItemIds)
		{
			if (raw == null || raw <= 0)
			{
				continue;
			}
			int id = itemManager.canonicalize(raw);
			if (!filed.contains(id) && freshSeen.add(id))
			{
				fresh.add(id);
			}
		}
		if (fresh.isEmpty())
		{
			return "No new items to file.";
		}

		List<BankCategorizer.CategoryTab> newTabs = categorizer.categorize(fresh);
		List<String> owned = ownedTags();
		boolean structureChanged = false;

		// All tab gets every fresh item (it has no custom layout, so order doesn't matter).
		for (int id : fresh)
		{
			tagManager.addTag(id, ALL_TAG, false);
		}
		if (tabManager.find(ALL_TAG) == null)
		{
			ensureTab(ALL_TAG, mostValuable(bankItemIds));
			if (!owned.contains(ALL_TAG))
			{
				owned.add(0, ALL_TAG);
				structureChanged = true;
			}
		}

		for (BankCategorizer.CategoryTab ct : newTabs)
		{
			String tag = ct.category.label;
			List<Integer> catIds = flatten(ct.groups);
			for (int id : catIds)
			{
				tagManager.addTag(id, tag, false);
			}
			Layout existing = layoutManager.loadLayout(tag);
			if (existing == null)
			{
				layoutManager.saveLayout(new Layout(tag, packGroups(ct.groups)));
				ensureTab(tag, ct.iconItemId);
				if (!owned.contains(tag))
				{
					owned.add(tag);
					structureChanged = true;
				}
			}
			else
			{
				appendToLayout(tag, existing, catIds);   // preserve existing positions/edits
				ensureTab(tag, -1);                       // keep the user's icon
			}
		}

		filed.addAll(fresh);
		writeFiled(filed);

		if (structureChanged)
		{
			reorderLiveTabsFirst(owned);
			tabManager.save();
			configManager.setConfiguration(GROUP, KEY_OWNED, String.join(",", owned));
		}
		tabManager.save();
		log.debug("RuneTools: filed {} new item(s)", fresh.size());
		return "Filed " + fresh.size() + " new item" + (fresh.size() == 1 ? "" : "s") + " into their tabs.";
	}

	/** Find-or-create a tab for {@code tag}, refreshing its icon. Leaves an existing tab's position. */
	private void ensureTab(String tag, int icon)
	{
		TagTab tab = tabManager.find(tag);
		if (tab == null)
		{
			tab = new TagTab();
			tab.setTag(tag);
			tab.setIconItemId(icon);
			tabManager.add(tab);
		}
		else if (icon > 0)
		{
			tab.setIconItemId(icon);
		}
	}

	/** The most valuable item in the bank - used as the "All" tab's icon. */
	private int mostValuable(List<Integer> bankItemIds)
	{
		int bestId = -1;
		long bestPrice = -1;
		for (Integer raw : bankItemIds)
		{
			if (raw == null || raw <= 0)
			{
				continue;
			}
			int id = itemManager.canonicalize(raw);
			long price;
			try
			{
				price = itemManager.getItemPrice(id);
			}
			catch (RuntimeException ex)
			{
				price = 0;
			}
			if (price > bestPrice)
			{
				bestPrice = price;
				bestId = id;
			}
		}
		return bestId;
	}

	private static List<Integer> flatten(List<List<Integer>> rows)
	{
		List<Integer> out = new ArrayList<>();
		for (List<Integer> row : rows)
		{
			out.addAll(row);
		}
		return out;
	}

	/**
	 * Append {@code ids} onto a tab's existing layout without disturbing anything already placed:
	 * keep every occupied cell where it is, then add the new items on a fresh row after the last one.
	 */
	private void appendToLayout(String tag, Layout existing, List<Integer> ids)
	{
		int[] cur = existing.getLayout();
		int last = -1;
		for (int i = 0; i < cur.length; i++)
		{
			if (cur[i] > 0)
			{
				last = i;
			}
		}
		List<Integer> cells = new ArrayList<>();
		for (int i = 0; i <= last; i++)
		{
			cells.add(cur[i]);
		}
		while (cells.size() % COLS != 0)   // start the new items on a fresh row
		{
			cells.add(-1);
		}
		cells.addAll(ids);
		int[] out = new int[Math.max(cells.size(), COLS)];
		Arrays.fill(out, -1);
		for (int i = 0; i < cells.size(); i++)
		{
			out[i] = cells.get(i);
		}
		layoutManager.saveLayout(new Layout(tag, out));
	}

	private Set<Integer> readFiled()
	{
		Set<Integer> out = new HashSet<>();
		String raw = configManager.getConfiguration(GROUP, KEY_FILED);
		if (raw != null && !raw.trim().isEmpty())
		{
			for (String s : raw.split(","))
			{
				try
				{
					out.add(Integer.parseInt(s.trim()));
				}
				catch (NumberFormatException ignored)
				{
					// skip malformed entries
				}
			}
		}
		return out;
	}

	private void writeFiled(Set<Integer> ids)
	{
		StringBuilder sb = new StringBuilder();
		for (int id : ids)
		{
			if (sb.length() > 0)
			{
				sb.append(',');
			}
			sb.append(id);
		}
		configManager.setConfiguration(GROUP, KEY_FILED, sb.toString());
	}

	/** Delete every tab/layout/tag this organiser created, leaving the user's own tabs untouched. */
	void remove(Runnable onDone)
	{
		clientThread.invoke(() ->
		{
			clearOwned();
			tabManager.save();
			configManager.unsetConfiguration(GROUP, KEY_OWNED);
			configManager.unsetConfiguration(GROUP, KEY_FILED);
			String active = bankTags.getActiveTag();
			if (active != null)
			{
				bankTags.closeBankTag();
			}
			log.debug("RuneTools: removed organised bank tabs");
			if (onDone != null)
			{
				onDone.run();
			}
		});
	}

	/**
	 * Turn a native bank tab's exact contents (item ids in tab order, passed in by the caller) into a
	 * standalone Bank Tags tag + layout + tab named {@code name}. This is a user-owned tag - it is NOT
	 * tracked in {@link #KEY_OWNED}, so Rebuild/Remove never touch it - and it can be shared straight
	 * away via core Bank Tags' right-click "Export tag tab".
	 */
	void saveTabAsTag(String name, List<Integer> ids, Consumer<String> onStatus)
	{
		clientThread.invoke(() ->
		{
			if (ids.isEmpty())
			{
				if (onStatus != null)
				{
					onStatus.accept("That bank tab is empty - nothing to save.");
				}
				return;
			}
			String tag = name.trim();
			tagManager.removeTag(tag);                 // overwrite if a tag with this name already exists
			List<Integer> canon = new ArrayList<>();
			for (int id : ids)
			{
				int c = itemManager.canonicalize(id);
				tagManager.addTag(c, tag, false);
				canon.add(c);
			}
			int[] cells = new int[Math.max(canon.size(), COLS)];
			Arrays.fill(cells, -1);
			for (int i = 0; i < canon.size(); i++)
			{
				cells[i] = canon.get(i);               // dense, in tab order - mirrors the native tab
			}
			layoutManager.saveLayout(new Layout(tag, cells));
			ensureTab(tag, mostValuable(canon));
			tabManager.save();
			bankTags.openBankTag(tag, BankTagsService.OPTION_ALLOW_MODIFICATIONS);
			log.debug("RuneTools: saved bank tab as tag \"{}\" ({} items)", tag, canon.size());
			if (onStatus != null)
			{
				onStatus.accept("Saved as tag \"" + tag + "\". In the bank, right-click its tab "
					+ "-> Export tag tab to share it.");
			}
		});
	}

	/**
	 * Apply a layout imported from the site: create/refresh a Bank Tags tag + positional layout + tab
	 * named {@code name} from the given cells (index = grid position, value = item id, -1 = empty).
	 * A user-owned tag - NOT tracked in {@link #KEY_OWNED} - so Rebuild/Remove never touch it.
	 */
	void applyImportedLayout(String name, int icon, int[] cells)
	{
		clientThread.invoke(() ->
		{
			String base = sanitizeTagName(name);
			if (base.isEmpty() || cells == null || cells.length == 0)
			{
				return;
			}
			// Never destructively overwrite a tab the user (or the organiser) already owns: an
			// imported layout reusing an existing tag name gets a fresh, non-colliding name instead.
			String tag = uniqueTagName(base);
			// Cap grid size on untrusted imports (the caller also bounds it).
			int[] safe = cells.length > MAX_LAYOUT_CELLS ? Arrays.copyOf(cells, MAX_LAYOUT_CELLS) : cells.clone();
			int first = -1;
			tagManager.removeTag(tag);
			for (int i = 0; i < safe.length; i++)
			{
				int id = safe[i];
				if (id > 0 && plausibleItemId(id))
				{
					int c = itemManager.canonicalize(id);
					tagManager.addTag(c, tag, false);
					safe[i] = c;
					if (first < 0)
					{
						first = c;
					}
				}
				else
				{
					safe[i] = -1;   // drop implausible ids rather than write junk keys into banktags config
				}
			}
			layoutManager.saveLayout(new Layout(tag, safe));
			int safeIcon = (icon > 0 && plausibleItemId(icon)) ? icon : first;
			ensureTab(tag, safeIcon);
			tabManager.save();
			log.debug("RuneTools: applied imported layout \"{}\"", tag);
		});
	}

	/**
	 * Sanitise an untrusted (server/imported) tag name: drop control characters, the commas/newlines
	 * that delimit the {@code banktags.tagtabs}/{@code banktags.item_*} CSVs, and the angle brackets
	 * that would let it start Swing auto-HTML in a status label; cap the length.
	 */
	static String sanitizeTagName(String name)
	{
		if (name == null)
		{
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < name.length() && sb.length() < 40; i++)
		{
			char c = name.charAt(i);
			if (c >= 0x20 && c != 0x7f && c != ',' && c != '<' && c != '>')
			{
				sb.append(c);
			}
		}
		return sb.toString().trim();
	}

	/** A free tab name based on {@code base}, so applying an import never clobbers an existing tab. */
	private String uniqueTagName(String base)
	{
		if (tabManager.find(base) == null)
		{
			return base;
		}
		for (int i = 2; i < 100; i++)
		{
			String candidate = base + " (" + i + ")";
			if (tabManager.find(candidate) == null)
			{
				return candidate;
			}
		}
		return base;
	}

	/** Reject obviously-bogus item ids so imported data can't scatter junk keys into banktags config. */
	private static boolean plausibleItemId(int id)
	{
		return id > 0 && id <= 5_000_000;
	}

	private void clearOwned()
	{
		for (String tag : ownedTags())
		{
			tagManager.removeTag(tag);
			layoutManager.removeLayout(tag);
			tabManager.remove(tag);
		}
	}

	private List<String> ownedTags()
	{
		String raw = configManager.getConfiguration(GROUP, KEY_OWNED);
		if (raw == null || raw.trim().isEmpty())
		{
			return new ArrayList<>();
		}
		List<String> out = new ArrayList<>();
		for (String t : raw.split(","))
		{
			String s = t.trim();
			if (!s.isEmpty())
			{
				out.add(s);
			}
		}
		return out;
	}

	/**
	 * Row-major packing where each list in {@code rows} occupies its own grid row (wrapping if it
	 * holds more than {@link #COLS} items). An <em>empty</em> row is honoured as a full blank row, so
	 * the categoriser can use it as a visible separator between groups. Bank Tags treats -1 as empty.
	 */
	private static int[] packGroups(List<List<Integer>> rows)
	{
		List<Integer> cells = new ArrayList<>();
		for (List<Integer> row : rows)
		{
			while (cells.size() % COLS != 0)   // move to the start of the next row
			{
				cells.add(-1);
			}
			if (row == null || row.isEmpty())
			{
				for (int i = 0; i < COLS; i++)
				{
					cells.add(-1);             // a whole blank row as a separator
				}
			}
			else
			{
				cells.addAll(row);
			}
		}
		int[] out = new int[Math.max(cells.size(), COLS)];
		Arrays.fill(out, -1);
		for (int i = 0; i < cells.size(); i++)
		{
			out[i] = cells.get(i);
		}
		return out;
	}

	/**
	 * Move our category tabs to the front of core Bank Tags' <em>live</em> tab list. The tab bar
	 * renders from this list, and {@link TabManager#save()} re-serialises it - so leading the list is
	 * what keeps a later save from writing our tabs back to the end.
	 */
	private void reorderLiveTabsFirst(List<String> owned)
	{
		List<TagTab> live = tabManager.getTabs();
		List<TagTab> ours = new ArrayList<>();
		for (String tag : owned)
		{
			TagTab t = tabManager.find(tag);
			if (t != null)
			{
				ours.add(t);
			}
		}
		live.removeAll(ours);
		live.addAll(0, ours);
	}
}
