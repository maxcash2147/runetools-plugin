package com.runetools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Settings the site can hand the plugin ({@code GET /api/plugin/config}) so a problem can be eased
 * from the server side without a new release. Every field is optional and a plain value: there is no
 * code, no pattern and no id in it, and it can only make the plugin do less.
 *
 * <ul>
 *   <li>{@code features}: a flag set to {@code false} switches that sync off. Nothing here can switch
 *       on what the player turned off.</li>
 *   <li>{@code limits}: intervals in seconds, clamped so the server can only slow the plugin down.</li>
 *   <li>{@code paths}: where each request goes, accepted only under {@code /api/plugin/}.</li>
 *   <li>{@code delete_method}: {@code DELETE} (the default) or {@code POST} to {@code <path>/delete}.</li>
 *   <li>{@code message}: a short notice for the panel, shown once in chat.</li>
 *   <li>{@code min_version}: a build older than this pauses every write.</li>
 * </ul>
 *
 * <p>An answer that isn't a JSON object is ignored, so the last good copy (or the built-in defaults,
 * which are exactly the plugin's own behaviour) stays in force. Immutable and thread-safe.
 */
final class RemoteConfig
{
	static final String PATH = "/api/plugin/config";
	static final String PATH_PREFIX = "/api/plugin/";

