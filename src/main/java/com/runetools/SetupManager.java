package com.runetools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;

/**
 * Pulls the user's gear setups from RuneTools, caches them (persisted via ConfigManager so they
 * survive restarts), and tracks which one is "active". Polling uses a full pull with an
 * {@code If-None-Match} ETag, so an unchanged server returns 304.
 */
@Slf4j
class SetupManager
{
	private static final String GROUP = RuneToolsConfig.GROUP;
	private static final String KEY_SETUPS = "setups";
	private static final String KEY_ETAG = "setupsEtag";
	private static final int MAX_SETUPS = 500;   // cap on how many (untrusted) setups we parse/keep

	private final ConfigManager configManager;
	private final RuneToolsApiClient api;

	private volatile List<GearSetup> setups = new ArrayList<>();
	private volatile String etag;
	private volatile long activeId = -1;
	private volatile Runnable onChange = () -> { };
	private volatile Consumer<Long> onDelete = id -> { };
	private volatile Consumer<String> onError = m -> { };

	// One update PUT in flight at a time; edits that arrive during it coalesce (latest wins per setup)
	// so two quick edits can't race on the wire and clobber each other.
	private final Object pushGate = new Object();
	private boolean updateInFlight;
	private final Map<Long, GearSetup> pendingUpdates = new HashMap<>();

	SetupManager(ConfigManager configManager, RuneToolsApiClient api)
	{
		this.configManager = configManager;
		this.api = api;
		load();
	}

	void setOnChange(Runnable r)
	{
		this.onChange = r != null ? r : () -> { };
	}

	void setOnDelete(Consumer<Long> c)
	{
		this.onDelete = c != null ? c : id -> { };
	}

	void setOnError(Consumer<String> c)
	{
		this.onError = c != null ? c : m -> { };
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

	void poll()
	{
		api.getSetups(etag, new Callback()
		{
			@Override
			public void onFailure(Call call, IOException ex)
			{
				log.debug("RuneTools: setups sync failed (will retry): {}", ex.getMessage());
			}

			@Override
			public void onResponse(Call call, Response resp)
			{
				try (Response r = resp)
				{
					if (r.code() == 304)
					{
						return;   // unchanged
					}
					if (r.code() == 401)
					{
						return;   // handled by other paths / panel status
					}
					if (!r.isSuccessful())
					{
						log.debug("RuneTools: setups sync HTTP {}", r.code());
						return;
					}
					String body = RuneToolsApiClient.readBoundedBody(r);
					JsonObject o = new JsonParser().parse(body).getAsJsonObject();
					JsonArray arr = o.getAsJsonArray("setups");
					List<GearSetup> parsed = new ArrayList<>();
					if (arr != null)
					{
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
					}
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
					setups = parsed;
					for (Long id : removed)
					{
						onDelete.accept(id);
					}
					if (o.has("etag") && !o.get("etag").isJsonNull())
					{
						etag = o.get("etag").getAsString();
					}
					persist(arr);
					onChange.run();
					log.debug("RuneTools: {} gear setup(s) synced", parsed.size());
				}
				catch (Exception ex)
				{
					log.debug("RuneTools: could not parse setups: {}", ex.getMessage());
				}
			}
		});
	}

	void pushCreate(GearSetup s)
	{
		api.createSetup(s, refreshCallback("create"));
	}

	void pushUpdate(GearSetup s)
	{
		replaceLocal(s);   // reflect the edit in the panel immediately; the poll after the PUT reconciles
		synchronized (pushGate)
		{
			if (updateInFlight)
			{
				pendingUpdates.put(s.getId(), s);
				return;
			}
			updateInFlight = true;
		}
		sendUpdate(s);
	}

	/**
	 * Optimistically swap the matching cached setup for {@code s} and notify listeners, so a "Refresh
	 * from game" shows in the open detail view without waiting for the server round-trip (and even
	 * while briefly offline). The subsequent {@link #poll()} overwrites this with the authoritative
	 * server copy; latest-wins, so a lost race just self-heals next poll. In-place slot edits mutate
	 * the cached object itself (same reference) and the panel already reflects them, so those skip the
	 * re-render - only a genuine object swap (refresh) needs to notify.
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
					return;   // in-place edit of the cached object; the panel already shows it
				}
				copy.set(i, s);
				setups = copy;
				onChange.run();
				return;
			}
		}
	}

	private void sendUpdate(GearSetup s)
	{
		api.updateSetup(s.getId(), s, new Callback()
		{
			@Override
			public void onFailure(Call call, IOException ex)
			{
				log.debug("RuneTools: setup update failed: {}", ex.getMessage());
				onError.accept("Setup sync failed (offline?)");
				afterUpdate();
			}

			@Override
			public void onResponse(Call call, Response resp)
			{
				try (Response r = resp)
				{
					if (r.isSuccessful())
					{
						etag = null;   // force a fresh pull so the panel reflects the write
						poll();
					}
					else
					{
						log.debug("RuneTools: setup update HTTP {}", r.code());
						onError.accept("Setup save rejected (HTTP " + r.code() + ")");
					}
				}
				afterUpdate();
			}
		});
	}

	private void afterUpdate()
	{
		GearSetup next = null;
		synchronized (pushGate)
		{
			Iterator<GearSetup> it = pendingUpdates.values().iterator();
			if (it.hasNext())
			{
				next = it.next();
				it.remove();
			}
			else
			{
				updateInFlight = false;
			}
		}
		if (next != null)
		{
			sendUpdate(next);
		}
	}

	void pushDelete(long id)
	{
		api.deleteSetup(id, refreshCallback("delete"));
	}

	private Callback refreshCallback(String what)
	{
		return new Callback()
		{
			@Override
			public void onFailure(Call call, IOException ex)
			{
				log.debug("RuneTools: setup {} failed: {}", what, ex.getMessage());
				onError.accept("Setup " + what + " failed (offline?)");
			}

			@Override
			public void onResponse(Call call, Response resp)
			{
				try (Response r = resp)
				{
					if (r.isSuccessful())
					{
						etag = null;   // force a fresh pull so the panel reflects the write
						poll();
					}
					else
					{
						log.debug("RuneTools: setup {} HTTP {}", what, r.code());
						onError.accept("Setup " + what + " failed (HTTP " + r.code() + ")");
					}
				}
			}
		};
	}

	private void persist(JsonArray arr)
	{
		try
		{
			configManager.setConfiguration(GROUP, KEY_SETUPS, arr != null ? arr.toString() : "[]");
			if (etag != null)
			{
				configManager.setConfiguration(GROUP, KEY_ETAG, etag);
			}
		}
		catch (Exception ex)
		{
			log.debug("RuneTools: could not persist setups: {}", ex.getMessage());
		}
	}

	private void load()
	{
		try
		{
			etag = configManager.getConfiguration(GROUP, KEY_ETAG);
			String raw = configManager.getConfiguration(GROUP, KEY_SETUPS);
			if (raw != null && !raw.isEmpty())
			{
				JsonArray arr = new JsonParser().parse(raw).getAsJsonArray();
				List<GearSetup> parsed = new ArrayList<>();
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
						if (g.getId() > 0)
						{
							parsed.add(g);
						}
					}
					catch (RuntimeException ex)
					{
						log.debug("RuneTools: skipped a malformed cached setup: {}", ex.getMessage());
					}
				}
				setups = parsed;
			}
		}
		catch (Exception ex)
		{
			log.debug("RuneTools: could not load cached setups: {}", ex.getMessage());
		}
	}
}
