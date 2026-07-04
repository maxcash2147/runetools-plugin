package com.runetools;

import lombok.Value;

/** One item in a setup's inventory / rune pouch / quiver. */
@Value
public class GearSetupItem
{
	int itemId;
	int qty;
}
