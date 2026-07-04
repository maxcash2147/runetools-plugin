package com.runetools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.WorldType;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.SpriteID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.banktags.BankTagsPlugin;
import net.runelite.client.plugins.banktags.BankTagsService;
import net.runelite.client.plugins.banktags.TagManager;
import net.runelite.client.plugins.banktags.tabs.LayoutManager;
import net.runelite.client.task.Schedule;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Response;

@Slf4j
@PluginDependency(BankTagsPlugin.class)
@PluginDescriptor(
	name = "RuneTools",
	description = "Sync GE trades + account to RuneTools (gecharts.com) for auto P/L and personalized flips",
	tags = {"grand", "exchange", "ge", "flip", "flipping", "merch", "money", "profit", "runetools", "gecharts"}
)
public class RuneToolsPlugin extends Plugin
{
	private static final int COINS_ITEM_ID = 995;
	private static final String PENDING_KEY = "pending";

	@Inject private Client client;
	@Inject private RuneToolsConfig config;
	@Inject private ConfigManager configManager;
	@Inject private OkHttpClient okHttpClient;
	@Inject private ClientToolbar clientToolbar;
	@Inject private ClientThread clientThread;
	@Inject private BankTagsService bankTagsService;
	@Inject private TagManager tagManager;
	@Inject private LayoutManager layoutManager;
	@Inject private ItemManager itemManager;
	@Inject private SpriteManager spriteManager;

	private RuneToolsApiClient api;
	private OfferTracker offerTracker;
	private SetupManager setupManager;
	private BankFilterController bankFilter;
	private LoadoutReader loadoutReader;
	private RuneToolsPanel panel;
	private NavigationButton navButton;

	private final AccountSnapshot snapshot = new AccountSnapshot();
	private final List<Trade> pending = Collections.synchronizedList(new ArrayList<>());
	private int inventoryCoins;
	private int bankCoins;

	@Override
	protected void startUp()
	{
		api = new RuneToolsApiClient(okHttpClient, config);
		offerTracker = new OfferTracker();
		setupManager = new SetupManager(configManager, api);
		bankFilter = new BankFilterController(bankTagsService, tagManager, layoutManager, itemManager, clientThread);
		setupManager.setOnDelete(id -> bankFilter.forget(id));
		loadoutReader = new LoadoutReader(client, itemManager);
		loadPending();

		panel = new RuneToolsPanel(this::testConnection, this::onFilterSetup, this::onClearFilter, itemManager,
			this::addCurrentSetup, this::updateFromGear, this::pushSetup, this::deleteSetup);
		setupManager.setOnChange(() -> panel.setSetups(setupManager.getSetups()));
		loadSpellbookIcons();
		navButton = NavigationButton.builder()
			.tooltip("RuneTools")
			.icon(icon())
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		updatePanelStatic();
		panel.setSetups(setupManager.getSetups());   // show any cached setups immediately
	}

	@Override
	protected void shutDown()
	{
		clientToolbar.removeNavigation(navButton);
		savePending();
		navButton = null;
		// Keep the panel reference: a late async upload callback may still touch it.
	}

