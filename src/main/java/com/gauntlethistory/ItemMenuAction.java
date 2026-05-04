package com.gauntlethistory;

import net.runelite.api.Item;

class ItemMenuAction
{
	final Item[] oldInventory;

	ItemMenuAction(Item[] oldInventory)
	{
		this.oldInventory = oldInventory;
	}

	static class ItemAction extends ItemMenuAction
	{
		final int itemID;
		final int slot;

		ItemAction(Item[] oldInventory, int itemID, int slot)
		{
			super(oldInventory);
			this.itemID = itemID;
			this.slot = slot;
		}
	}
}
