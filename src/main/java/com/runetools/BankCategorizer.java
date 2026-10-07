package com.runetools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemEquipmentStats;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;

/**
 * Classifies the items currently in a player's bank into a handful of human categories, and - within
 * each category - into ordered <em>groups</em> that {@link BankOrganizer} lays out one-per-row so the
 * tab reads like a tidy, worn-kit-style arrangement rather than a value dump.
 *
 * <ul>
 *   <li><b>Gear</b> - grouped by combat style (Melee, Ranged, Magic), then a Jewelry group for
 *       combat jewelry, then Other; within a group items run in worn-slot order (helm -> cape ->
 *       body -> legs ...) so scanning top-to-bottom mirrors an equipment screen.</li>
 *   <li><b>Teleports</b> - teleport jewelry (glory, games necklace ...), then teleport tablets, then
 *       runes.</li>
 *   <li><b>Supplies</b> - potions, then food.</li>
 *   <li><b>Skilling</b> / <b>Misc</b> - single group, most-valuable-first.</li>
 * </ul>
 *
 * <p>It only reads cached item data ({@link ItemManager}) - it moves nothing - but callers should run
 * it on the client thread. Classification is deliberately heuristic; anything it can't place lands in
 * Misc rather than being hidden.
 */
class BankCategorizer
{
	/** Tabs are emitted in this order. Names double as the Bank Tags tag/tab labels. */
	enum Category
	{
		GEAR("Gear"),
		TELEPORTS("Teleports"),
		SUPPLIES("Supplies"),
		SKILLING("Skilling"),
		MISC("Misc");

		final String label;

		Category(String label)
		{
			this.label = label;
		}
	}

	/** A category's items, split into ordered groups (each laid out starting on a fresh row). */
	static class CategoryTab
	{
		final Category category;
		final int iconItemId;
		final List<List<Integer>> groups;

		CategoryTab(Category category, int iconItemId, List<List<Integer>> groups)
		{
			this.category = category;
			this.iconItemId = iconItemId;
			this.groups = groups;
		}
	}

	// Equipment slot indices (net.runelite.api.EquipmentInventorySlot#getSlotIdx).
	private static final int SLOT_AMULET = 2;
	private static final int SLOT_RING = 12;

	// Worn-silhouette order used to sort a gear group top-to-bottom. Index = equipment slot idx.
	private static final int[] SLOT_ORDER = new int[14];

	static
	{
		Arrays.fill(SLOT_ORDER, 90);
		SLOT_ORDER[0] = 0;    // head
		SLOT_ORDER[1] = 1;    // cape
		SLOT_ORDER[2] = 2;    // amulet
		SLOT_ORDER[4] = 3;    // body
		SLOT_ORDER[7] = 4;    // legs
		SLOT_ORDER[9] = 5;    // hands
		SLOT_ORDER[10] = 6;   // feet
		SLOT_ORDER[3] = 7;    // weapon
		SLOT_ORDER[5] = 8;    // shield
		SLOT_ORDER[12] = 9;   // ring
		SLOT_ORDER[13] = 10;  // ammo
	}

	private static final Pattern DOSE = Pattern.compile("\\([1-4]\\)$");

	// Teleport jewelry (equipable) - pulled into the Teleports tab; combat jewelry stays in Gear.
	private static final String[] TELEPORT_JEWELRY = {
		"amulet of glory", "eternal glory", "games necklace", "ring of dueling", "ring of duelling",
		"ring of wealth", "skills necklace", "digsite pendant", "necklace of passage", "burning amulet",
		"combat bracelet", "ring of returning", "ring of the elements", "slayer ring", "amulet of bounty",
		"xeric's talisman", "drakan's medallion", "pendant of ates"};

	// Non-equipable teleports that don't carry the word "teleport".
	private static final Set<String> TELEPORT_ITEMS = new HashSet<>(Arrays.asList(
		"ectophial", "royal seed pod", "teleport crystal", "enchanted lyre", "chronicle"));

	private static final Set<String> FOOD = new HashSet<>(Arrays.asList(
		"shark", "manta ray", "sea turtle", "anglerfish", "dark crab", "monkfish", "lobster",
		"tuna", "swordfish", "bass", "salmon", "trout", "cooked karambwan", "tuna potato",
		"pineapple pizza", "summer pie", "wild pie", "admiral pie", "curry", "stew",
		"cake", "chocolate cake", "jug of wine", "purple sweets", "peaches", "guthix rest tea"));

