package com.runetools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;

/**
 * Pulls the user's gear setups from RuneTools, caches them (persisted in RuneLite's config so they
 * survive restarts) and tracks which one is "active". The cache and its ETag are kept per account
 * hash, so one character's setups are never shown, filtered or deleted as another's. Polling uses a
 * full pull with an {@code If-None-Match} ETag, so an unchanged server returns 304. How often it polls
 * is the plugin's call ({@link PollSchedule}).
 *
 * <p>Every write reports its own result through {@link Host#message}, with the server's words when it
 * gave any; a write that failed pulls the server's copy again, so a refused edit doesn't stay on
 * screen as if it had been saved.
 */
@Slf4j
class SetupManager
{
	private static final String KEY_SETUPS = "setups";
	private static final String KEY_ETAG = "setupsEtag";
	private static final int MAX_SETUPS = 500;   // cap on how many (untrusted) setups we parse/keep
	private static final long IMPORT_GAP_MS = 400L;   // between imported setups: ~150 a minute at most

	/** What the manager needs from the plugin. */
	interface Host
	{
		/** Whether a background pull may go out now (signed in, consent, not backing off, not switched off). */
		boolean mayPull();

		/** Whether a write may go out; when not, the host has already said why in the panel. */
		boolean mayWrite();

		/** A pull's answer, for the plugin's sync health (status 0 = no answer). */
		void pulled(int status, String body, boolean confirmed);

		/** The result of something the player did (saved, deleted, imported, refused). */
		void message(String text, boolean ok);

		/** The setup list changed. */
		void changed(List<GearSetup> setups);

		/** A setup was removed on the site: drop its bank tag and layout. */
		void deleted(long id);

		/** A poll found the setups changed on the site since the last one. */
		void remoteChanged();
	}

	private final DebouncedConfig store;
	private final RuneToolsApiClient api;
	private final Host host;
	private final ScheduledExecutorService executor;

	private volatile String account;   // account hash the list belongs to; null before a character is in game
	private volatile List<GearSetup> setups = Collections.emptyList();
	private volatile String etag;
	private volatile long activeId = -1;
	private volatile boolean closed;

	// One update PUT in flight at a time; edits that arrive during it coalesce (latest wins per setup)
	// so two quick edits can't race on the wire and clobber each other. Each edit is serialised when it
	// is made, on the thread that made it, so a later edit can't change what an earlier one sends.
	private final Object pushGate = new Object();
	private boolean updateInFlight;
	private final Map<Long, JsonObject> pendingUpdates = new LinkedHashMap<>();

	SetupManager(DebouncedConfig store, RuneToolsApiClient api, Host host, ScheduledExecutorService executor)
	{
		this.store = store;
		this.api = api;
		this.host = host;
		this.executor = executor;
	}

	/** Stop for good (the plugin was switched off). */
	void close()
	{
		closed = true;
	}

	/** True when a poll sent with {@code sent} came back with a different, real ETag. */
	static boolean etagChanged(String sent, String received)
	{
		return sent != null && !sent.isEmpty() && received != null && !received.equals(sent);
	}

	static String setupsKey(String accountHash)
	{
		return KEY_SETUPS + "_" + accountHash;
	}

	static String etagKey(String accountHash)
	{
		return KEY_ETAG + "_" + accountHash;
	}

	/**
	 * The character in game is now {@code accountHash} (null: nobody). Shows that character's cached
	 * setups and forgets the previous one's; nothing is deleted for it.
	 */
	void switchAccount(String accountHash)
	{
		String now = accountHash != null && !accountHash.isEmpty() ? accountHash : null;
		if (now == null ? account == null : now.equals(account))
		{
			return;
		}
		synchronized (pushGate)
		{
			pendingUpdates.clear();   // edits queued for the previous character's setups
		}
		account = now;
		activeId = -1;
		load(now);
		host.changed(setups);
	}

	List<GearSetup> getSetups()
	{
		return setups;
	}

	GearSetup getActive()
	{
		long id = activeId;
		for (GearSetup s : setups)
		{
			if (s.getId() == id)
			{
				return s;
			}
		}
		return null;
	}

	void setActive(long id)
	{
		activeId = id;
	}

	void clearActive()
	{
		activeId = -1;
	}

