package com.runetools;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.banktags.BankTagsService;
import net.runelite.client.plugins.banktags.TagManager;
import net.runelite.client.plugins.banktags.tabs.Layout;
import net.runelite.client.plugins.banktags.tabs.LayoutManager;

/**
 * Filters and (optionally) LAYS OUT the in-game bank to a gear setup, the way Inventory Setups
 * does: build a hidden Bank Tag ({@code _rtsetup_<id>}) of the setup's item ids, save a Bank Tags
 * {@link Layout} placing equipment on the left and inventory on the right, then open the tag with
 * that layout. Core Bank Tags does the actual hiding/positioning and re-applies it across redraws.
 *
 * <p>All Bank Tags coupling lives here; every call runs on the client thread.
 */
@Slf4j
class BankFilterController
{
	static final String TAG_PREFIX = "_rtsetup_";

	// Bank is 8 columns wide: pos = row*8 + col. Equipment sits in cols 0-2 (RS worn-slot shape),
	// inventory in cols 4-7 (col 3 is a gutter).
	private static final Map<String, Integer> EQUIP_POS = new HashMap<>();

	static
	{
		EQUIP_POS.put("head", 1);
		EQUIP_POS.put("cape", 8);
		EQUIP_POS.put("neck", 9);
		EQUIP_POS.put("ammo", 10);
		EQUIP_POS.put("weapon", 16);
		EQUIP_POS.put("body", 17);
		EQUIP_POS.put("shield", 18);
		EQUIP_POS.put("legs", 25);
		EQUIP_POS.put("hands", 32);
		EQUIP_POS.put("feet", 33);
		EQUIP_POS.put("ring", 34);
	}

	private static int invPos(int i)
	{
		return (i / 4) * 8 + 4 + (i % 4);
	}

	private final BankTagsService bankTags;
	private final TagManager tagManager;
	private final LayoutManager layoutManager;
	private final ItemManager itemManager;
	private final ClientThread clientThread;

	BankFilterController(BankTagsService bankTags, TagManager tagManager, LayoutManager layoutManager,
						 ItemManager itemManager, ClientThread clientThread)
	{
		this.bankTags = bankTags;
		this.tagManager = tagManager;
		this.layoutManager = layoutManager;
		this.itemManager = itemManager;
		this.clientThread = clientThread;
	}

	/** Tag the setup's items, optionally save a positional layout, and open the tag. */
	void filter(GearSetup setup, boolean fuzzy, boolean useLayout)
	{
		if (setup == null)
		{
			return;
		}
		clientThread.invoke(() ->
		{
			String tag = TAG_PREFIX + setup.getId();
			tagManager.removeTag(tag);                 // clear stale membership
			int n = 0;
			for (int id : setup.filterItemIds())
			{
				if (id > 0)
				{
					// Per-item fuzzy (like Inventory Setups) OR the global toggle.
					tagManager.addTag(id, tag, fuzzy || setup.isFuzzy(id));
					n++;
				}
			}
			tagManager.setHidden(tag, true);

			int opts = BankTagsService.OPTION_ALLOW_MODIFICATIONS
				| BankTagsService.OPTION_HIDE_TAG_NAME;
			if (useLayout)
			{
				saveLayout(setup, tag);                // open WITH the layout (no OPTION_NO_LAYOUT)
			}
			else
			{
				layoutManager.removeLayout(tag);       // drop any stale layout
				opts |= BankTagsService.OPTION_NO_LAYOUT;
			}
			bankTags.openBankTag(tag, opts);
			log.debug("RuneTools: bank filter -> \"{}\" ({} items, layout={})", setup.getName(), n, useLayout);
		});
	}

	private void saveLayout(GearSetup setup, String tag)
	{
		List<GearSetupItem> inv = setup.getInventory() != null ? setup.getInventory() : Collections.emptyList();
		int max = 34;                                  // last equipment cell (ring)
		for (int i = 0; i < inv.size(); i++)
		{
			max = Math.max(max, invPos(i));
		}
		int[] cells = new int[max + 1];
		Arrays.fill(cells, -1);                        // -1 == empty cell

		if (setup.getEquipment() != null)
		{
			for (Map.Entry<String, Integer> e : setup.getEquipment().entrySet())
			{
				Integer pos = EQUIP_POS.get(e.getKey());
				Integer id = e.getValue();
				if (pos != null && id != null && id > 0 && pos < cells.length)
				{
					cells[pos] = itemManager.canonicalize(id);
				}
			}
		}
		for (int i = 0; i < inv.size(); i++)
		{
			int pos = invPos(i);
			if (pos < cells.length && inv.get(i).getItemId() > 0)
			{
				cells[pos] = itemManager.canonicalize(inv.get(i).getItemId());
			}
		}
		layoutManager.saveLayout(new Layout(tag, cells));
	}

	/** Cancel our filter - only if OUR tag is the active one (don't clobber another plugin's). */
	void clear()
	{
		clientThread.invoke(() ->
		{
			String active = bankTags.getActiveTag();
			if (active != null && active.startsWith(TAG_PREFIX))
			{
				bankTags.closeBankTag();
			}
		});
	}

	/** Drop a setup's tag membership AND its saved layout (e.g. when deleted on the site). */
	void forget(long setupId)
	{
		clientThread.invoke(() ->
		{
			String tag = TAG_PREFIX + setupId;
			tagManager.removeTag(tag);
			layoutManager.removeLayout(tag);
		});
	}
}