	private static final String[] SKILLING_KEYS = {
		"logs", " ore", " bar", "plank", "seed", "sapling", "grimy ", "clean ", "essence",
		"herb", "hide", "leather", "bones", "ashes", "uncut ", " gem", "feather", "arrowtip",
		"dart tip", "bolt tips", "javelin head", "spear head", "nails", "molten glass",
		"soda ash", "seaweed", "flax", "bow string", "bowstring", "ball of wool", "wool",
		"battlestaff", "compost", "bucket", "jug of water", "vial", "coal", "clay", "limestone",
		"tar", "raw ", "unfired", "(unf)", "amethyst", "pure essence", "rune essence",
		"dragon scale", "kebbit", "pyre", "mahogany", "teak", "willow", "maple", "yew",
		"redwood", "crushed", "potato cactus"};

	private final ItemManager itemManager;

	BankCategorizer(ItemManager itemManager)
	{
		this.itemManager = itemManager;
	}

	/** Bucket bank item ids into ordered category tabs (non-empty only, in {@link Category} order). */
	List<CategoryTab> categorize(List<Integer> bankItemIds)
	{
		// category -> (groupIndex -> items). TreeMap keeps groups in their intended top-to-bottom order.
		TreeMap<Category, TreeMap<Integer, List<Integer>>> buckets = new TreeMap<>();

		Set<Integer> seen = new HashSet<>();
		for (Integer raw : bankItemIds)
		{
			if (raw == null || raw <= 0)
			{
				continue;
			}
			int id = itemManager.canonicalize(raw);
			if (id <= 0 || !seen.add(id))
			{
				continue;
			}
			Placement p = classify(id);
			buckets.computeIfAbsent(p.category, k -> new TreeMap<>())
				.computeIfAbsent(p.group, k -> new ArrayList<>())
				.add(id);
		}

		List<CategoryTab> out = new ArrayList<>();
		for (Category cat : Category.values())
		{
			TreeMap<Integer, List<Integer>> groups = buckets.get(cat);
			if (groups == null || groups.isEmpty())
			{
				continue;
			}
			List<List<Integer>> rows = buildRows(cat, groups);
			int icon = -1;
			for (List<Integer> row : rows)
			{
				if (!row.isEmpty())
				{
					icon = row.get(0);
					break;
				}
			}
			out.add(new CategoryTab(cat, icon, rows));
		}
		return out;
	}

	/**
	 * Turn a category's groups into laid-out rows. Gear splits each style group into one row per
	 * equipment slot (helm row, cape row, body row ...) so it reads top-to-bottom like a worn kit;
	 * other categories use one wrapping row per group. A blank row separates consecutive groups.
	 */
	private List<List<Integer>> buildRows(Category cat, TreeMap<Integer, List<Integer>> groups)
	{
		List<List<Integer>> rows = new ArrayList<>();
		for (List<Integer> group : groups.values())
		{
			if (group.isEmpty())
			{
				continue;
			}
			if (!rows.isEmpty())
			{
				rows.add(new ArrayList<>());   // blank separator row between groups
			}
			if (cat == Category.GEAR)
			{
				TreeMap<Integer, List<Integer>> bySlot = new TreeMap<>();
				for (int id : group)
				{
					bySlot.computeIfAbsent(slotOrder(id), k -> new ArrayList<>()).add(id);
				}
				for (List<Integer> slotRow : bySlot.values())
				{
					slotRow.sort(Comparator.comparingLong((Integer id) -> price(id)).reversed()
						.thenComparing(this::name));
					rows.add(slotRow);         // one row per slot, best-first
				}
			}
			else
			{
				group.sort(Comparator.comparingLong((Integer id) -> price(id)).reversed()
					.thenComparing(this::name));
				rows.add(group);
			}
		}
		return rows;
	}

	// -- classification ------------------------------------------------------ //

	private static final class Placement
	{
		final Category category;
		final int group;

		Placement(Category category, int group)
		{
			this.category = category;
			this.group = group;
		}
	}

	// Gear group indices (worn kit reads Melee, Ranged, Magic, then jewelry, then everything else).
	private static final int G_MELEE = 0;
	private static final int G_RANGED = 1;
	private static final int G_MAGIC = 2;
	private static final int G_JEWELRY = 3;
	private static final int G_OTHER = 4;