	/** Ask the site for this account's setups (an ETag poll). Gated by {@link Host#mayPull}. */
	void poll()
	{
		final String forAccount = account;
		if (closed || forAccount == null || !host.mayPull())
		{
			return;
		}
		final String sentEtag = etag;
		api.getSetups(sentEtag, new Callback()
		{
			@Override
			public void onFailure(Call call, IOException ex)
			{
				if (!closed)
				{
					host.pulled(0, null, false);
				}
				log.debug("RuneTools: setups sync failed (will retry): {}", ex.getMessage());
			}

			@Override
			public void onResponse(Call call, Response resp)
			{
				try (Response r = resp)
				{
					if (closed || !forAccount.equals(account))
					{
						return;   // switched off, or another character is in game now
					}
					if (r.code() == 304)
					{
						host.pulled(304, null, true);
						return;
					}
					String body = RuneToolsApiClient.bodyOrEmpty(r);
					boolean confirmed = r.isSuccessful()
						&& SyncResponse.confirms(SyncResponse.Shape.SETUPS, body, 0);
					host.pulled(r.code(), body, confirmed);
					if (!confirmed)
					{
						log.debug("RuneTools: setups sync HTTP {} (not applied)", r.code());
						return;   // never wipe the list over an answer that isn't a setups list
					}
					apply(forAccount, sentEtag, new JsonParser().parse(body).getAsJsonObject());
				}
				catch (Exception ex)
				{
					log.debug("RuneTools: could not parse setups: {}", ex.getMessage());
				}
			}
		});
	}

	private void apply(String forAccount, String sentEtag, JsonObject o)
	{
		JsonArray arr = o.getAsJsonArray("setups");
		List<GearSetup> parsed = parseList(arr);
		// Diff old vs new ids so setups removed on the site drop their bank tag + layout.
		Set<Long> removed = new HashSet<>();
		for (GearSetup s : setups)
		{
			removed.add(s.getId());
		}
		for (GearSetup s : parsed)
		{
			removed.remove(s.getId());
		}
		setups = Collections.unmodifiableList(parsed);
		for (Long id : removed)
		{
			host.deleted(id);
		}
		String received = o.has("etag") && !o.get("etag").isJsonNull() ? safeString(o.get("etag")) : null;
		if (received != null)
		{
			etag = received;
		}
		persist(forAccount, arr);
		host.changed(setups);
		if (etagChanged(sentEtag, received))
		{
			host.remoteChanged();
		}
		log.debug("RuneTools: {} gear setup(s) synced", parsed.size());
	}

	/** Save a new setup on the site. {@code s} is serialised now, on the caller's thread. */
	void pushCreate(GearSetup s)
	{
		if (closed || !host.mayWrite())
		{
			return;
		}
		final String name = displayName(s);
		final JsonObject body = s.toJson();
		host.message("Saving \"" + name + "\"…", true);
		api.createSetup(body, new Callback()
		{
			@Override
			public void onFailure(Call call, IOException ex)
			{
				host.message(SyncResponse.describe(0, null, "Couldn't save \"" + name + "\"."), false);
			}

			@Override
			public void onResponse(Call call, Response resp)
			{
				try (Response r = resp)
				{
					String b = RuneToolsApiClient.bodyOrEmpty(r);
					if (r.isSuccessful() && SyncResponse.confirms(SyncResponse.Shape.SETUP, b, 0))
					{
						host.message("Saved \"" + name + "\".", true);
					}
					else
					{
						host.message(SyncResponse.describe(r.code(), b, "Couldn't save \"" + name + "\"."), false);
					}
				}
				refresh();
			}
		});
	}

	/**
	 * Save an edit. {@code s} is shown at once and serialised now, on the caller's thread (the Swing
	 * thread for panel edits), so nothing done to it afterwards changes what is sent.
	 */
	void pushUpdate(GearSetup s)
	{
		if (closed || !host.mayWrite())
		{
			revert();   // put the last copy from the site back over an edit that won't be sent
			return;
		}
		final JsonObject body = s.toJson();
		replaceLocal(s);   // reflect the edit in the panel immediately; the poll after the PUT reconciles
		synchronized (pushGate)
		{
			if (updateInFlight)
			{
				pendingUpdates.put(s.getId(), body);
				return;
			}
			updateInFlight = true;
		}
		sendUpdate(s.getId(), body);
	}