	static final Set<String> FEATURES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
		"trades", "state", "bank", "ca", "clog", "clog_request", "achievements", "setups_pull",
		"setups_write", "layouts_apply", "layouts_publish", "live", "pairing", "web_login")));

	/** Limit name -> {built-in default, ceiling}, in milliseconds. */
	private static final Map<String, long[]> LIMITS = new LinkedHashMap<>();
	/** Logical request name -> built-in path. */
	private static final Map<String, String> PATHS = new LinkedHashMap<>();

	static
	{
		limit("sync_s", 20, 3600);           // background sweep
		limit("poll_min_s", 30, 3600);       // setups / layouts ETag poll, shortest gap
		limit("poll_max_s", 300, 86400);     // ... and the idle ceiling
		limit("panel_gap_s", 10, 3600);      // opening the side panel pulls a poll in, at most this often
		limit("bank_gap_s", 60, 3600);       // opening the bank does too
		limit("live_gap_s", 10, 3600);       // shortest gap between two live-update requests
		limit("backoff_max_s", 600, 86400);  // a failing endpoint waits at most this long
		limit("config_s", 1800, 86400);      // how often this config is fetched again
		limit("pair_poll_s", 3, 60);         // pairing poll while a connection is being approved

		PATHS.put("web_login", "/api/plugin/web-login");
		PATHS.put("pair", "/api/plugin/pair");
		PATHS.put("pair_poll", "/api/plugin/pair/poll");
		PATHS.put("changes", "/api/plugin/changes");
		PATHS.put("setups", "/api/plugin/setups");
		PATHS.put("trades", "/api/plugin/trades");
		PATHS.put("state", "/api/plugin/state");
		PATHS.put("bank_layouts", "/api/plugin/bank-layouts");
		PATHS.put("combat_achievements", "/api/plugin/combat-achievements");
		PATHS.put("collection_log", "/api/plugin/collection-log");
		PATHS.put("achievements", "/api/plugin/achievements");
	}

	private static void limit(String name, long defaultSeconds, long maxSeconds)
	{
		LIMITS.put(name, new long[]{defaultSeconds * 1000L, maxSeconds * 1000L});
	}

	private static final int MAX_TEXT = 200;
	private static final RemoteConfig DEFAULTS = new RemoteConfig(Collections.emptySet(), Collections.emptyMap(),
		Collections.emptyMap(), false, null, null, "{}");

	/** A notice for the panel. */
	static final class Message
	{
		final String id;
		final String level;   // info | warn | error
		final String text;
		final String link;    // a path on the configured server, or null

		Message(String id, String level, String text, String link)
		{
			this.id = id;
			this.level = level;
			this.text = text;
			this.link = link;
		}
	}

	private final Set<String> off;
	private final Map<String, Long> limits;
	private final Map<String, String> paths;
	private final boolean deleteViaPost;
	private final Message message;
	private final String minVersion;
	private final String raw;

	private RemoteConfig(Set<String> off, Map<String, Long> limits, Map<String, String> paths,
		boolean deleteViaPost, Message message, String minVersion, String raw)
	{
		this.off = off;
		this.limits = limits;
		this.paths = paths;
		this.deleteViaPost = deleteViaPost;
		this.message = message;
		this.minVersion = minVersion;
		this.raw = raw;
	}

	/** The plugin's own behaviour: what applies when the site has never answered. */
	static RemoteConfig defaults()
	{
		return DEFAULTS;
	}

	/** Parse an answer, or null when it isn't a JSON object (the caller keeps what it had). */
	static RemoteConfig parse(String json)
	{
		if (json == null || json.trim().isEmpty() || json.length() > 64 * 1024)
		{
			return null;
		}
		JsonObject o;
		try
		{
			JsonElement root = new JsonParser().parse(json);
			if (root == null || !root.isJsonObject())
			{
				return null;
			}
			o = root.getAsJsonObject();
		}
		catch (RuntimeException ex)
		{
			return null;
		}

		Set<String> off = new HashSet<>();
		JsonObject features = obj(o, "features");
		if (features != null)
		{
			for (String f : FEATURES)
			{
				JsonElement e = features.get(f);
				if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && !e.getAsBoolean())
				{
					off.add(f);
				}
			}
		}

		Map<String, Long> limits = new HashMap<>();
		JsonObject lim = obj(o, "limits");
		if (lim != null)
		{
			for (Map.Entry<String, long[]> en : LIMITS.entrySet())
			{
				JsonElement e = lim.get(en.getKey());
				if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber())
				{
					double seconds = e.getAsDouble();
					if (!Double.isNaN(seconds) && !Double.isInfinite(seconds))
					{
						limits.put(en.getKey(), clampMillis((long) Math.min(seconds * 1000d, 1e15),
							en.getValue()[0], en.getValue()[1]));
					}
				}
			}
		}

		Map<String, String> paths = new HashMap<>();
		boolean deleteViaPost = isPost(string(o, "delete_method"));
		JsonObject p = obj(o, "paths");
		if (p != null)
		{
			for (String name : PATHS.keySet())
			{
				String v = string(p, name);
				if (validPath(v))
				{
					paths.put(name, v);
				}
			}
			deleteViaPost |= isPost(string(p, "delete_method"));
		}

		return new RemoteConfig(Collections.unmodifiableSet(off), Collections.unmodifiableMap(limits),
			Collections.unmodifiableMap(paths), deleteViaPost, message(obj(o, "message")),
			version(string(o, "min_version")), json);
	}

	/** {@code remote} within [default, ceiling]: the server may only lengthen an interval. */
	static long clampMillis(long remote, long defaultMs, long maxMs)
	{
		return Math.max(defaultMs, Math.min(remote, maxMs));
	}

	/** A request path the server may point us at: under /api/plugin/, plain characters, no "..". */
	static boolean validPath(String path)
	{
		if (path == null || path.length() <= PATH_PREFIX.length() || path.length() > 120
			|| !path.startsWith(PATH_PREFIX) || path.contains("..") || path.contains("//"))
		{
			return false;
		}
		for (int i = 0; i < path.length(); i++)
		{
			char c = path.charAt(i);
			boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
				|| c == '/' || c == '-' || c == '_' || c == '.';
			if (!ok)
			{
				return false;
			}
		}
		return !path.endsWith("/");
	}

	/** A link a notice may carry: a path on the configured server, never another host or scheme. */
	static boolean validLink(String link)
	{
		if (link == null || link.isEmpty() || link.length() > MAX_TEXT || link.charAt(0) != '/'
			|| link.startsWith("//") || link.contains("..") || link.contains("\\"))
		{
			return false;
		}
		for (int i = 0; i < link.length(); i++)
		{
			char c = link.charAt(i);
			boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
				|| "/-_.?=&%#~+".indexOf(c) >= 0;
			if (!ok)
			{
				return false;
			}
		}
		return true;
	}

	boolean on(String feature)
	{
		return !off.contains(feature);
	}

	/** An interval in milliseconds: the built-in value unless the server asked for a longer one. */
	long limitMs(String name)
	{
		Long v = limits.get(name);
		if (v != null)
		{
			return v;
		}
		long[] d = LIMITS.get(name);
		return d != null ? d[0] : 0L;
	}

	/** Where a request goes: the built-in path unless the server named a valid one. */
	String path(String name)
	{
		String v = paths.get(name);
		return v != null ? v : PATHS.get(name);
	}

	boolean deleteViaPost()
	{
		return deleteViaPost;
	}

	Message message()
	{
		return message;
	}

	String minVersion()
	{
		return minVersion;
	}

	/** True when {@code version} is older than the server's {@code min_version}. */
	boolean tooOld(String version)
	{
		return minVersion != null && PluginVersion.compare(version, minVersion) < 0;
	}

	/** The answer as received, to cache. */
	String raw()
	{
		return raw;
	}

	private static Message message(JsonObject m)
	{
		if (m == null)
		{
			return null;
		}
		String text = clean(string(m, "text"), MAX_TEXT).trim();
		if (text.isEmpty())
		{
			return null;
		}
		String id = clean(string(m, "id"), 64).trim();
		if (id.isEmpty())
		{
			id = "t" + Integer.toHexString(text.hashCode());
		}
		String level = string(m, "level");
		level = "warn".equals(level) || "error".equals(level) ? level : "info";
		String link = string(m, "link");
		return new Message(id, level, text, validLink(link) ? link : null);
	}

	private static String version(String v)
	{
		if (v == null || v.isEmpty() || v.length() > 20)
		{
			return null;
		}
		for (int i = 0; i < v.length(); i++)
		{
			char c = v.charAt(i);
			if (!(c == '.' || (c >= '0' && c <= '9')))
			{
				return null;
			}
		}
		return v;
	}

	private static boolean isPost(String method)
	{
		return method != null && method.trim().equalsIgnoreCase("POST");
	}

	private static JsonObject obj(JsonObject o, String key)
	{
		JsonElement e = o.get(key);
		return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
	}

	private static String string(JsonObject o, String key)
	{
		JsonElement e = o.get(key);
		if (e == null || !e.isJsonPrimitive())
		{
			return null;
		}
		JsonPrimitive p = e.getAsJsonPrimitive();
		return p.isString() ? p.getAsString() : null;
	}

	/** Strip control and direction-override characters and cap the length. */
	static String clean(String s, int max)
	{
		if (s == null)
		{
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < s.length() && sb.length() < max; i++)
		{
			int c = s.charAt(i);
			boolean drop = (c < 0x20) || (c == 0x7f)
				|| (c >= 0x80 && c <= 0x9f)
				|| (c == 0x2028 || c == 0x2029)
				|| (c >= 0x202a && c <= 0x202e)
				|| (c >= 0x2066 && c <= 0x2069);
			if (!drop)
			{
				sb.append((char) c);
			}
		}
		return sb.toString();
	}
}
