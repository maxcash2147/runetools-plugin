package com.runetools;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Local dev launcher: starts a RuneLite client with this plugin side-loaded.
 * Run with {@code ./gradlew run}.
 */
public class RuneToolsPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(RuneToolsPlugin.class);
		RuneLite.main(args);
	}
}
