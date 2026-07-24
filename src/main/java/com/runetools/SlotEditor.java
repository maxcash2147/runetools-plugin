package com.runetools;

import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Bridge the setups panel uses to touch the live game: open the in-game item search (the same
 * chatbox search Inventory Setups uses) and read the item currently in a live equipment/inventory
 * slot. All callbacks are delivered back on the Swing EDT so the panel can mutate its widgets.
 */
interface SlotEditor
{
	/** Open the in-game Item Search; {@code onPicked} gets the chosen (canonicalised) item id. */
	void searchItem(String tooltip, IntConsumer onPicked);

	/** Read the worn item at the given equipment slot index; {@code onItem} gets the id (-1 if empty). */
	void readEquipSlot(int equipIdx, IntConsumer onItem);

	/** Read the inventory item at the given index; {@code onItem} gets the id (-1 if empty). */
	void readInvSlot(int index, IntConsumer onItem);

	/** Resolve whether an item is a Dizana's quiver (drives the extra ammo slot). */
	void isQuiver(int itemId, Consumer<Boolean> onResult);
}