	@Provides
	RuneToolsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(RuneToolsConfig.class);
	}

	// -- capture (all handlers run on the client thread) --------------------- //

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		if (e.getGameState() == GameState.LOGIN_SCREEN || e.getGameState() == GameState.HOPPING)
		{
			// Character context is gone; drop identity so we never attribute to the wrong RSN.
			snapshot.rsn = null;
		}
	}

	@Subscribe
	public void onGameTick(GameTick e)
	{
		updateIdentity();
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged e)
	{
		ItemContainer c = e.getItemContainer();
		if (c == null)
		{
			return;
		}
		if (e.getContainerId() == InventoryID.INVENTORY.getId())
		{
			inventoryCoins = c.count(COINS_ITEM_ID);
			snapshot.inventory = itemsToArray(c);
			snapshot.gp = (long) inventoryCoins + (long) bankCoins;
			snapshot.dirty = true;
		}
		else if (e.getContainerId() == InventoryID.BANK.getId())
		{
			bankCoins = c.count(COINS_ITEM_ID);
			if (config.syncBank())
			{
				snapshot.bank = itemsToArray(c);
			}
			snapshot.gp = (long) inventoryCoins + (long) bankCoins;
			snapshot.dirty = true;
		}
	}

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged e)
	{
		GrandExchangeOffer offer = e.getOffer();
		// At login/hop the client fires an EMPTY burst for ALL 8 slots (before LOGGED_IN), even
		// for occupied ones. Ignore those so they never wipe our persisted per-slot baselines;
		// a real in-game slot collection produces EMPTY while LOGGED_IN and is still handled.
		if (offer.getState() == GrandExchangeOfferState.EMPTY && client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		String rsn = snapshot.rsn;
		String accountHash = snapshot.accountHash;
		if (rsn == null)
		{
			// A GE event can beat the first game tick; read identity directly as a fallback,
			// and remember it so later events (and state sync) have it too.
			Player p = client.getLocalPlayer();
			rsn = p != null ? p.getName() : null;
			long ah = client.getAccountHash();
			accountHash = ah == -1 ? null : Long.toString(ah);
			if (rsn != null && !rsn.isEmpty())
			{
				snapshot.rsn = rsn;
				snapshot.accountHash = accountHash;
			}
		}

		// Queue the trade whenever we captured one — do NOT require an RSN here. The API key
		// already identifies the account server-side; the RSN only links a display name and is
		// backfilled at flush time if it wasn't known yet. (Dropping on a momentarily-null RSN was
		// silently losing fills.)
		Trade t = offerTracker.process(e.getSlot(), offer, rsn, accountHash);
		log.info("RuneTools/GE: slot={} state={} qtySold={} spent={} syncTrades={} rsn={} -> {}",
			e.getSlot(), offer.getState(), offer.getQuantitySold(), offer.getSpent(),
			config.syncTrades(), rsn,
			t == null ? "no-trade" : (t.getSide() + " " + t.getQty() + " @" + t.getPrice()));
		if (t != null && config.syncTrades())
		{
			pending.add(t);
			savePending();
			updatePanelStatic();
			log.info("RuneTools: queued {} {} x item {} @ {}gp (rsn={})",
				t.getSide(), t.getQty(), t.getItemId(), t.getPrice(), rsn);
		}

		rebuildOffers();
		snapshot.dirty = true;
	}

	private void updateIdentity()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		Player p = client.getLocalPlayer();
		String name = p != null ? p.getName() : null;
		if (name == null || name.isEmpty())
		{
			return;
		}
		boolean firstIdentity = snapshot.rsn == null;
		long ah = client.getAccountHash();
		snapshot.rsn = name;
		snapshot.accountHash = ah == -1 ? null : Long.toString(ah);
		snapshot.world = client.getWorld();
		snapshot.members = client.getWorldType().contains(WorldType.MEMBERS);
		if (firstIdentity)
		{
			// Ensure at least one state upload after login even if nothing else changes.
			recomputeGpAndOffers();
			snapshot.dirty = true;
			updatePanelStatic();
			log.info("RuneTools: identity captured rsn={} accountHash={}", name, snapshot.accountHash);
		}
	}

	private void recomputeGpAndOffers()
	{
		ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
		if (inv != null)
		{
			inventoryCoins = inv.count(COINS_ITEM_ID);
			snapshot.inventory = itemsToArray(inv);
		}
		ItemContainer bank = client.getItemContainer(InventoryID.BANK);
		if (bank != null)
		{
			bankCoins = bank.count(COINS_ITEM_ID);
			if (config.syncBank())
			{
				snapshot.bank = itemsToArray(bank);
			}
		}
		snapshot.gp = (long) inventoryCoins + (long) bankCoins;
		rebuildOffers();
	}

	private void rebuildOffers()
	{
		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		JsonArray arr = new JsonArray();
		int free = 0;
		if (offers != null)
		{
			for (int slot = 0; slot < offers.length; slot++)
			{
				GrandExchangeOffer o = offers[slot];
				if (o == null || o.getState() == GrandExchangeOfferState.EMPTY)
				{
					free++;
					continue;
				}
				JsonObject j = new JsonObject();
				j.addProperty("slot", slot);
				j.addProperty("item_id", o.getItemId());
				j.addProperty("side", OfferTracker.isBuy(o.getState()) ? "buy" : "sell");
				j.addProperty("state", o.getState().name().toLowerCase());
				j.addProperty("price", o.getPrice());
				j.addProperty("qty", o.getTotalQuantity());
				j.addProperty("filled", o.getQuantitySold());
				j.addProperty("spent", o.getSpent());
				arr.add(j);
			}
		}
		snapshot.offers = arr;
		snapshot.freeSlots = free;
	}

	private static JsonArray itemsToArray(ItemContainer c)
	{
		JsonArray arr = new JsonArray();
		Map<Integer, Long> agg = new LinkedHashMap<>();
		for (Item it : c.getItems())
		{
			int id = it.getId();
			if (id <= 0)
			{
				continue;
			}
			agg.merge(id, (long) it.getQuantity(), Long::sum);
		}
		for (Map.Entry<Integer, Long> en : agg.entrySet())
		{
			JsonObject o = new JsonObject();
			o.addProperty("item_id", en.getKey());
			o.addProperty("qty", en.getValue());
			arr.add(o);
		}
		return arr;
	}

	// -- upload (scheduler thread; OkHttp enqueue is async, never blocks) ---- //

	@Schedule(period = 20, unit = ChronoUnit.SECONDS, asynchronous = true)
	public void sync()
	{
		if (!hasKeyAndConsent())
		{
			return;
		}
		flushTrades();                       // needs only the API key (it identifies the account)
		if (snapshot.hasIdentity())
		{
			flushState();                    // account state is per-(user, RSN), so it needs the RSN
		}
		else
		{
			log.debug("RuneTools: state sync waiting for in-game identity (RSN not captured yet)");
		}
		if (config.syncSetups())
		{
			setupManager.poll();             // full pull + ETag -> 304 when unchanged
		}
	}

	private void onFilterSetup(GearSetup s)
	{
		setupManager.setActive(s.getId());
		bankFilter.filter(s, config.setupFuzzyMatch(), config.setupBankLayout());
	}

	private void onClearFilter()
	{
		setupManager.clearActive();
		bankFilter.clear();
	}

	// -- in-game authoring (all live reads on the client thread) ------------- //

	private void addCurrentSetup(String name)
	{
		clientThread.invoke(() ->
		{
			if (client.getGameState() != GameState.LOGGED_IN)
			{
				return;
			}
			GearSetup s = loadoutReader.snapshot();
			s.setName(name);
			setupManager.pushCreate(s);
		});
	}

	private void updateFromGear(GearSetup current)
	{
		clientThread.invoke(() ->
		{
			if (client.getGameState() != GameState.LOGGED_IN)
			{
				return;
			}
			GearSetup fresh = loadoutReader.snapshot();
			fresh.setId(current.getId());        // keep identity + user-set metadata
			fresh.setName(current.getName());
			fresh.setFuzzy(current.getFuzzy());
			fresh.setNotes(current.getNotes());
			setupManager.pushUpdate(fresh);
		});
	}

	private void pushSetup(GearSetup s)
	{
		setupManager.pushUpdate(s);
	}

	private void deleteSetup(long id)
	{
		setupManager.pushDelete(id);
	}

	private void loadSpellbookIcons()
	{
		clientThread.invokeLater(() ->
		{
			BufferedImage std = spriteManager.getSprite(SpriteID.SideiconsInterface.MAGIC, 0);
			BufferedImage anc = spriteManager.getSprite(SpriteID.SideiconsInterface.SPELLBOOK_ANCIENT_MAGICKS, 0);
			BufferedImage lun = spriteManager.getSprite(SpriteID.SideiconsInterface.SPELLBOOK_LUNAR, 0);
			BufferedImage arc = spriteManager.getSprite(SpriteID.SideiconsInterface.SPELLBOOK_ARCEUUS, 0);
			if (std == null || anc == null || lun == null || arc == null)
			{
				return false;   // sprites not cached yet — retry next tick
			}
			panel.setSpellbookIcons(new BufferedImage[]{std, anc, lun, arc});
			return true;
		});
	}

	private boolean hasKeyAndConsent()
	{
		return config.consent() && config.apiKey() != null && !config.apiKey().trim().isEmpty();
	}

	private void flushTrades()
	{
		if (!config.syncTrades())
		{
			return;
		}
		List<Trade> batch;
		synchronized (pending)
		{
			if (pending.isEmpty())
			{
				return;
			}
			batch = new ArrayList<>(pending);
		}
		// Group by account so a batch is attributed correctly even across an account switch. A
		// trade captured before its RSN was known is backfilled with the current identity.
		String curRsn = snapshot.rsn;
		String curHash = snapshot.accountHash;
		Map<String, List<Trade>> byRsn = new LinkedHashMap<>();
		Map<String, String> hashByRsn = new HashMap<>();
		for (Trade t : batch)
		{
			String rsn = t.getRsn() != null ? t.getRsn() : curRsn;
			String key = rsn == null ? "" : rsn;
			byRsn.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
			String hash = t.getAccountHash() != null ? t.getAccountHash() : curHash;
			if (hash != null)
			{
				hashByRsn.putIfAbsent(key, hash);
			}
		}
		log.info("RuneTools: flushing {} trade(s) to {}", batch.size(), api.base());
		for (Map.Entry<String, List<Trade>> entry : byRsn.entrySet())
		{
			List<Trade> group = entry.getValue();
			String rsn = entry.getKey().isEmpty() ? null : entry.getKey();
			String hash = hashByRsn.get(entry.getKey());
			api.postTrades(rsn, hash, group, new Callback()
			{
				@Override
				public void onFailure(Call call, IOException ex)
				{
					log.info("RuneTools: trade sync failed (will retry): {}", ex.getMessage());
				}

				@Override
				public void onResponse(Call call, Response resp)
				{
					try (Response r = resp)
					{
						if (r.isSuccessful())
						{
							synchronized (pending)
							{
								pending.removeAll(group);
							}
							savePending();
							panel.setLastSync(nowString());
							updatePanelStatic();
							log.info("RuneTools: synced {} trade(s) OK", group.size());
						}
						else if (r.code() == 401)
						{
							panel.setStatus("Invalid or revoked API key", false);
							log.info("RuneTools: trade sync rejected (401) — bad/revoked key");
						}
						else
						{
							log.info("RuneTools: trade sync HTTP {} (will retry)", r.code());
						}
					}
				}
			});
		}
	}

	private void flushState()
	{
		if (!config.syncState() || !snapshot.dirty)
		{
			return;
		}
		snapshot.snapshotTsMillis = System.currentTimeMillis();
		// Clear up-front so a change that lands mid-request re-marks it dirty and is not lost.
		snapshot.dirty = false;
		api.postState(snapshot, new Callback()
		{
			@Override
			public void onFailure(Call call, IOException ex)
			{
				snapshot.dirty = true;
				log.info("RuneTools: state sync failed (will retry): {}", ex.getMessage());
			}

			@Override
			public void onResponse(Call call, Response resp)
			{
				try (Response r = resp)
				{
					if (r.isSuccessful())
					{
						log.info("RuneTools: state synced OK");
					}
					else if (r.code() == 401)
					{
						panel.setStatus("Invalid or revoked API key", false);
						log.info("RuneTools: state sync rejected (401) — bad/revoked key");
					}
					else
					{
						snapshot.dirty = true;
						log.info("RuneTools: state sync HTTP {} (will retry)", r.code());
					}
				}
			}
		});
	}

	// -- panel + connection test --------------------------------------------- //

	void testConnection()
	{
		if (config.apiKey() == null || config.apiKey().trim().isEmpty())
		{
			panel.setStatus("No API key set", false);
			return;
		}
		if (!config.consent())
		{
			// Honor the disclosed gate on every outbound path — nothing leaves until consent is on.
			panel.setStatus("Enable consent in settings", false);
			return;
		}
		panel.setStatus("Checking…", true);
		api.me(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException ex)
			{
				panel.setStatus("Can't reach server", false);
			}

			@Override
			public void onResponse(Call call, Response resp)
			{
				try (Response r = resp)
				{
					String bodyStr = r.body() != null ? r.body().string() : "";
					if (!r.isSuccessful())
					{
						panel.setStatus(r.code() == 401 ? "Invalid or revoked API key"
							: "Server error " + r.code(), false);
						return;
					}
					JsonObject o = new JsonParser().parse(bodyStr).getAsJsonObject();
					JsonObject user = o.getAsJsonObject("user");
					JsonElement dn = user.get("display_name");
					String name = dn != null && !dn.isJsonNull() ? dn.getAsString()
						: user.get("email").getAsString();
					panel.setStatus("Connected as " + name, true);

					List<String> rsns = new ArrayList<>();
					JsonArray linked = o.getAsJsonArray("linked_rsn");
					if (linked != null)
					{
						for (JsonElement el : linked)
						{
							rsns.add(el.getAsString());
						}
					}
					panel.setLinked(String.join(", ", rsns));
				}
				catch (Exception ex)
				{
					panel.setStatus("Unexpected server response", false);
				}
			}
		});
	}

	private void updatePanelStatic()
	{
		if (panel == null)
		{
			return;
		}
		int n;
		synchronized (pending)
		{
			n = pending.size();
		}
		panel.setPending(n);
		if (config.apiKey() == null || config.apiKey().trim().isEmpty())
		{
			panel.setStatus("No API key set", false);
		}
		else if (!config.consent())
		{
			panel.setStatus("Enable consent in settings", false);
		}
		else if (snapshot.hasIdentity())
		{
			panel.setStatus("Syncing " + snapshot.rsn, true);
		}
		else
		{
			panel.setStatus("Waiting for login", true);
		}
	}

	// -- persistence of the unsynced-trade queue ----------------------------- //

	private void loadPending()
	{
		try
		{
			String raw = configManager.getConfiguration(RuneToolsConfig.GROUP, PENDING_KEY);
			if (raw == null || raw.isEmpty())
			{
				return;
			}
			JsonArray arr = new JsonParser().parse(raw).getAsJsonArray();
			List<Trade> loaded = new ArrayList<>();
			for (JsonElement el : arr)
			{
				JsonObject o = el.getAsJsonObject();
				loaded.add(new Trade(
					o.get("itemId").getAsInt(), o.get("side").getAsString(),
					o.get("qty").getAsInt(), o.get("price").getAsInt(),
					o.get("tsMillis").getAsLong(), o.get("extId").getAsString(),
					str(o, "rsn"), str(o, "accountHash")));
			}
			synchronized (pending)
			{
				pending.clear();
				pending.addAll(loaded);
			}
		}
		catch (Exception ex)
		{
			log.debug("Could not load pending trades: {}", ex.getMessage());
		}
	}

	private void savePending()
	{
		try
		{
			// Build the snapshot AND write it under the same lock, so two threads calling
			// savePending() (client thread on capture; OkHttp thread after a flush) can't
			// interleave and let a stale, larger queue overwrite a just-flushed one.
			synchronized (pending)
			{
				JsonArray arr = new JsonArray();
				for (Trade t : pending)
				{
					JsonObject o = new JsonObject();
					o.addProperty("itemId", t.getItemId());
					o.addProperty("side", t.getSide());
					o.addProperty("qty", t.getQty());
					o.addProperty("price", t.getPrice());
					o.addProperty("tsMillis", t.getTsMillis());
					o.addProperty("extId", t.getExtId());
					o.addProperty("rsn", t.getRsn());
					if (t.getAccountHash() != null)
					{
						o.addProperty("accountHash", t.getAccountHash());
					}
					arr.add(o);
				}
				if (arr.size() == 0)
				{
					configManager.unsetConfiguration(RuneToolsConfig.GROUP, PENDING_KEY);
				}
				else
				{
					configManager.setConfiguration(RuneToolsConfig.GROUP, PENDING_KEY, arr.toString());
				}
			}
		}
		catch (Exception ex)
		{
			log.debug("Could not persist pending trades: {}", ex.getMessage());
		}
	}

	private static String str(JsonObject o, String key)
	{
		JsonElement e = o.get(key);
		return e != null && !e.isJsonNull() ? e.getAsString() : null;
	}

	private static String nowString()
	{
		return LocalTime.now().withNano(0).toString();
	}

	private static BufferedImage icon()
	{
		BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(0x1F, 0x9D, 0x55));
		g.fillRoundRect(0, 0, 16, 16, 5, 5);
		g.setColor(Color.WHITE);
		g.setFont(new Font("SansSerif", Font.BOLD, 12));
		g.drawString("R", 4, 12);
		g.dispose();
		return img;
	}
}
