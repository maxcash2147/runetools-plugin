package com.runetools;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;

/**
 * Snapshots the player's live loadout (worn gear, inventory, rune pouch, quiver, spellbook) into a
 * {@link GearSetup}. All reads touch varbits/varps/enums/containers, so {@link #snapshot()} MUST be
 * called on the client thread.
 */
class LoadoutReader
{
	private static final Map<EquipmentInventorySlot, String> SLOT_KEY = new EnumMap<>(EquipmentInventorySlot.class);

	static
	{
		SLOT_KEY.put(EquipmentInventorySlot.HEAD, "head");
		SLOT_KEY.put(EquipmentInventorySlot.CAPE, "cape");
		SLOT_KEY.put(EquipmentInventorySlot.AMULET, "neck");
		SLOT_KEY.put(EquipmentInventorySlot.WEAPON, "weapon");
		SLOT_KEY.put(EquipmentInventorySlot.BODY, "body");
		SLOT_KEY.put(EquipmentInventorySlot.SHIELD, "shield");
		SLOT_KEY.put(EquipmentInventorySlot.LEGS, "legs");
		SLOT_KEY.put(EquipmentInventorySlot.GLOVES, "hands");
		SLOT_KEY.put(EquipmentInventorySlot.BOOTS, "feet");
		SLOT_KEY.put(EquipmentInventorySlot.RING, "ring");
		SLOT_KEY.put(EquipmentInventorySlot.AMMO, "ammo");
	}

	private static final int[] RP_TYPE = {
		VarbitID.RUNE_POUCH_TYPE_1, VarbitID.RUNE_POUCH_TYPE_2,
		VarbitID.RUNE_POUCH_TYPE_3, VarbitID.RUNE_POUCH_TYPE_4,
	};
	private static final int[] RP_QTY = {
		VarbitID.RUNE_POUCH_QUANTITY_1, VarbitID.RUNE_POUCH_QUANTITY_2,
		VarbitID.RUNE_POUCH_QUANTITY_3, VarbitID.RUNE_POUCH_QUANTITY_4,
	};

	private final Client client;
	private final ItemManager itemManager;

	LoadoutReader(Client client, ItemManager itemManager)
	{
		this.client = client;
		this.itemManager = itemManager;
	}

	/** Snapshot the current loadout. Client thread only. */
	GearSetup snapshot()
	{
		GearSetup s = new GearSetup();
		s.setSource("manual");

		// (1) worn equipment
		ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		Map<String, Integer> eq = new LinkedHashMap<>();
		Integer weaponId = null;
		if (worn != null)
		{
			Item[] w = worn.getItems();
			for (Map.Entry<EquipmentInventorySlot, String> e : SLOT_KEY.entrySet())
			{
				int idx = e.getKey().getSlotIdx();
				if (idx >= 0 && idx < w.length && w[idx].getId() > 0)
				{
					eq.put(e.getValue(), w[idx].getId());
					if (e.getKey() == EquipmentInventorySlot.WEAPON)
					{
						weaponId = w[idx].getId();
					}
				}
			}
		}
		// (2) 2h weapon -> no shield
		if (weaponId != null)
		{
			ItemStats st = itemManager.getItemStats(weaponId);
			if (st != null && st.getEquipment() != null && st.getEquipment().isTwoHanded())
			{
				eq.remove("shield");
				s.setTwoHanded(true);
			}
		}
		s.setEquipment(eq);

		// (3) inventory (positional: preserve the real slot layout, empty slots as blanks, trim the tail)
		ItemContainer inv = client.getItemContainer(InventoryID.INV);
		List<GearSetupItem> invList = new ArrayList<>();
		if (inv != null)
		{
			for (Item it : inv.getItems())
			{
				invList.add(it.getId() > 0
					? new GearSetupItem(it.getId(), Math.max(1, it.getQuantity()))
					: new GearSetupItem(0, 0));
			}
			while (!invList.isEmpty() && invList.get(invList.size() - 1).getItemId() <= 0)
			{
				invList.remove(invList.size() - 1);
			}
		}
		s.setInventory(invList);

		// (4) rune pouch (scan all 4 slots; empty slots have type 0)
		List<GearSetupItem> runes = new ArrayList<>();
		EnumComposition runeEnum = client.getEnum(982);   // RUNEPOUCH_RUNE enum: pouch-slot type -> rune item id
		if (runeEnum != null)
		{
			for (int i = 0; i < RP_TYPE.length; i++)
			{
				int type = client.getVarbitValue(RP_TYPE[i]);
				if (type == 0)
				{
					continue;
				}
				int runeId = runeEnum.getIntValue(type);
				if (runeId > 0)
				{
					runes.add(new GearSetupItem(runeId, Math.max(1, client.getVarbitValue(RP_QTY[i]))));
				}
			}
		}
		s.setRunePouch(runes);

		// (5) Dizana's quiver
		List<GearSetupItem> quiver = new ArrayList<>();
		int ammoId = client.getVarpValue(VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO);
		int ammoQty = client.getVarpValue(VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO_AMOUNT);
		if (ammoId > 0 && ammoQty > 0)
		{
			quiver.add(new GearSetupItem(ammoId, ammoQty));
		}
		s.setQuiver(quiver);

		// (6) spellbook  0 std | 1 ancient | 2 lunar | 3 arceuus
		int sb = client.getVarbitValue(VarbitID.SPELLBOOK);
		s.setSpellbook(sb >= 0 && sb <= 3 ? sb : -1);

		return s;
	}
}
