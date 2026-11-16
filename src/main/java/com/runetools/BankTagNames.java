package com.runetools;

import java.util.Locale;

/**
 * Every Bank Tags tag, tab and layout this plugin adds, changes or deletes is named with {@link #PREFIX},
 * so it can never touch a tag the player made (Plugin Hub rule). Bank Tags keeps tag names lower case.
 */
final class BankTagNames
{
	static final String PREFIX = "runetools_";

	private BankTagNames()
	{
	}

	/** Our tag for {@code name}: the prefix, then the name. */
	static String of(String name)
	{
		return PREFIX + name;
	}

	/** Whether {@code tag} is one of ours (so this plugin may change or delete it). */
	static boolean ours(String tag)
	{
		return tag != null && tag.toLowerCase(Locale.ROOT).startsWith(PREFIX);
	}

	/** {@code tag} without our prefix, for a name the player types or reads. */
	static String shown(String tag)
	{
		return ours(tag) ? tag.substring(PREFIX.length()) : tag;
	}
}