	/**
	 * Optimistically swap the matching cached setup for {@code s} and notify listeners, so a "Refresh
	 * from game" shows in the open detail view without waiting for the server round-trip. The
	 * following {@link #poll()} overwrites it with the server's copy. In-place slot edits mutate the
	 * cached object itself and the panel already shows them, so those skip the re-render.
	 */
	private void replaceLocal(GearSetup s)
	{
		List<GearSetup> copy = new ArrayList<>(setups);
		for (int i = 0; i < copy.size(); i++)
		{
			GearSetup existing = copy.get(i);
			if (existing.getId() == s.getId())
			{
				if (existing == s)
				{
					return;
				}
				copy.set(i, s);
				setups = Collections.unmodifiableList(copy);
				host.changed(setups);
				return;
			}
		}
	}

	private void sendUpdate(long id, JsonObject body)
	{
		final String name = body.has("name") ? safeString(body.get("name")) : "Setup";
		api.updateSetup(id, body, new Callback()
		{
			@Override
			public void onFailure(Call call, IOException ex)
			{
				try
				{
					log.debug("RuneTools: setup update failed: {}", ex.getMessage());
					host.message(SyncResponse.describe(0, null, "Couldn't save \"" + name + "\"."), false);
					revert();   // show what the site really has
				}
				finally
				{
					afterUpdate();
				}
			}

			@Override
			public void onResponse(Call call, Response resp)
			{
				try (Response r = resp)
				{
					String b = RuneToolsApiClient.bodyOrEmpty(r);
					if (!(r.isSuccessful() && SyncResponse.confirms(SyncResponse.Shape.SETUP, b, 0)))
					{
						log.debug("RuneTools: setup update HTTP {}", r.code());
						host.message(SyncResponse.describe(r.code(), b, "Couldn't save \"" + name + "\"."), false);
						revert();
					}
					else
					{
						refresh();   // the panel then shows the site's copy
					}
				}
				finally
				{
					afterUpdate();
				}
			}
		});
	}

	private void afterUpdate()
	{
		Long nextId = null;
		JsonObject next = null;
		synchronized (pushGate)
		{
			Iterator<Map.Entry<Long, JsonObject>> it = pendingUpdates.entrySet().iterator();
			if (it.hasNext() && !closed)
			{
				Map.Entry<Long, JsonObject> en = it.next();
				nextId = en.getKey();
				next = en.getValue();
				it.remove();
			}
			else
			{
				updateInFlight = false;
			}
		}
		if (next != null)
		{
			sendUpdate(nextId, next);
		}
	}

	void pushDelete(long id)
	{
		if (closed || !host.mayWrite())
		{
			return;
		}
		api.deleteSetup(id, new Callback()
		{
			@Override
			public void onFailure(Call call, IOException ex)
			{
				host.message(SyncResponse.describe(0, null, "Couldn't delete the setup."), false);
				revert();
			}

			@Override
			public void onResponse(Call call, Response resp)
			{
				try (Response r = resp)
				{
					String b = RuneToolsApiClient.bodyOrEmpty(r);
					if (r.isSuccessful() && SyncResponse.confirms(SyncResponse.Shape.DELETED, b, 0))
					{
						host.message("Deleted.", true);
					}
					else
					{
						host.message(SyncResponse.describe(r.code(), b, "Couldn't delete the setup."), false);
					}
				}
				refresh();
			}
		});
	}

	/**
	 * Save setups imported from Inventory Setups, one request at a time and a little apart (the site
	 * allows about 240 plugin requests a minute from one address), then pull once. Stops at a rate
	 * limit, a full setup list or a rejected key, and reports what was really saved.
	 */
	void importMany(List<GearSetup> list)
	{
		if (closed || list.isEmpty() || !host.mayWrite())
		{
			return;
		}
		List<JsonObject> bodies = new ArrayList<>();
		for (GearSetup g : list)
		{
			bodies.add(g.toJson());
		}
		host.message("Importing " + bodies.size() + " setup" + (bodies.size() == 1 ? "" : "s") + "…", true);
		importNext(bodies, 0, 0, 0);
	}