	private Placement classify(int id)
	{
		ItemComposition comp = itemManager.getItemComposition(id);
		String name = comp != null && comp.getName() != null ? comp.getName().toLowerCase() : "";

		ItemStats stats = itemManager.getItemStats(id);
		ItemEquipmentStats eq = stats != null && stats.isEquipable() ? stats.getEquipment() : null;

		if (eq != null)
		{
			if (isTeleportJewelry(name))
			{
				return new Placement(Category.TELEPORTS, 0);   // teleport jewelry group
			}
			if (isJewelry(eq, name))
			{
				return new Placement(Category.GEAR, G_JEWELRY);
			}
			return new Placement(Category.GEAR, gearStyle(eq));
		}

		if (name.isEmpty())
		{
			return new Placement(Category.MISC, 0);
		}
		if (isTeleportTablet(name))
		{
			return new Placement(Category.TELEPORTS, 1);       // tablets group
		}
		if (isRune(name))
		{
			return new Placement(Category.TELEPORTS, 2);       // runes group
		}
		if (isPotion(name))
		{
			return new Placement(Category.SUPPLIES, 0);
		}
		if (isFood(name))
		{
			return new Placement(Category.SUPPLIES, 1);
		}
		if (isSkilling(name))
		{
			return new Placement(Category.SKILLING, 0);
		}
		return new Placement(Category.MISC, 0);
	}

	private static boolean isTeleportJewelry(String name)
	{
		for (String key : TELEPORT_JEWELRY)
		{
			if (name.contains(key))
			{
				return true;
			}
		}
		return false;
	}

	private static boolean isTeleportTablet(String name)
	{
		return name.contains("teleport") || TELEPORT_ITEMS.contains(name);
	}

	private static boolean isJewelry(ItemEquipmentStats eq, String name)
	{
		return eq.getSlot() == SLOT_AMULET || eq.getSlot() == SLOT_RING || name.contains("bracelet");
	}

	/** Classify a piece of gear as Melee/Ranged/Magic by its offensive+defensive bias, else Other. */
	private static int gearStyle(ItemEquipmentStats eq)
	{
		double melee = pos(eq.getAstab()) + pos(eq.getAslash()) + pos(eq.getAcrush())
			+ pos(eq.getStr()) * 2
			+ (pos(eq.getDstab()) + pos(eq.getDslash()) + pos(eq.getDcrush())) / 3.0;
		double ranged = pos(eq.getArange()) + pos(eq.getRstr()) * 2 + pos(eq.getDrange()) / 2.0;
		double magic = pos(eq.getAmagic()) + (eq.getMdmg() > 0 ? eq.getMdmg() * 100 : 0)
			+ pos(eq.getDmagic()) / 2.0;

		double best = Math.max(melee, Math.max(ranged, magic));
		if (best < 1.0)
		{
			return G_OTHER;   // capes/prayer/skilling equipables with no meaningful combat bias
		}
		if (best == ranged)
		{
			return G_RANGED;
		}
		if (best == magic)
		{
			return G_MAGIC;
		}
		return G_MELEE;
	}

	private static double pos(int v)
	{
		return v > 0 ? v : 0;
	}

	private static boolean isRune(String name)
	{
		return name.endsWith("rune") && !name.contains("essence");
	}

	private static boolean isPotion(String name)
	{
		return DOSE.matcher(name).find();
	}

	private static boolean isFood(String name)
	{
		return FOOD.contains(name) || (name.startsWith("cooked") && name.contains("karambwan"));
	}

	private static boolean isSkilling(String name)
	{
		for (String key : SKILLING_KEYS)
		{
			if (name.contains(key))
			{
				return true;
			}
		}
		return false;
	}

	// -- ordering helpers ---------------------------------------------------- //

	private int slotOrder(int id)
	{
		ItemStats stats = itemManager.getItemStats(id);
		if (stats == null || stats.getEquipment() == null)
		{
			return 99;
		}
		int slot = stats.getEquipment().getSlot();
		return slot >= 0 && slot < SLOT_ORDER.length ? SLOT_ORDER[slot] : 90;
	}

	private long price(int id)
	{
		try
		{
			return itemManager.getItemPrice(id);
		}
		catch (RuntimeException ex)
		{
			return 0;
		}
	}

	private String name(int id)
	{
		ItemComposition comp = itemManager.getItemComposition(id);
		return comp != null && comp.getName() != null ? comp.getName() : "";
	}
}
