package com.runetools;

/**
 * This build's version, sent on every request so the site can tell which plugin it is talking to
 * (and, through the remote config's {@code min_version}, pause an old one's writes). A unit test
 * keeps {@link #VERSION} equal to the version in runelite-plugin.properties.
 */
final class PluginVersion
{
	/** Must equal {@code version} in runelite-plugin.properties. */
	static final String VERSION = "1.0.0";

	/**
	 * The request and response shapes this build speaks. Bumped only when the plugin starts relying
	 * on a different shape, so the server can keep answering older builds the way they expect.
	 */
	static final int API_LEVEL = 1;

	static final String HEADER_VERSION = "X-RuneTools-Plugin";
	static final String HEADER_API = "X-RuneTools-Api";

	private PluginVersion()
	{
	}

	/**
	 * Compare two dotted numeric versions ("1.2.10" against "1.2.9"). Missing parts count as 0 and a
	 * part that isn't a number counts as 0, so a garbled value can never throw.
	 */
	static int compare(String a, String b)
	{
		String[] pa = a != null ? a.trim().split("\\.") : new String[0];
		String[] pb = b != null ? b.trim().split("\\.") : new String[0];
		int n = Math.max(pa.length, pb.length);
		for (int i = 0; i < n; i++)
		{
			long x = i < pa.length ? part(pa[i]) : 0;
			long y = i < pb.length ? part(pb[i]) : 0;
			if (x != y)
			{
				return x < y ? -1 : 1;
			}
		}
		return 0;
	}

	private static long part(String s)
	{
		int end = 0;
		while (end < s.length() && end < 9 && Character.isDigit(s.charAt(end)))
		{
			end++;
		}
		return end == 0 ? 0 : Long.parseLong(s.substring(0, end));
	}
}