	private void importNext(List<JsonObject> bodies, int index, int created, int failed)
	{
		if (closed)
		{
			return;
		}
		if (index >= bodies.size())
		{
			finishImport(bodies.size(), created, failed, null);
			return;
		}
		api.createSetup(bodies.get(index), new Callback()
		{
			@Override
			public void onFailure(Call call, IOException ex)
			{
				next(created, failed + 1);
			}

			@Override
			public void onResponse(Call call, Response resp)
			{
				String stop = null;
				boolean ok;
				try (Response r = resp)
				{
					String b = RuneToolsApiClient.bodyOrEmpty(r);
					ok = r.isSuccessful() && SyncResponse.confirms(SyncResponse.Shape.SETUP, b, 0);
					if (!ok && (r.code() == 429 || r.code() == 409 || r.code() == 401 || r.code() == 403))
					{
						stop = SyncResponse.describe(r.code(), b, "Import stopped.");
					}
				}
				if (stop != null)
				{
					finishImport(bodies.size(), created, failed, stop);
					return;
				}
				next(ok ? created + 1 : created, ok ? failed : failed + 1);
			}

			private void next(int c, int f)
			{
				if (closed)
				{
					return;
				}
				if (executor != null)
				{
					executor.schedule(() -> importNext(bodies, index + 1, c, f), IMPORT_GAP_MS, TimeUnit.MILLISECONDS);
				}
				else
				{
					importNext(bodies, index + 1, c, f);
				}
			}
		});
	}

	private void finishImport(int total, int created, int failed, String stoppedBecause)
	{
		if (closed)
		{
			return;
		}
		host.message(importSummary(total, created, failed, stoppedBecause), created == total);
		if (created > 0)
		{
			refresh();
		}
	}

	/** "Imported 3 setups." or "Imported 2 of 5. 1 failed. <why it stopped>" */
	static String importSummary(int total, int created, int failed, String stoppedBecause)
	{
		if (created == total)
		{
			return "Imported " + total + " setup" + (total == 1 ? "" : "s") + ".";
		}
		StringBuilder sb = new StringBuilder("Imported ").append(created).append(" of ").append(total).append('.');
		if (failed > 0)
		{
			sb.append(' ').append(failed).append(" failed.");
		}
		if (stoppedBecause != null)
		{
			sb.append(' ').append(stoppedBecause);
		}
		return sb.toString();
	}

	/**
	 * A write was refused or never sent: show the last copy the site gave (the cached one, which edits
	 * made in the panel haven't touched), then pull a fresh one.
	 */
	private void revert()
	{
		if (closed)
		{
			return;
		}
		String forAccount = account;
		load(forAccount);
		host.changed(setups);
		refresh();
	}

	/** Pull the server's copy now, bypassing the ETag (after a write). */
	private void refresh()
	{
		if (closed)
		{
			return;
		}
		etag = null;
		poll();
	}

	private static String displayName(GearSetup s)
	{
		return s.getName() != null && !s.getName().trim().isEmpty() ? s.getName().trim() : "Setup";
	}

	private void persist(String forAccount, JsonArray arr)
	{
		store.set(setupsKey(forAccount), arr != null ? arr.toString() : "[]");
		store.set(etagKey(forAccount), etag);
	}

	/** Read one account's cache. Its ETag is trusted only when the cached list itself parsed. */
	private void load(String forAccount)
	{
		etag = null;
		setups = Collections.emptyList();
		if (forAccount == null)
		{
			return;
		}
		try
		{
			String raw = store.get(setupsKey(forAccount));
			if (raw == null || raw.isEmpty())
			{
				return;
			}
			JsonElement root = new JsonParser().parse(raw);
			if (!root.isJsonArray())
			{
				return;
			}
			setups = Collections.unmodifiableList(parseList(root.getAsJsonArray()));
			etag = store.get(etagKey(forAccount));
		}
		catch (Exception ex)
		{
			setups = Collections.emptyList();
			etag = null;
			log.debug("RuneTools: could not load cached setups: {}", ex.getMessage());
		}
	}

	private static List<GearSetup> parseList(JsonArray arr)
	{
		List<GearSetup> parsed = new ArrayList<>();
		if (arr == null)
		{
			return parsed;
		}
		for (JsonElement e : arr)
		{
			if (parsed.size() >= MAX_SETUPS)
			{
				break;
			}
			if (!e.isJsonObject())
			{
				continue;
			}
			try
			{
				GearSetup g = GearSetup.fromJson(e.getAsJsonObject());
				if (g.getId() > 0)   // skip a malformed/id-less element rather than drop the batch
				{
					parsed.add(g);
				}
			}
			catch (RuntimeException ex)
			{
				log.debug("RuneTools: skipped a malformed setup: {}", ex.getMessage());
			}
		}
		return parsed;
	}

	private static String safeString(JsonElement e)
	{
		try
		{
			return e.getAsString();
		}
		catch (RuntimeException ex)
		{
			return null;
		}
	}
}
