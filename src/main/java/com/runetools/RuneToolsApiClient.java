package com.runetools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Thin wrapper over the RuneTools plugin API. Every request carries the plugin's version headers and,
 * where it needs one, a character's Bearer key (never in the body, never in a log line), and is
 * dispatched asynchronously via OkHttp's {@code enqueue}, so nothing here ever blocks the game/client
 * thread.
 *
 * <p>One client lives for one run of the plugin. {@link #close} cancels everything still in flight and
 * drops any answer that arrives afterwards, so a request sent before the plugin was switched off can
 * never act once it is off (or after it is switched back on, which makes a new client).
 */
class RuneToolsApiClient
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	static final String DEFAULT_SERVER = "https://runetools.com";
	static final String BAD_URL_MESSAGE = "The Server URL setting isn't a valid web address.";

	/** Never buffer more than this from a response - bounds a hostile/huge body (memory-exhaustion DoS). */
	static final long MAX_RESPONSE_BYTES = 4L * 1024 * 1024;

	private final OkHttpClient http;
	// The same client, allowed to wait longer for an answer: the live-update request is held open by
	// the site for up to 50 seconds on purpose.
	private final OkHttpClient longPoll;
	private final RuneToolsConfig config;
	private final Supplier<RemoteConfig> remote;
	// Resolves the API key for the CURRENTLY logged-in character (per-account keys).
	private volatile Supplier<String> keySupplier = () -> null;

	private volatile boolean closed;
	private final Set<Call> inFlight = ConcurrentHashMap.newKeySet();
	private volatile Call liveCall;

	RuneToolsApiClient(OkHttpClient http, RuneToolsConfig config, Supplier<RemoteConfig> remote)
	{
		this.http = http;
		this.longPoll = http.newBuilder().readTimeout(80, TimeUnit.SECONDS).build();
		this.config = config;
		this.remote = remote != null ? remote : RemoteConfig::defaults;
	}

	/** Point the client at the plugin's per-character key resolver. */
	void setKeySupplier(Supplier<String> keySupplier)
	{
		this.keySupplier = keySupplier != null ? keySupplier : () -> null;
	}

	/** The active character's key, or "" (an empty key reads as "no key" server-side). */
	String activeKey()
	{
		String k = keySupplier.get();
		return k != null ? k : "";
	}

	/** Stop for good: cancel what is in flight and ignore every answer still to come. */
	void close()
	{
		closed = true;
		for (Call c : inFlight)
		{
			c.cancel();
		}
		inFlight.clear();
	}

	/** Cancel the live-update request, if one is waiting (a character switch or logout). */
	void cancelLive()
	{
		Call c = liveCall;
		if (c != null)
		{
			c.cancel();
		}
	}

	/**
	 * The server's base URL. The saved setting is normalised (see {@link #normalizeServerUrl}); an
	 * unusable one gives {@link #DEFAULT_SERVER} here, for display, while {@link #validBase} says the
	 * requests themselves must not go out.
	 */
	String base()
	{
		String b = validBase();
		return b != null ? b : DEFAULT_SERVER;
	}

	/** The normalised server URL, or null when the setting is not a usable https address. */
	String validBase()
	{
		return normalizeServerUrl(config.serverUrl());
	}

	/**
	 * Normalise a Server URL setting: blank means the default; a bare host gets {@code https://}; an
	 * {@code http://} one is upgraded (the key must never travel over cleartext, localhost included);
	 * the old gecharts.com address and www.runetools.com become https://runetools.com; trailing slashes
	 * go. Anything else that isn't a plain https URL (another scheme, a query, junk) gives null.
	 */
	static String normalizeServerUrl(String raw)
	{
		if (raw == null || raw.trim().isEmpty())
		{
			return DEFAULT_SERVER;
		}
		String u = raw.trim();
		while (u.endsWith("/"))
		{
			u = u.substring(0, u.length() - 1);
		}
		int scheme = u.indexOf("://");
		if (scheme < 0)
		{
			if (u.contains(":") && !u.matches("^[A-Za-z0-9.\\-]+:\\d+(/.*)?$"))
			{
				return null;   // "javascript:..." and the like, not a host with a port
			}
			u = "https://" + u;
		}
		else if (u.regionMatches(true, 0, "http://", 0, 7))
		{
			u = "https://" + u.substring(7);
		}
		else if (!u.regionMatches(true, 0, "https://", 0, 8))
		{
			return null;
		}
		HttpUrl parsed = HttpUrl.parse(u);
		if (parsed == null || parsed.query() != null || parsed.fragment() != null || u.contains("@"))
		{
			return null;
		}
		String host = parsed.host().toLowerCase(Locale.ROOT);
		boolean plain = parsed.port() == 443 && ("/".equals(parsed.encodedPath()) || parsed.encodedPath().isEmpty());
		if (plain && (host.equals("gecharts.com") || host.equals("www.gecharts.com") || host.equals("www.runetools.com")))
		{
			return DEFAULT_SERVER;
		}
		String out = parsed.toString();
		while (out.endsWith("/"))
		{
			out = out.substring(0, out.length() - 1);
		}
		return out;
	}

	/** The bare host of a URL (no scheme, userinfo, port, path), or "" if it can't be parsed. */
	static String hostOf(String url)
	{
		if (url == null)
		{
			return "";
		}
		int scheme = url.indexOf("://");
		String rest = scheme >= 0 ? url.substring(scheme + 3) : url;
		int slash = rest.indexOf('/');
		if (slash >= 0)
		{
			rest = rest.substring(0, slash);
		}
		int at = rest.lastIndexOf('@');   // strip userinfo
		if (at >= 0)
		{
			rest = rest.substring(at + 1);
		}
		if (rest.startsWith("["))         // IPv6 literal: [::1]:port
		{
			int rb = rest.indexOf(']');
			return rb >= 0 ? rest.substring(1, rb) : rest.substring(1);
		}
		int colon = rest.indexOf(':');    // strip port
		if (colon >= 0)
		{
			rest = rest.substring(0, colon);
		}
		return rest;
	}

	/**
	 * True only for genuine loopback hosts. Used to vet the (untrusted) host of a server-supplied
	 * share URL, so the 127.0.0.0/8 test validates four numeric octets - a bare
	 * {@code startsWith("127.")} would wrongly accept "127.evil.com" or "127.0.0.1.attacker.example".
	 */
	static boolean isLoopbackHost(String host)
	{
		if (host == null || host.isEmpty())
		{
			return false;
		}
		String h = host.toLowerCase();
		if (h.equals("localhost") || h.equals("::1") || h.equals("0:0:0:0:0:0:0:1"))
		{
			return true;
		}
		String[] octets = h.split("\\.");
		if (octets.length != 4)
		{
			return false;
		}
		try
		{
			if (Integer.parseInt(octets[0]) != 127)
			{
				return false;
			}
			for (int i = 1; i < 4; i++)
			{
				int o = Integer.parseInt(octets[i]);
				if (o < 0 || o > 255)
				{
					return false;
				}
			}
			return true;
		}
		catch (NumberFormatException ex)
		{
			return false;   // non-numeric label is not loopback
		}
	}

	/** Read a response body without ever buffering more than {@link #MAX_RESPONSE_BYTES}. Re-readable. */
	static String readBoundedBody(Response r) throws IOException
	{
		if (r == null || r.body() == null)
		{
			return "";
		}
		return r.peekBody(MAX_RESPONSE_BYTES).string();
	}

	/** Like {@link #readBoundedBody}, but "" instead of an exception (an error body is best-effort). */
	static String bodyOrEmpty(Response r)
	{
		try
		{
			return readBoundedBody(r);
		}
		catch (IOException | RuntimeException ex)
		{
			return "";
		}
	}

	private String path(String name)
	{
		return remote.get().path(name);
	}

	/** A request with the version headers and no key (pairing is how a client without one gets one). */
	private Request.Builder plain(String path)
	{
		String b = validBase();
		if (b == null)
		{
			throw new IllegalArgumentException(BAD_URL_MESSAGE);
		}
		return new Request.Builder()
			.url(b + path)
			.header("Accept", "application/json")
			.header(PluginVersion.HEADER_VERSION, PluginVersion.VERSION)
			.header(PluginVersion.HEADER_API, String.valueOf(PluginVersion.API_LEVEL));
	}

	private Request.Builder authed(String path)
	{
		return authed(path, activeKey());
	}

	private Request.Builder authed(String path, String key)
	{
		return plain(path).header("Authorization", "Bearer " + (key != null ? key : ""));
	}

	private interface RequestMaker
	{
		Request make();
	}

	/**
	 * Send a request. A request that can't even be built (an unusable Server URL) fails through the
	 * callback at once, so a caller's in-flight flag is always released; after {@link #close} nothing
	 * is sent and no callback runs.
	 */
	private void send(OkHttpClient client, RequestMaker maker, Callback callback, boolean live)
	{
		if (closed)
		{
			return;
		}
		Request req;
		try
		{
			req = maker.make();
		}
		catch (RuntimeException ex)
		{
			callback.onFailure(null, new IOException(BAD_URL_MESSAGE));
			return;
		}
		Call call = client.newCall(req);
		inFlight.add(call);
		if (closed)
		{
			// close() ran while this was being built: it may have missed the call, so it never leaves.
			inFlight.remove(call);
			return;
		}
		if (live)
		{
			liveCall = call;
		}
		call.enqueue(new Callback()
		{
			@Override
			public void onFailure(Call c, IOException ex)
			{
				inFlight.remove(c);
				if (!closed)
				{
					callback.onFailure(c, ex);
				}
			}

			@Override
			public void onResponse(Call c, Response r) throws IOException
			{
				inFlight.remove(c);
				if (closed)
				{
					r.close();
					return;
				}
				callback.onResponse(c, r);
			}
		});
	}

	private static RequestBody json(String body)
	{
		return RequestBody.create(JSON, body);
	}

	/** The site's settings for the plugin (see {@link RemoteConfig}). Optional: a 404 changes nothing. */
	void getConfig(Callback callback)
	{
		send(http, () -> plain(RemoteConfig.PATH).get().build(), callback, false);
	}

	/**
	 * Wait for setups or bank layouts saved on the site: the server answers as soon as either differs
	 * from the versions given (the ones its last answer named; empty the first time), or with an
	 * empty 204 after about 50 seconds. Response: {@code {setups, layouts, setups_tag, layouts_tag}},
	 * and {@code config_tag} once the site has a remote config.
	 */
	void waitForChanges(String setupsTag, String layoutsTag, String configTag, Callback callback)
	{
		String q = "?setups=" + enc(setupsTag) + "&layouts=" + enc(layoutsTag) + "&config=" + enc(configTag);
		send(longPoll, () -> authed(path("changes") + q).get().build(), callback, true);
	}

	private static String enc(String s)
	{
		return URLEncoder.encode(s != null ? s : "", StandardCharsets.UTF_8);
	}

	/**
	 * Mint a one-time browser-login code for the account this character's stored key opens, so the
	 * user can open the site already signed in. It never creates an account or hands out a key: with
	 * no working key for this character the site answers 403 {@code code: "pair"} (connect it by
	 * {@link #pairStart pairing}), and for an account with an email 403 {@code code: "email_signin"}.
	 * {@code next} is the site path to land on once signed in (null for the home page). Response:
	 * {@code {code, url}}.
	 */
	void webLogin(String accountHash, String rsn, String next, Callback callback)
	{
		JsonObject body = new JsonObject();
		if (accountHash != null)
		{
			body.addProperty("account_hash", accountHash);
		}
		body.addProperty("rsn", rsn);
		if (next != null)
		{
			body.addProperty("next", next);
		}
		send(http, () -> authed(path("web_login")).post(json(body.toString())).build(), callback, false);
	}

	/**
	 * Start pairing: how a character with no working key here gets connected. Sent without a key.
	 * Response: {@code {poll_token, user_code, url, expires_in, interval}}; a person approves
	 * user_code at <site>{url} in the browser, and {@link #pairPoll} then hands back this
	 * character's key.
	 */
	void pairStart(String accountHash, String rsn, Callback callback)
	{
		JsonObject body = new JsonObject();
		if (accountHash != null)
		{
			body.addProperty("account_hash", accountHash);
		}
		body.addProperty("rsn", rsn);
		send(http, () -> plain(path("pair")).post(json(body.toString())).build(), callback, false);
	}

	/**
	 * Poll a pairing: {@code {status: "pending"}} until the player approves it, then once
	 * {@code {status: "approved", api_key}}; HTTP 410 once it expired or was used.
	 */
	void pairPoll(String pollToken, Callback callback)
	{
		JsonObject body = new JsonObject();
		body.addProperty("poll_token", pollToken);
		send(http, () -> plain(path("pair_poll")).post(json(body.toString())).build(), callback, false);
	}

	/** Pull the user's gear setups (full list). A matching {@code etag} yields a cheap 304. */
	void getSetups(String etag, Callback callback)
	{
		send(http, () ->
		{
			Request.Builder b = authed(path("setups") + "?since=0");
			if (etag != null && !etag.isEmpty())
			{
				b.header("If-None-Match", etag);
			}
			return b.get().build();
		}, callback, false);
	}

	/** Create a setup on the site from the plugin (two-way sync). */
	void createSetup(JsonObject setup, Callback callback)
	{
		String body = setup.toString();
		send(http, () -> authed(path("setups")).post(json(body)).build(), callback, false);
	}

	/** Update a setup on the site. */
	void updateSetup(long id, JsonObject setup, Callback callback)
	{
		String body = setup.toString();
		send(http, () -> authed(path("setups") + "/" + id).put(json(body)).build(), callback, false);
	}

	/** Delete a setup on the site ({@code DELETE}, or {@code POST .../delete} when the site asks for it). */
	void deleteSetup(long id, Callback callback)
	{
		boolean viaPost = remote.get().deleteViaPost();
		send(http, () -> viaPost
			? authed(path("setups") + "/" + id + "/delete").post(json("{}")).build()
			: authed(path("setups") + "/" + id).delete().build(), callback, false);
	}

	/** Upload fills with the key of the character that made them (see {@link TradeGroups}). */
	void postTrades(String key, String rsn, String accountHash, List<Trade> trades, Callback callback)
	{
		JsonArray arr = new JsonArray();
		for (Trade t : trades)
		{
			JsonObject o = new JsonObject();
			o.addProperty("item_id", t.getItemId());
			o.addProperty("side", t.getSide());
			o.addProperty("qty", t.getQty());
			o.addProperty("price", t.getPrice());
			o.addProperty("ts", t.getTsMillis());
			o.addProperty("ext_id", t.getExtId());
			arr.add(o);
		}
		JsonObject body = new JsonObject();
		body.addProperty("rsn", rsn);
		if (accountHash != null)
		{
			body.addProperty("account_hash", accountHash);
		}
		body.add("trades", arr);
		String s = body.toString();
		send(http, () -> authed(path("trades"), key).post(json(s)).build(), callback, false);
	}

	/** Publish a bank layout to the user's runetools.com profile; response carries {code, url}. */
	void postBankLayout(JsonObject body, Callback callback)
	{
		String s = body.toString();
		send(http, () -> authed(path("bank_layouts")).post(json(s)).build(), callback, false);
	}

	/** Push the CA catalog + this account's completion to the site's planner. */
	void postCombatAchievements(JsonObject body, Callback callback)
	{
		String s = body.toString();
		send(http, () -> authed(path("combat_achievements")).post(json(s)).build(), callback, false);
	}

	/** Push collection-log totals + the pages read this session (server accumulates the replica). */
	void postCollectionLog(JsonObject body, Callback callback)
	{
		String s = body.toString();
		send(http, () -> authed(path("collection_log")).post(json(s)).build(), callback, false);
	}

	/** Push achievements for the Adventurer's Log: {@code {rsn, account_hash, events: [...]}}. */
	void postAchievements(JsonObject body, Callback callback)
	{
		String s = body.toString();
		send(http, () -> authed(path("achievements")).post(json(s)).build(), callback, false);
	}

	/** Pull the user's bank layouts (incl. ones imported on the site). A matching {@code etag} -> 304. */
	void getBankLayouts(String etag, Callback callback)
	{
		send(http, () ->
		{
			Request.Builder b = authed(path("bank_layouts") + "?since=0");
			if (etag != null && !etag.isEmpty())
			{
				b.header("If-None-Match", etag);
			}
			return b.get().build();
		}, callback, false);
	}

	/**
	 * The {@code /state} body for a snapshot, without the capture timestamp ({@code ts}): that differs
	 * on every send, and leaving it to the caller lets two sends of the same state be compared.
	 * {@code includeBank} false leaves the bank out, which tells the server to keep the one it has.
	 *
	 * <p>Every field here is one the server stores; anything it would only throw away is not sent.
	 * Keep this in step with the README's "What it sends" table and the consent text.
	 */
	static JsonObject stateBody(AccountSnapshot s, boolean includeBank)
	{
		JsonObject body = new JsonObject();
		body.addProperty("rsn", s.rsn);
		if (s.accountHash != null)
		{
			body.addProperty("account_hash", s.accountHash);
		}
		body.addProperty("members", s.members);
		body.addProperty("gp", s.gp);
		body.addProperty("free_slots", s.freeSlots);
		body.add("offers", s.offers);
		body.add("inventory", s.inventory);
		// Only send the bank once it's captured; omitting it tells the server to keep the last one.
		if (includeBank && s.bank != null)
		{
			body.add("bank", s.bank);
		}
		// Worn equipment likewise.
		if (s.equipment != null)
		{
			body.add("equipment", s.equipment);
		}
		// Same preserve-if-absent contract for skills.
		if (s.skills != null)
		{
			body.add("skills", s.skills);
		}
		return body;
	}

	/** {@link #stateBody(AccountSnapshot, boolean)} with the bank, when captured. */
	static JsonObject stateBody(AccountSnapshot s)
	{
		return stateBody(s, true);
	}

	/** Post a {@link #stateBody} with its {@code ts} stamped in. */
	void postState(JsonObject body, Callback callback)
	{
		String s = body.toString();
		send(http, () -> authed(path("state")).post(json(s)).build(), callback, false);
	}
}
