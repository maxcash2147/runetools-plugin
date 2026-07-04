package com.runetools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

/**
 * Thin wrapper over the RuneTools plugin API. Every request carries the user's Bearer key
 * (never the body, never a log line) and is dispatched asynchronously via OkHttp's
 * {@code enqueue}, so nothing here ever blocks the game/client thread.
 */
class RuneToolsApiClient
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	private final OkHttpClient http;
	private final RuneToolsConfig config;

	RuneToolsApiClient(OkHttpClient http, RuneToolsConfig config)
	{
		this.http = http;
		this.config = config;
	}

	String base()
	{
		String u = config.serverUrl();
		if (u == null || u.trim().isEmpty())
		{
			return "https://gecharts.com";
		}
		u = u.trim().replaceAll("/+$", "");
		// The consent disclosure promises the key travels over HTTPS. Upgrade any non-loopback
		// http:// so a password-equivalent key can never leave over cleartext; keep loopback http
		// so local/staging dev (http://127.0.0.1:8000) still works.
		String lower = u.toLowerCase();
		if (lower.startsWith("http://")
			&& !lower.startsWith("http://localhost")
			&& !lower.startsWith("http://127.0.0.1"))
		{
			u = "https://" + u.substring("http://".length());
		}
		return u;
	}

	private Request.Builder authed(String path)
	{
		return new Request.Builder()
			.url(base() + path)
			.header("Authorization", "Bearer " + config.apiKey())
			.header("Accept", "application/json");
	}

	/** Validate the key / fetch "Connected as …". */
	void me(Callback callback)
	{
		http.newCall(authed("/api/plugin/me").get().build()).enqueue(callback);
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

	void postState(AccountSnapshot s, Callback callback)
	{
		JsonObject body = new JsonObject();
		body.addProperty("rsn", s.rsn);
		if (s.accountHash != null)
		{
			body.addProperty("account_hash", s.accountHash);
		}
		body.addProperty("world", s.world);
		body.addProperty("members", s.members);
		body.addProperty("gp", s.gp);
		body.addProperty("free_slots", s.freeSlots);
		body.addProperty("ts", s.snapshotTsMillis);
		body.add("offers", s.offers);
		body.add("inventory", s.inventory);
		body.add("bank", s.bank);
		http.newCall(authed("/api/plugin/state")
			.post(RequestBody.create(JSON, body.toString())).build()).enqueue(callback);
	}
}
