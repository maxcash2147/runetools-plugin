package com.runetools;

import java.util.function.Consumer;

/**
 * Bridge the setups panel uses to touch the live game: open the in-game item search (the same
 * chatbox search Inventory Setups uses), read the item currently in a live equipment/inventory slot
 * and look up item names. All callbacks are delivered back on the Swing EDT so the panel can mutate
 * its widgets.
 */
interface SlotEditor
{
	/** An item and how many: {@code id} -1 for an empty slot. */
	interface ItemPick
	{
		void accept(int id, int qty);
	}

	/** Open the in-game Item Search; {@code onPicked} gets the chosen (canonicalised) item id. */
	void searchItem(String tooltip, ItemPick onPicked);

	/** Read the worn item at the given equipment slot index, with its quantity (ammo). */
	void readEquipSlot(int equipIdx, ItemPick onItem);

	/** Read the inventory item at the given index, with its quantity. */
	void readInvSlot(int index, ItemPick onItem);

	/** Resolve whether an item holds Dizana's quiver ammo (drives the extra ammo slot). */
	void isQuiver(int itemId, Consumer<Boolean> onResult);

	/** The item's name, for a slot's tooltip. */
	void itemName(int itemId, Consumer<String> onName);
}
