package com.runetools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Thin wrapper over the RuneTools plugin API. Every request carries the user's Bearer key
 * (never the body, never a log line) and is dispatched asynchronously via OkHttp's
 * {@code enqueue}, so nothing here ever blocks the game/client thread.
 */
class RuneToolsApiClient
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	/** Never buffer more than this from a response - bounds a hostile/huge body (memory-exhaustion DoS). */
	static final long MAX_RESPONSE_BYTES = 4L * 1024 * 1024;

	private final OkHttpClient http;
	private final RuneToolsConfig config;
	// Resolves the API key for the CURRENTLY logged-in character (per-account keys). Defaults to the
	// legacy single config key; the plugin swaps in its per-account-hash resolver at startup.
	private Supplier<String> keySupplier;

	RuneToolsApiClient(OkHttpClient http, RuneToolsConfig config)
	{
		this.http = http;
		this.config = config;
		this.keySupplier = config::apiKey;
	}

	/** Point the client at the plugin's per-character key resolver. */
	void setKeySupplier(Supplier<String> keySupplier)
	{
		this.keySupplier = keySupplier;
	}

	/** The active character's key, or "" (an empty key reads as "no key" server-side). */
	String activeKey()
	{
		String k = keySupplier != null ? keySupplier.get() : null;
		return k != null ? k : "";
	}

	String base()
	{
		String u = config.serverUrl();
		if (u == null || u.trim().isEmpty())
		{
			return "https://runetools.com";
		}
		u = u.trim().replaceAll("/+$", "");
		// The consent disclosure promises the key travels over HTTPS - enforce it for EVERY host,
		// localhost included. A password-equivalent key must never leave over cleartext, so any
		// http:// is upgraded to https://. Run a local dev server over TLS (see the README /
		// mkcert) and point the plugin at https://localhost:5173.
		if (u.length() >= 7 && u.regionMatches(true, 0, "http://", 0, 7))
		{
			u = "https://" + u.substring(7);
		}
		return u;
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

	/** Read a response body without ever buffering more than {@link #MAX_RESPONSE_BYTES}. */
	static String readBoundedBody(Response r) throws IOException
	{
		if (r == null || r.body() == null)
		{
			return "";
		}
		return r.peekBody(MAX_RESPONSE_BYTES).string();
	}

	/** A request that carries no key (pairing is how a client without a working key gets one). */
	private Request.Builder plain(String path)
	{
		return new Request.Builder()
			.url(base() + path)
			.header("Accept", "application/json");
	}

	private Request.Builder authed(String path)
	{
		return new Request.Builder()
			.url(base() + path)
			.header("Authorization", "Bearer " + activeKey())
			.header("Accept", "application/json");
	}

	/**
	 * Mint a one-time browser-login code so the user can open the site already signed in - creating a
	 * site account on first sight (keyed by the game account hash) when they have no key yet. The
	 * request carries the Bearer key if one is stored; an empty key reads as "no key" server-side.
	 * Response: {@code {code, url, api_key?}}.
	 */
	void webLogin(String accountHash, String rsn, Callback callback)
	{
		JsonObject body = new JsonObject();
		if (accountHash != null)
		{
			body.addProperty("account_hash", accountHash);
		}
		body.addProperty("rsn", rsn);
		http.newCall(authed("/api/plugin/web-login")
			.post(RequestBody.create(JSON, body.toString())).build()).enqueue(callback);
	}

	/**
	 * Start pairing this client with an account it can't enter on its own (one secured with an
	 * email, which web-login refuses). Response:
	 * {@code {poll_token, user_code, url, expires_in, interval}}; the player approves user_code at
	 * <site>{url} while signed in, and {@link #pairPoll} then hands back this client's key.
	 */
	void pairStart(String accountHash, String rsn, Callback callback)
	{
		JsonObject body = new JsonObject();
		if (accountHash != null)
		{
			body.addProperty("account_hash", accountHash);
		}
		body.addProperty("rsn", rsn);
		http.newCall(plain("/api/plugin/pair")
			.post(RequestBody.create(JSON, body.toString())).build()).enqueue(callback);
	}

	/**
	 * Poll a pairing: {@code {status: "pending"}} until the player approves it, then once
	 * {@code {status: "approved", api_key}}; HTTP 410 once it expired or was used.
	 */
	void pairPoll(String pollToken, Callback callback)
	{
		JsonObject body = new JsonObject();
		body.addProperty("poll_token", pollToken);
		http.newCall(plain("/api/plugin/pair/poll")
			.post(RequestBody.create(JSON, body.toString())).build()).enqueue(callback);
	}

	/** Pull the user's gear setups (full list). A matching {@code etag} yields a cheap 304. */
	void getSetups(String etag, Callback callback)
	{
		Request.Builder b = authed("/api/plugin/setups?since=0");
		if (etag != null && !etag.isEmpty())
		{
			b.header("If-None-Match", etag);
		}
		http.newCall(b.get().build()).enqueue(callback);
	}

	/** Create a setup on the site from the plugin (two-way sync). */
	void createSetup(GearSetup s, Callback callback)
	{
		http.newCall(authed("/api/plugin/setups")
			.post(RequestBody.create(JSON, s.toJson().toString())).build()).enqueue(callback);
	}

	/** Update a setup on the site. */
	void updateSetup(long id, GearSetup s, Callback callback)
	{
		http.newCall(authed("/api/plugin/setups/" + id)
			.put(RequestBody.create(JSON, s.toJson().toString())).build()).enqueue(callback);
	}

	/** Delete a setup on the site. */
	void deleteSetup(long id, Callback callback)
	{
		http.newCall(authed("/api/plugin/setups/" + id).delete().build()).enqueue(callback);
	}

	void postTrades(String rsn, String accountHash, List<Trade> trades, Callback callback)
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
		http.newCall(authed("/api/plugin/trades")
			.post(RequestBody.create(JSON, body.toString())).build()).enqueue(callback);
	}

	/** Publish a bank layout to the user's runetools.com profile; response carries {code, url}. */
	void postBankLayout(JsonObject body, Callback callback)
	{
		http.newCall(authed("/api/plugin/bank-layouts")
			.post(RequestBody.create(JSON, body.toString())).build()).enqueue(callback);
	}

	/** Push the CA catalog + this account's completion to the site's planner. */
	void postCombatAchievements(JsonObject body, Callback callback)
	{
		http.newCall(authed("/api/plugin/combat-achievements")
			.post(RequestBody.create(JSON, body.toString())).build()).enqueue(callback);
	}

	/** Push collection-log totals + the pages read this session (server accumulates the replica). */
	void postCollectionLog(JsonObject body, Callback callback)
	{
		http.newCall(authed("/api/plugin/collection-log")
			.post(RequestBody.create(JSON, body.toString())).build()).enqueue(callback);
	}

	/** Push this session's combat-timing totals ({@link CombatTiming#report()} plus identity). */
	void postTiming(JsonObject body, Callback callback)
	{
		http.newCall(authed("/api/plugin/timing")
			.post(RequestBody.create(JSON, body.toString())).build()).enqueue(callback);
	}

	/** Pull the user's bank layouts (incl. ones imported on the site). A matching {@code etag} -> 304. */
	void getBankLayouts(String etag, Callback callback)
	{
		Request.Builder b = authed("/api/plugin/bank-layouts?since=0");
		if (etag != null && !etag.isEmpty())
		{
			b.header("If-None-Match", etag);
		}
		http.newCall(b.get().build()).enqueue(callback);
	}

	/**
	 * The {@code /state} body for a snapshot, without the capture timestamp ({@code ts}): that differs
	 * on every send, and leaving it to the caller lets two sends of the same state be compared.
	 *
	 * <p>Every field here is one the server stores; anything it would only throw away is not sent.
	 * Keep this in step with the README's "What it sends" table and the consent text.
	 */
	static JsonObject stateBody(AccountSnapshot s)
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
		if (s.bank != null)
		{
			body.add("bank", s.bank);
		}
		// Same preserve-if-absent contract for skills.
		if (s.skills != null)
		{
			body.add("skills", s.skills);
		}
		return body;
	}

	/** Post a {@link #stateBody} with its {@code ts} stamped in. */
	void postState(JsonObject body, Callback callback)
	{
		http.newCall(authed("/api/plugin/state")
			.post(RequestBody.create(JSON, body.toString())).build()).enqueue(callback);
	}

}
