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
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import javax.inject.Inject;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Quest;
import net.runelite.api.Skill;
import net.runelite.api.Varbits;
import net.runelite.api.WorldType;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.SpriteID;
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.game.chatbox.ChatboxItemSearch;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.banktags.BankTagsPlugin;
import net.runelite.client.plugins.banktags.BankTagsService;
import net.runelite.client.plugins.banktags.TagManager;
import net.runelite.client.plugins.banktags.tabs.Layout;
import net.runelite.client.plugins.banktags.tabs.LayoutManager;
import net.runelite.client.plugins.banktags.tabs.TabManager;
import net.runelite.client.task.Schedule;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.LinkBrowser;
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
	private static final String ALERTS_SINCE_KEY = "alertsSince";   // persisted alert dedup cursor (epoch sec)

	// Bounds on untrusted, server-supplied data so a hostile/compromised server can't crash or flood
	// the client (all confirmed in the security audit - DoS / resource-exhaustion class).
	private static final int MAX_LAYOUT_CELLS = 4096;               // bank layout grid upper bound
	private static final int MAX_IMPORTED_LAYOUTS_PER_POLL = 100;
	private static final int MAX_BUY_LIMIT_ROWS = 500;
	private static final int MAX_CA_PLAN_IDS = 5000;
	private static final int MAX_ALERTS_PER_POLL = 8;              // then coalesce the rest into one

	@Inject private Client client;
	@Inject private RuneToolsConfig config;
	@Inject private ConfigManager configManager;
	@Inject private OkHttpClient okHttpClient;
	@Inject private ClientToolbar clientToolbar;
	@Inject private ClientThread clientThread;
	@Inject private BankTagsService bankTagsService;
	@Inject private TagManager tagManager;
	@Inject private LayoutManager layoutManager;
	@Inject private TabManager tabManager;
	@Inject private ItemManager itemManager;
	@Inject private SpriteManager spriteManager;
	@Inject private ChatboxItemSearch itemSearch;
	@Inject private Notifier notifier;

	private RuneToolsApiClient api;
	private OfferTracker offerTracker;
	private SessionTracker sessionTracker;
	private SetupManager setupManager;
	private BankFilterController bankFilter;
	private BankOrganizer bankOrganizer;
	private LoadoutReader loadoutReader;
	private RuneToolsPanel panel;
	private NavigationButton navButton;

	// The published account state. The client thread swaps in a new immutable snapshot; the sync
	// thread reads one coherent object, so it never sees a torn multi-field mix. stateDirty flags
	// that there's something new to upload (kept separate so it can be cleared/re-set independently).
	private final AtomicReference<AccountSnapshot> snapshot = new AtomicReference<>(AccountSnapshot.empty());
	private final AtomicBoolean stateDirty = new AtomicBoolean();
	private final List<Trade> pending = Collections.synchronizedList(new ArrayList<>());
	private int inventoryCoins;
	private int bankCoins;
	private int activeOffers;
	private volatile String layoutsEtag;
	private volatile boolean caCatalogSent;
	private volatile int caCompletedHash;
	private boolean caResyncPending;   // client thread only (set on chat, read on tick)
	private int questReadCountdown = -1;   // ticks until the once-per-login quest read; -1 = idle
	private int questsHash;                // client thread only; last-sent quest states (skip identical re-reads)
	private int slayerHash;                // client thread only; last-sent slayer state (skip identical re-reads)
	private volatile int tcgEconHash;      // last-sent OSRS TCG economy hash (0 = resend on next check)
	private volatile int tcgCardsHash;     // last-sent OSRS TCG card-collection hash (preserve-if-absent)
	private int buyLimitPollCountdown = -1;   // ticks until a post-trade buy-limit re-poll; -1 = idle
	private volatile long alertsSince;     // epoch sec of the newest in-game alert already shown
	private final AtomicBoolean alertsPollInFlight = new AtomicBoolean();   // at most one alerts poll outstanding
	private volatile Set<Integer> caPlanIds = new HashSet<>();
	private volatile CombatAchievements.Snapshot lastCaSnapshot;
	private volatile int[] caThresholds;

	// Collection Log: obtained data is read from the interface as pages are opened; overall counts
	// come from varps. All maps are guarded by clogLock (client thread queues, scheduler thread sends).
	private final Object clogLock = new Object();
	private final Map<String, JsonObject> clogChanged = new LinkedHashMap<>();   // page key -> page json, pending send
	private final Map<String, Integer> clogChangedHash = new HashMap<>();        // page key -> hash of the pending json
	private final Map<String, Integer> clogSentHash = new HashMap<>();           // page key -> last successfully-sent hash
	private volatile JsonObject clogTotals;
	private volatile int clogTotalsHash;
	private volatile boolean clogDirty;

	/** Lets the setups panel open the in-game item search and read live equipment/inventory slots. */
	private final SlotEditor slotEditor = new SlotEditor()
	{
		@Override
		public void searchItem(String tooltip, IntConsumer onPicked)
		{
			clientThread.invoke(() ->
			{
				if (client.getGameState() != GameState.LOGGED_IN)
				{
					return;
				}
				itemSearch
					.tooltipText(tooltip)
					.onItemSelected(itemId -> clientThread.invoke(() ->
					{
						int canon = itemManager.canonicalize(itemId);
						SwingUtilities.invokeLater(() -> onPicked.accept(canon));
					}))
					.build();
			});
		}

		@Override
		public void readEquipSlot(int equipIdx, IntConsumer onItem)
		{
			clientThread.invoke(() ->
			{
				int id = itemAt(client.getItemContainer(InventoryID.EQUIPMENT), equipIdx);
				SwingUtilities.invokeLater(() -> onItem.accept(id));
			});
		}

		@Override
		public void readInvSlot(int index, IntConsumer onItem)
		{
			clientThread.invoke(() ->
			{
				int id = itemAt(client.getItemContainer(InventoryID.INVENTORY), index);
				SwingUtilities.invokeLater(() -> onItem.accept(id));
			});
		}

		@Override
		public void isQuiver(int itemId, Consumer<Boolean> onResult)
		{
			clientThread.invoke(() ->
			{
				boolean q = false;
				if (itemId > 0)
				{
					String name = itemManager.getItemComposition(itemId).getName();
					q = name != null && name.toLowerCase().contains("quiver");
				}
				final boolean qf = q;
				SwingUtilities.invokeLater(() -> onResult.accept(qf));
			});
		}
	};

	private int itemAt(ItemContainer c, int idx)
	{
		if (c == null)
		{
			return -1;
		}
		Item[] items = c.getItems();
		if (idx < 0 || idx >= items.length)
		{
			return -1;
		}
		int id = items[idx].getId();
		return id > 0 ? itemManager.canonicalize(id) : -1;
	}

	@Override
	protected void startUp()
	{
		api = new RuneToolsApiClient(okHttpClient, config);
		offerTracker = new OfferTracker();
		sessionTracker = new SessionTracker();
		setupManager = new SetupManager(configManager, api);
		bankFilter = new BankFilterController(bankTagsService, tagManager, layoutManager, itemManager, clientThread);
		bankOrganizer = new BankOrganizer(bankTagsService, tagManager, layoutManager, tabManager, itemManager,
			clientThread, configManager);
		setupManager.setOnDelete(id -> bankFilter.forget(id));
		loadoutReader = new LoadoutReader(client, itemManager);
		loadPending();

		panel = new RuneToolsPanel(this::testConnection, this::onFilterSetup, this::onClearFilter, itemManager,
			this::addCurrentSetup, this::updateFromGear, this::pushSetup, this::deleteSetup, slotEditor, this::openHistory,
			this::organizeBank, this::removeOrganizedTabs, this::convertBankTab, this::exportBankTag);
		setupManager.setOnChange(() -> panel.setSetups(setupManager.getSetups()));
		setupManager.setOnError(msg -> panel.setStatus(msg, false));
		panel.setMissingComputer(this::computeMissingItems);
		// Resume the alert cursor from where we left off so alerts that fired while the client was
		// closed still surface; only a brand-new install (no saved cursor) baselines to "now".
		long savedAlerts = readAlertsSince();
		alertsSince = savedAlerts > 0 ? savedAlerts : System.currentTimeMillis() / 1000L;
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
		updateTradesPanel();
	}

	@Override
	protected void shutDown()
	{
		clientToolbar.removeNavigation(navButton);
		if (panel != null)
		{
			panel.onShutdown();   // stop the buy-limit ticker so the panel graph can be GC'd
		}
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
			snapshot.updateAndGet(s -> s.toBuilder().rsn(null).build());
			// Re-evaluate the OSRS TCG collection on next login: an account switch reads a different
			// RS profile's state, so clear the change hashes to force one fresh upload after re-login.
			tcgEconHash = 0;
			tcgCardsHash = 0;
		}
		else if (e.getGameState() == GameState.LOGGED_IN)
		{
			// Quest states are read once per login, a little after the world settles (each read
			// runs a clientscript, so we do the ~170 of them in one deferred pass, hash-guarded).
			questReadCountdown = 15;
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged e)
	{
		if (!config.syncState())
		{
			return;
		}
		// Rebuild the whole skills object so the snapshot stays internally consistent.
		JsonObject skills = new JsonObject();
		for (Skill sk : Skill.values())
		{
			if (sk == Skill.OVERALL)
			{
				continue;
			}
			JsonObject s = new JsonObject();
			s.addProperty("level", client.getRealSkillLevel(sk));
			s.addProperty("xp", client.getSkillExperience(sk));
			skills.add(sk.getName().toLowerCase(), s);
		}
		snapshot.updateAndGet(s -> s.toBuilder().skills(skills).build());
		stateDirty.set(true);
	}

	/** Once-per-login quest read (client thread, via the onGameTick countdown). */
	private void readQuests()
	{
		if (!config.syncState())
		{
			return;
		}
		JsonArray quests = new JsonArray();
		int hash = 1;
		for (Quest q : Quest.values())
		{
			String state;
			try
			{
				state = q.getState(client).name().toLowerCase();
			}
			catch (RuntimeException ex)
			{
				continue;   // a single unreadable quest shouldn't kill the pass
			}
			JsonObject o = new JsonObject();
			o.addProperty("id", q.getId());
			o.addProperty("name", q.getName());
			o.addProperty("state", state);
			quests.add(o);
			hash = hash * 31 + q.getId() * 7 + state.hashCode();
		}
		if (quests.size() > 0 && hash != questsHash)
		{
			questsHash = hash;
			snapshot.updateAndGet(s -> s.toBuilder().quests(quests).build());
			stateDirty.set(true);
		}
	}

	/**
	 * The Slayer task lives in a handful of varps/varbits (creature, amount, master, points, streak).
	 * Re-read only when one of those actually changes so we're not rebuilding the snapshot on every
	 * unrelated var change. Runs on the client thread (where var events fire).
	 */
	@Subscribe
	public void onVarbitChanged(VarbitChanged e)
	{
		if (!config.syncState())
		{
			return;
		}
		if (isWatched(e.getVarbitId(), Slayer.WATCH_VARBITS) || isWatched(e.getVarpId(), Slayer.WATCH_VARPS))
		{
			readSlayer();
		}
	}

	private static boolean isWatched(int id, int[] ids)
	{
		if (id < 0)
		{
			return false;
		}
		for (int w : ids)
		{
			if (w == id)
			{
				return true;
			}
		}
		return false;
	}

	/** Rebuild the slayer state (client thread) and publish it if it changed. Skips when no active task. */
	private void readSlayer()
	{
		if (!config.syncState())
		{
			return;
		}
		JsonObject slayer = Slayer.read(client);
		if (slayer == null)
		{
			return;   // no active task; preserve-if-absent leaves the last one until a new one is assigned
		}
		int h = Slayer.hash(slayer);
		if (h == slayerHash)
		{
			return;
		}
		slayerHash = h;
		snapshot.updateAndGet(s -> s.toBuilder().slayer(slayer).build());
		stateDirty.set(true);
	}

	@Subscribe
	public void onGameTick(GameTick e)
	{
		updateIdentity();
		if (questReadCountdown > 0 && --questReadCountdown == 0)
		{
			questReadCountdown = -1;
			readQuests();
			readSlayer();   // seed the current task at login; varbit changes keep it fresh after
		}
		// Post-trade buy-limit re-poll, fired a few ticks after a buy so the expedited flush has landed.
		if (buyLimitPollCountdown > 0 && --buyLimitPollCountdown == 0)
		{
			buyLimitPollCountdown = -1;
			if (hasKeyAndConsent())
			{
				pollBuyLimits();
			}
		}
		// A CA-completion chat message fired: resync now (a tick later, so the completion varp has
		// settled) instead of waiting for the 20s scheduler.
		if (caResyncPending)
		{
			caResyncPending = false;
			if (config.syncCombatAchievements() && hasKeyAndConsent())
			{
				syncCombatAchievements();
			}
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage e)
	{
		if (e.getType() == ChatMessageType.GAMEMESSAGE)
		{
			String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
			// e.g. "Congratulations, you've completed a Hard combat task: ..."
			if (msg.contains("combat task") && msg.contains("complet"))
			{
				caResyncPending = true;
			}
			// "New item added to your collection log: ..." - the drop bumps the overall counters now;
			// the item's page detail syncs the next time you open that page.
			if (msg.contains("added to your collection log") && config.syncCollectionLog())
			{
				refreshCollectionTotals();
			}
		}
	}

	/** When the collection-log interface draws a page, capture its items. Runs on the client thread. */
	@Subscribe
	public void onScriptPostFired(ScriptPostFired e)
	{
		if (e.getScriptId() != CollectionLog.DRAW_SCRIPT || !config.syncCollectionLog())
		{
			return;
		}
		CollectionLog.Page page = CollectionLog.readOpenPage(client, itemManager);
		JsonObject totals = CollectionLog.readTotals(client);
		synchronized (clogLock)
		{
			if (totals != null)
			{
				int th = totals.toString().hashCode();
				if (th != clogTotalsHash)
				{
					clogTotals = totals;
					clogTotalsHash = th;
					clogDirty = true;
				}
			}
			if (page != null)
			{
				Integer sent = clogSentHash.get(page.key);
				Integer queued = clogChangedHash.get(page.key);
				if ((sent == null || sent != page.hash) && (queued == null || queued != page.hash))
				{
					clogChanged.put(page.key, page.json);
					clogChangedHash.put(page.key, page.hash);
					clogDirty = true;
				}
			}
		}
		pushClogPanel();
	}

	/** Read the overall/per-tab collection-log counters (varps) on the client thread and refresh the UI. */
	private void refreshCollectionTotals()
	{
		clientThread.invoke(() ->
		{
			JsonObject totals = CollectionLog.readTotals(client);
			if (totals == null)
			{
				return;
			}
			synchronized (clogLock)
			{
				int th = totals.toString().hashCode();
				if (th != clogTotalsHash)
				{
					clogTotals = totals;
					clogTotalsHash = th;
					clogDirty = true;
				}
			}
			pushClogPanel();
		});
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
			final JsonArray inv = itemsToArray(c);
			final long gp = (long) inventoryCoins + (long) bankCoins;
			snapshot.updateAndGet(s -> s.toBuilder().inventory(inv).gp(gp).build());
			stateDirty.set(true);
		}
		else if (e.getContainerId() == InventoryID.BANK.getId())
		{
			bankCoins = c.count(COINS_ITEM_ID);
			final JsonArray bank = config.syncBank() ? itemsToArray(c) : null;
			final long gp = (long) inventoryCoins + (long) bankCoins;
			snapshot.updateAndGet(s -> s.toBuilder().gp(gp).bank(bank != null ? bank : s.bank).build());
			stateDirty.set(true);
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

		AccountSnapshot snap = snapshot.get();
		String rsn = snap.rsn;
		String accountHash = snap.accountHash;
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
				final String frsn = rsn;
				final String fhash = accountHash;
				snapshot.updateAndGet(s -> s.toBuilder().rsn(frsn).accountHash(fhash).build());
			}
		}

		// Queue the trade whenever we captured one - do NOT require an RSN here. The API key
		// already identifies the account server-side; the RSN only links a display name and is
		// backfilled at flush time if it wasn't known yet. (Dropping on a momentarily-null RSN was
		// silently losing fills.)
		Trade t = offerTracker.process(e.getSlot(), offer, rsn, accountHash);
		if (t != null)
		{
			sessionTracker.record(t);
		}
		log.debug("RuneTools/GE: slot={} state={} qtySold={} spent={} syncTrades={} -> {}",
			e.getSlot(), offer.getState(), offer.getQuantitySold(), offer.getSpent(),
			config.syncTrades(),
			t == null ? "no-trade" : (t.getSide() + " " + t.getQty() + " @" + t.getPrice()));
		if (t != null && config.syncTrades())
		{
			pending.add(t);
			savePending();
			updatePanelStatic();
			log.info("RuneTools: queued {} {} x item {} @ {}gp",
				t.getSide(), t.getQty(), t.getItemId(), t.getPrice());
			// A buy fill just consumed part of a 4h limit. Expedite the flush so the server's trade log
			// is current, then re-poll buy-limits a few ticks later (the counts are computed server-side
			// from that log). Sells don't touch buy limits, so only buys trigger it.
			if ("buy".equals(t.getSide()) && hasKeyAndConsent())
			{
				flushTrades();
				buyLimitPollCountdown = 3;
			}
		}

		rebuildOffers();
		updateTradesPanel();
		stateDirty.set(true);
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
		boolean firstIdentity = snapshot.get().rsn == null;
		long ah = client.getAccountHash();
		final String hash = ah == -1 ? null : Long.toString(ah);
		final int world = client.getWorld();
		final boolean members = client.getWorldType().contains(WorldType.MEMBERS);
		snapshot.updateAndGet(s -> s.toBuilder().rsn(name).accountHash(hash).world(world).members(members).build());
		if (firstIdentity)
		{
			// Ensure at least one state upload after login even if nothing else changes.
			recomputeGpAndOffers();
			stateDirty.set(true);
			updatePanelStatic();
			// RSN only at DEBUG; never log the persistent account hash (a stable cross-session
			// correlator) to the on-disk client log users routinely share.
			log.debug("RuneTools: identity captured for {}", name);
		}
	}

	private void recomputeGpAndOffers()
	{
		ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
		final JsonArray invArr = inv != null ? itemsToArray(inv) : null;
		if (inv != null)
		{
			inventoryCoins = inv.count(COINS_ITEM_ID);
		}
		ItemContainer bank = client.getItemContainer(InventoryID.BANK);
		JsonArray bankArr = null;
		if (bank != null)
		{
			bankCoins = bank.count(COINS_ITEM_ID);
			bankArr = config.syncBank() ? itemsToArray(bank) : null;
		}
		final JsonArray bankJson = bankArr;
		final long gp = (long) inventoryCoins + (long) bankCoins;
		snapshot.updateAndGet(s -> s.toBuilder()
			.inventory(invArr != null ? invArr : s.inventory)
			.bank(bankJson != null ? bankJson : s.bank)
			.gp(gp)
			.build());
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
		final JsonArray offersArr = arr;
		final int freeSlots = free;
		snapshot.updateAndGet(s -> s.toBuilder().offers(offersArr).freeSlots(freeSlots).build());
		activeOffers = arr.size();
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
		if (snapshot.get().hasIdentity())
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
			pollImportedLayouts();           // apply bank layouts imported on the site
		}
		if (config.syncCombatAchievements())
		{
			syncCombatAchievements();
			pollCaPlan();
		}
		if (config.syncCollectionLog())
		{
			refreshCollectionTotals();   // keep overall progress fresh even if the log isn't open
			syncCollectionLog();
		}
		if (config.syncTcg())
		{
			syncTcg();
		}
		if (config.inGameAlerts())
		{
			pollTriggeredAlerts();
		}
		pollBuyLimits();
	}

	/** Show newly-triggered RuneTools price alerts as RuneLite notifications. */
	private void pollTriggeredAlerts()
	{
		// Keep at most one alerts poll outstanding: a slow request must not let the next 20s poll
		// re-request from the same (not-yet-advanced) cursor and notify the same alert twice.
		if (!alertsPollInFlight.compareAndSet(false, true))
		{
			return;
		}
		final long since = alertsSince;
		api.getTriggeredAlerts(since, new Callback()
		{
			@Override
			public void onFailure(Call call, IOException ex)
			{
				alertsPollInFlight.set(false);
				log.debug("RuneTools: alerts poll failed: {}", ex.getMessage());
			}

			@Override
			public void onResponse(Call call, Response resp)
			{
				try (Response r = resp)
				{
					if (!r.isSuccessful())
					{
						return;   // 404 until the server ships the endpoint - silently skip
					}
					JsonObject o = new JsonParser().parse(RuneToolsApiClient.readBoundedBody(r)).getAsJsonObject();
					if (o.has("now") && !o.get("now").isJsonNull())
					{
						long advanced = Math.max(since, o.get("now").getAsLong());
						if (advanced != alertsSince)
						{
							alertsSince = advanced;
							persistAlertsSince(advanced);   // survive a restart without replaying shown alerts
						}
					}
					JsonArray arr = o.getAsJsonArray("triggered");
					if (arr == null)
					{
						return;
					}
					int shown = 0;
					for (JsonElement e : arr)
					{
						if (shown >= MAX_ALERTS_PER_POLL)
						{
							notifier.notify("RuneTools: +" + (arr.size() - shown) + " more price alert(s) triggered.");
							break;
						}
						JsonObject a = e.getAsJsonObject();
						// Build the message from a fixed template with sanitized parts - the server must
						// never be able to inject control chars or a spoofed body of arbitrary length.
						String name = clean(str(a, "name", "Item"), 40);
						String field = clean(str(a, "field", "price"), 20);
						String op = allowedOp(str(a, "op", ""));
						String threshold = numeric(a, "threshold");
						String value = numeric(a, "value");
						notifier.notify("RuneTools: " + name + " " + field + " " + op + " "
							+ threshold + " - now " + value);
						shown++;
					}
				}
				catch (Exception ex)
				{
					log.debug("RuneTools: could not parse alerts: {}", ex.getMessage());
				}
				finally
				{
					alertsPollInFlight.set(false);
				}
			}
		});
	}

	/** Refresh the panel's GE buy-limit rows (used/limit + reset time from the server's trade log). */
	private void pollBuyLimits()
	{
		api.getBuyLimits(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException ex)
			{
				log.debug("RuneTools: buy-limits poll failed: {}", ex.getMessage());
			}

			@Override
			public void onResponse(Call call, Response resp)
			{
				try (Response r = resp)
				{
					if (!r.isSuccessful())
					{
						return;   // 404 until the server ships the endpoint - silently skip
					}
					JsonObject o = new JsonParser().parse(RuneToolsApiClient.readBoundedBody(r)).getAsJsonObject();
					JsonArray arr = o.getAsJsonArray("items");
					List<RuneToolsPanel.BuyLimitRow> rows = new ArrayList<>();
					if (arr != null)
					{
						for (JsonElement e : arr)
						{
							if (rows.size() >= MAX_BUY_LIMIT_ROWS)
							{
								break;
							}
							JsonObject it = e.getAsJsonObject();
							rows.add(new RuneToolsPanel.BuyLimitRow(
								asInt(it, "item_id", 0),
								clean(str(it, "name", ""), 40),
								asInt(it, "used", 0),
								asInt(it, "limit", 0),
								it.has("resets_at") && !it.get("resets_at").isJsonNull()
									? it.get("resets_at").getAsLong() : 0));
						}
					}
					panel.setBuyLimits(rows);
				}
				catch (Exception ex)
				{
					log.debug("RuneTools: could not parse buy limits: {}", ex.getMessage());
				}
			}
		});
	}

	/** Assemble the panel's Combat Achievements view from the last cache read + the synced plan. */
	private void pushCaPanel()
	{
		CombatAchievements.Snapshot snap = lastCaSnapshot;
		if (snap == null || panel == null)
		{
			return;
		}
		int totalDone = 0, total = 0, earned = snap.totalEarnedPts(), possible = 0;
		List<RuneToolsPanel.CaTierRow> rows = new ArrayList<>();
		for (int i = 0; i < CombatAchievements.TIER_LABELS.length; i++)
		{
			totalDone += snap.tierDone[i];
			total += snap.tierTotal[i];
			possible += snap.tierPossiblePts[i];
			rows.add(new RuneToolsPanel.CaTierRow(CombatAchievements.TIER_LABELS[i],
				snap.tierDone[i], snap.tierTotal[i], snap.tierEarnedPts[i], snap.tierPossiblePts[i]));
		}
		String overall = totalDone + "/" + total + " tasks · " + earned + "/" + possible + " points";
		String next = null;
		int[] th = caThresholds;
		if (th != null)
		{
			for (int i = 0; i < th.length; i++)
			{
				if (th[i] > 0 && earned < th[i])
				{
					next = (th[i] - earned) + " points to complete " + CombatAchievements.TIER_LABELS[i];
					break;
				}
			}
		}

		// Plan: group the user's planned, not-yet-done tasks by boss.
		Set<Integer> done = new HashSet<>();
		for (JsonElement e : snap.completed)
		{
			done.add(e.getAsInt());
		}
		Map<String, List<String>> byBoss = new TreeMap<>();
		for (JsonElement e : snap.tasks)
		{
			JsonObject t = e.getAsJsonObject();
			int id = t.get("task_id").getAsInt();
			if (!caPlanIds.contains(id) || done.contains(id))
			{
				continue;
			}
			String boss = t.has("monster") ? t.get("monster").getAsString() : "Other";
			byBoss.computeIfAbsent(boss, k -> new ArrayList<>())
				.add(t.get("name").getAsString() + " (" + t.get("points").getAsInt() + ")");
		}
		List<String> planLines = new ArrayList<>();
		for (Map.Entry<String, List<String>> e : byBoss.entrySet())
		{
			planLines.add(e.getKey() + ": " + String.join(" · ", e.getValue()));
		}
		panel.setCaProgress(overall, next, rows, planLines);
	}

	/** POST any collection-log totals/pages changed since the last successful sync. Scheduler thread. */
	private void syncCollectionLog()
	{
		JsonObject totals;
		List<JsonObject> pages;
		final Map<String, Integer> sentHashes = new HashMap<>();
		synchronized (clogLock)
		{
			if (!clogDirty)
			{
				return;
			}
			totals = clogTotals;
			pages = new ArrayList<>(clogChanged.values());
			for (Map.Entry<String, Integer> en : clogChangedHash.entrySet())
			{
				sentHashes.put(en.getKey(), en.getValue());
			}
			// Optimistically dequeue; re-queue on failure so a page is never silently lost.
			clogChanged.clear();
			clogChangedHash.clear();
			clogDirty = false;
		}
		if (totals == null && pages.isEmpty())
		{
			return;
		}
		JsonObject body = new JsonObject();
		String rsn = snapshot.get().rsn;
		if (rsn != null)
		{
			body.addProperty("rsn", rsn);
		}
		if (totals != null)
		{
			body.add("totals", totals);
		}
		JsonArray arr = new JsonArray();
		for (JsonObject p : pages)
		{
			arr.add(p);
		}
		body.add("pages", arr);
		api.postCollectionLog(body, new Callback()
		{
			@Override
			public void onFailure(Call call, IOException ex)
			{
				requeueCollectionPages(sentHashes, pages);
				log.debug("RuneTools: collection-log sync failed (will retry): {}", ex.getMessage());
			}

			@Override
			public void onResponse(Call call, Response resp)
			{
				try (Response r = resp)
				{
					if (r.isSuccessful())
					{
						synchronized (clogLock)
						{
							clogSentHash.putAll(sentHashes);   // don't re-send these unchanged pages
						}
					}
					else
					{
						requeueCollectionPages(sentHashes, pages);
						log.debug("RuneTools: collection-log sync HTTP {}", r.code());
					}
				}
			}
		});
	}

	/** Put pages back on the queue after a failed send so the next tick retries them. */
	private void requeueCollectionPages(Map<String, Integer> hashes, List<JsonObject> pages)
	{
		synchronized (clogLock)
		{
			int i = 0;
			for (Map.Entry<String, Integer> en : hashes.entrySet())
			{
				String key = en.getKey();
				if (!clogChanged.containsKey(key) && i < pages.size())   // don't clobber a newer read
				{
					clogChanged.put(key, pages.get(i));
					clogChangedHash.put(key, en.getValue());
				}
				i++;
			}
			clogDirty = true;
		}
	}

	/** Render the panel's Collection Log section from the latest totals. */
	private void pushClogPanel()
	{
		JsonObject t = clogTotals;
		if (t == null || panel == null)
		{
			return;
		}
		int obtained = t.has("obtained") ? t.get("obtained").getAsInt() : 0;
		int total = t.has("total") ? t.get("total").getAsInt() : 0;
		List<RuneToolsPanel.ClogTabRow> rows = new ArrayList<>();
		JsonArray tabs = t.getAsJsonArray("tabs");
		if (tabs != null)
		{
			for (JsonElement e : tabs)
			{
				JsonObject o = e.getAsJsonObject();
				rows.add(new RuneToolsPanel.ClogTabRow(
					o.get("name").getAsString(), o.get("obtained").getAsInt(), o.get("total").getAsInt()));
			}
		}
		int syncedPages;
		synchronized (clogLock)
		{
			syncedPages = clogSentHash.size() + clogChanged.size();
		}
		panel.setCollectionLog(obtained, total, rows, syncedPages);
	}

	/**
	 * Read the OSRS TCG collection (from that separate plugin's config) and push it for the site's TCG
	 * hiscores + collection page. The economy fields (credits, packs, counts) re-send whenever they
	 * change; the card list is preserve-if-absent - re-sent only when the collection itself changes. A
	 * no-op when the TCG plugin isn't installed or has no state for this profile. Scheduler thread.
	 */
	private void syncTcg()
	{
		if (!snapshot.get().hasIdentity())
		{
			return;   // need the in-game RSN to attribute the collection on the hiscores
		}
		Tcg.Data d = Tcg.read(configManager);
		if (d == null)
		{
			return;   // TCG plugin absent, nothing saved for this profile, or an unreadable/tampered blob
		}
		if (d.econHash == tcgEconHash && d.cardsHash == tcgCardsHash)
		{
			return;   // nothing changed since the last successful upload
		}
		final boolean sendCards = d.cardsHash != tcgCardsHash;
		JsonObject body = d.economy;   // a fresh object from read() - safe to extend
		body.addProperty("rsn", snapshot.get().rsn);
		if (sendCards)
		{
			body.add("cards", d.cards);
		}
		final int econHash = d.econHash;
		final int cardsHash = d.cardsHash;
		api.postTcg(body, new Callback()
		{
			@Override
			public void onFailure(Call call, IOException ex)
			{
				log.debug("RuneTools: TCG sync failed (will retry): {}", ex.getMessage());
			}

			@Override
			public void onResponse(Call call, Response resp)
			{
				try (Response r = resp)
				{
					if (r.isSuccessful())
					{
						tcgEconHash = econHash;
						if (sendCards)
						{
							tcgCardsHash = cardsHash;   // don't re-ship the card array until it changes again
						}
					}
					else
					{
						log.debug("RuneTools: TCG sync HTTP {}", r.code());
					}
				}
			}
		});
	}

	/** Pull the user's CA plan (task ids); names/bosses resolve against the local catalogue read. */
	private void pollCaPlan()
	{
		api.getCaPlan(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException ex)
			{
				log.debug("RuneTools: CA plan poll failed: {}", ex.getMessage());
			}

			@Override
			public void onResponse(Call call, Response resp)
			{
				try (Response r = resp)
				{
					if (!r.isSuccessful())
					{
						return;   // 404 until the server ships the endpoint - silently skip
					}
					JsonObject o = new JsonParser().parse(RuneToolsApiClient.readBoundedBody(r)).getAsJsonObject();
					JsonArray arr = o.getAsJsonArray("plan");
					Set<Integer> ids = new HashSet<>();
					if (arr != null)
					{
						for (JsonElement e : arr)
						{
							if (ids.size() >= MAX_CA_PLAN_IDS)
							{
								break;
							}
							try
							{
								ids.add(e.getAsInt());
							}
							catch (RuntimeException ignored)
							{
								// skip a non-numeric plan entry
							}
						}
					}
					caPlanIds = ids;
					pushCaPanel();   // re-render with the fresh plan
				}
				catch (Exception ex)
				{
					log.debug("RuneTools: could not parse CA plan: {}", ex.getMessage());
				}
			}
		});
	}

	/**
	 * Read Combat Achievements from the game cache and push them to the site's planner. The catalogue
	 * is sent once per session; completion is re-sent only when it actually changes (hash guard).
	 */
	private void syncCombatAchievements()
	{
		clientThread.invoke(() ->
		{
			if (client.getGameState() != GameState.LOGGED_IN)
			{
				return;
			}
			CombatAchievements.Snapshot snap = CombatAchievements.read(client);
			if (snap == null)
			{
				return;   // CA cache not resolved yet
			}
			lastCaSnapshot = snap;
			caThresholds = CombatAchievements.readTierThresholds(client);
			pushCaPanel();
			boolean sendCatalog = !caCatalogSent;
			if (!sendCatalog && snap.completedHash == caCompletedHash)
			{
				return;   // nothing changed since the last successful post
			}
			JsonObject body = new JsonObject();
			String rsn = snapshot.get().rsn;
			if (rsn != null)
			{
				body.addProperty("rsn", rsn);
			}
			if (sendCatalog)
			{
				body.add("tasks", snap.tasks);
			}
			body.add("completed", snap.completed);
			body.add("tiers", snap.tiers);
			final int hash = snap.completedHash;
			api.postCombatAchievements(body, new Callback()
			{
				@Override
				public void onFailure(Call call, IOException ex)
				{
					log.debug("RuneTools: CA sync failed: {}", ex.getMessage());
				}

				@Override
				public void onResponse(Call call, Response resp)
				{
					try (Response r = resp)
					{
						if (r.isSuccessful())
						{
							caCatalogSent = true;
							caCompletedHash = hash;
						}
					}
				}
			});
		});
	}

	/**
	 * Pull the user's bank layouts and apply any newly-imported ones (imported on the site) as
	 * in-game Bank Tags tabs. Tracks applied ids in config so each import is applied exactly once,
	 * and never re-applies the user's own exported originals.
	 */
	private void pollImportedLayouts()
	{
		api.getBankLayouts(layoutsEtag, new Callback()
		{
			@Override
			public void onFailure(Call call, IOException ex)
			{
				log.debug("RuneTools: layout sync failed: {}", ex.getMessage());
			}

			@Override
			public void onResponse(Call call, Response resp)
			{
				try (Response r = resp)
				{
					if (r.code() == 304 || r.code() == 401 || !r.isSuccessful())
					{
						return;
					}
					String body = RuneToolsApiClient.readBoundedBody(r);
					JsonObject o = new JsonParser().parse(body).getAsJsonObject();
					if (o.has("etag") && !o.get("etag").isJsonNull())
					{
						layoutsEtag = o.get("etag").getAsString();
					}
					JsonArray arr = o.getAsJsonArray("layouts");
					if (arr == null)
					{
						return;
					}
					Set<String> applied = readAppliedLayouts();
					boolean changed = false;
					int processed = 0;
					for (JsonElement e : arr)
					{
						if (++processed > MAX_IMPORTED_LAYOUTS_PER_POLL)
						{
							break;
						}
						JsonObject l = e.getAsJsonObject();
						if (!l.has("imported") || l.get("imported").isJsonNull() || !l.get("imported").getAsBoolean())
						{
							continue;    // only auto-apply layouts imported from someone else
						}
						String id = str(l, "id", null);
						if (id != null)
						{
							id = id.trim();   // readAppliedLayouts trims - normalise here so dedup matches
						}
						// The applied-set is a CSV; an id with a comma/newline would corrupt dedup, so
						// we can't safely track it - skip rather than risk re-applying forever.
						if (id == null || id.isEmpty()
							|| id.indexOf(',') >= 0 || id.indexOf('\n') >= 0 || id.indexOf('\r') >= 0)
						{
							continue;
						}
						if (applied.contains(id))
						{
							continue;
						}
						JsonArray cells = l.getAsJsonArray("cells");
						if (cells == null || cells.size() == 0)
						{
							continue;
						}
						String name = BankOrganizer.sanitizeTagName(str(l, "name", "Imported layout"));
						if (name.isEmpty())
						{
							name = "Imported layout";
						}
						int icon = l.has("icon_item_id") && !l.get("icon_item_id").isJsonNull()
							? asInt(l, "icon_item_id", -1) : -1;
						// Mark applied FIRST so a payload that somehow still errors can never loop on
						// every 20s poll; the allocation is already bounded in cellsToArray.
						applied.add(id);
						changed = true;
						try
						{
							bankOrganizer.applyImportedLayout(name, icon, cellsToArray(cells));
							panel.setBankStatus("Imported \"" + name + "\" - added as a bank tab.", true);
						}
						catch (RuntimeException ex)
						{
							log.debug("RuneTools: skipped bad imported layout {}: {}", id, ex.toString());
						}
					}
					if (changed)
					{
						writeAppliedLayouts(applied);
					}
				}
				catch (Exception ex)
				{
					log.debug("RuneTools: could not parse layouts: {}", ex.getMessage());
				}
			}
		});
	}

	/**
	 * Build a positional layout array from server-supplied cells. Positions are attacker-controlled,
	 * so the array is sized to the largest <em>in-range</em> position only - never to a raw value that
	 * could force a multi-GB allocation (the confirmed OOM/DoS). Out-of-range cells are ignored.
	 */
	private static int[] cellsToArray(JsonArray cells)
	{
		int max = -1;
		int processed = 0;
		for (JsonElement e : cells)
		{
			if (++processed > MAX_LAYOUT_CELLS)
			{
				break;
			}
			int pos = asInt(e.getAsJsonObject(), "pos", -1);
			if (pos >= 0 && pos < MAX_LAYOUT_CELLS && pos > max)
			{
				max = pos;
			}
		}
		if (max < 0)
		{
			return new int[0];
		}
		int[] arr = new int[max + 1];
		Arrays.fill(arr, -1);
		processed = 0;
		for (JsonElement e : cells)
		{
			if (++processed > MAX_LAYOUT_CELLS)
			{
				break;
			}
			JsonObject c = e.getAsJsonObject();
			int pos = asInt(c, "pos", -1);
			int id = asInt(c, "item_id", 0);
			if (pos >= 0 && pos < arr.length && id > 0)
			{
				arr[pos] = id;
			}
		}
		return arr;
	}

	private Set<String> readAppliedLayouts()
	{
		Set<String> out = new HashSet<>();
		String raw = configManager.getConfiguration(RuneToolsConfig.GROUP, "appliedLayouts");
		if (raw != null && !raw.isEmpty())
		{
			for (String s : raw.split(","))
			{
				if (!s.trim().isEmpty())
				{
					out.add(s.trim());
				}
			}
		}
		return out;
	}

	private void writeAppliedLayouts(Set<String> ids)
	{
		configManager.setConfiguration(RuneToolsConfig.GROUP, "appliedLayouts", String.join(",", ids));
	}

	/** The persisted alert dedup cursor (epoch seconds), or 0 if none has been saved yet. */
	private long readAlertsSince()
	{
		try
		{
			String raw = configManager.getConfiguration(RuneToolsConfig.GROUP, ALERTS_SINCE_KEY);
			return raw != null && !raw.isEmpty() ? Long.parseLong(raw.trim()) : 0L;
		}
		catch (RuntimeException ex)
		{
			return 0L;
		}
	}

	private void persistAlertsSince(long epochSec)
	{
		configManager.setConfiguration(RuneToolsConfig.GROUP, ALERTS_SINCE_KEY, Long.toString(epochSec));
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

	private void openHistory()
	{
		LinkBrowser.browse(api.base() + "/trades");
	}

	/**
	 * Organise the bank into category tabs (moves nothing). If tabs already exist, ask whether to
	 * rebuild from scratch (which resets manual edits) or just file newly-gained items.
	 */
	private void organizeBank()
	{
		if (!bankOrganizer.hasOrganized())
		{
			runOrganize(BankOrganizer.Mode.REBUILD);
			return;
		}
		Object[] options = {"Rebuild", "File new items only", "Cancel"};
		int choice = JOptionPane.showOptionDialog(panel,
			"<html>You've already organized your bank.<br><br>"
				+ "<b>Rebuild</b> re-sorts from scratch and <b>resets any manual edits</b> you've made to "
				+ "the RuneTools tabs.<br>"
				+ "<b>File new items only</b> keeps your edits and just files items you've gained since.</html>",
			"Organize bank", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
			null, options, options[1]);
		if (choice == 0)
		{
			runOrganize(BankOrganizer.Mode.REBUILD);
		}
		else if (choice == 1)
		{
			runOrganize(BankOrganizer.Mode.FILE_NEW);
		}
	}

	/** Read the live bank on the client thread and hand it to the organiser in the chosen mode. */
	private void runOrganize(BankOrganizer.Mode mode)
	{
		panel.setBankStatus(mode == BankOrganizer.Mode.FILE_NEW ? "Filing new items…" : "Organizing…", true);
		clientThread.invoke(() ->
		{
			ItemContainer bank = client.getItemContainer(InventoryID.BANK);
			if (bank == null)
			{
				panel.setBankStatus("Open your bank once, then try again.", false);
				return;
			}
			List<Integer> ids = new ArrayList<>();
			for (Item it : bank.getItems())
			{
				if (it.getId() > 0)
				{
					ids.add(it.getId());
				}
			}
			bankOrganizer.organize(ids, mode, msg -> panel.setBankStatus(msg, true));
		});
	}

	private void removeOrganizedTabs()
	{
		bankOrganizer.remove(() -> panel.setBankStatus("Removed the organized tabs.", true));
	}

	/**
	 * "Missing items · cost to complete" for a setup's detail view: diff the setup's items against
	 * what the player owns (bank + inventory + equipment) and price the gap. Local + offline. Items
	 * are aggregated by canonical id (so a stack of 1000 runes is priced as 1000 and the same id in
	 * two lists is counted once), and a per-item breakdown drives the row's tooltip.
	 */
	private void computeMissingItems(GearSetup setup, Consumer<RuneToolsPanel.MissingResult> onResult)
	{
		clientThread.invoke(() ->
		{
			ItemContainer bank = client.getItemContainer(InventoryID.BANK);
			Set<Integer> owned = new HashSet<>();
			for (ItemContainer c : new ItemContainer[]{bank,
				client.getItemContainer(InventoryID.INVENTORY), client.getItemContainer(InventoryID.EQUIPMENT)})
			{
				if (c == null)
				{
					continue;
				}
				for (Item it : c.getItems())
				{
					if (it.getId() > 0)
					{
						owned.add(itemManager.canonicalize(it.getId()));
					}
				}
			}
			// Aggregate the setup's requirements by canonical id (dedup across lists + fold in qty).
			Map<Integer, Long> need = new LinkedHashMap<>();
			for (Map.Entry<Integer, Integer> e : setup.filterItemCounts().entrySet())
			{
				int canon = itemManager.canonicalize(e.getKey());
				if (canon > 0)
				{
					need.merge(canon, (long) e.getValue(), Long::sum);
				}
			}
			int missing = 0;
			long cost = 0;
			List<RuneToolsPanel.MissingItem> breakdown = new ArrayList<>();
			for (Map.Entry<Integer, Long> e : need.entrySet())
			{
				int id = e.getKey();
				if (owned.contains(id))
				{
					continue;
				}
				missing++;
				long qty = e.getValue();
				long price = 0;
				try
				{
					price = (long) itemManager.getItemPrice(id) * qty;
				}
				catch (RuntimeException ignored)
				{
					// unpriced item - count it, cost stays approximate
				}
				cost += price;
				String name = "item " + id;
				try
				{
					name = itemManager.getItemComposition(id).getName();
				}
				catch (RuntimeException ignored)
				{
					// no composition - fall back to the id
				}
				breakdown.add(new RuneToolsPanel.MissingItem(name, qty, price));
			}
			breakdown.sort((a, b) -> Long.compare(b.price, a.price));   // dearest first
			final String text = bank == null
				? "Open your bank once to check for missing items."
				: missing == 0
					? "You own every item in this setup ✓"
					: "Missing " + missing + " item" + (missing == 1 ? "" : "s")
						+ " · ~" + String.format("%,d", cost) + " gp to buy";
			final RuneToolsPanel.MissingResult result = new RuneToolsPanel.MissingResult(
				text, bank != null ? breakdown : Collections.emptyList());
			SwingUtilities.invokeLater(() -> onResult.accept(result));
		});
	}

	private static final int[] BANK_TAB_COUNTS = {
		Varbits.BANK_TAB_ONE_COUNT, Varbits.BANK_TAB_TWO_COUNT, Varbits.BANK_TAB_THREE_COUNT,
		Varbits.BANK_TAB_FOUR_COUNT, Varbits.BANK_TAB_FIVE_COUNT, Varbits.BANK_TAB_SIX_COUNT,
		Varbits.BANK_TAB_SEVEN_COUNT, Varbits.BANK_TAB_EIGHT_COUNT, Varbits.BANK_TAB_NINE_COUNT,
	};

	/**
	 * Turn the native bank tab the user is currently viewing into a shareable Bank Tag. The bank
	 * container lists items tab-by-tab (tab 1, tab 2, ... then the main tab), and each numbered tab's
	 * size is a varbit - so we slice out exactly the open tab's items, in order, and hand them off.
	 */
	private void convertBankTab(String name)
	{
		clientThread.invoke(() ->
		{
			ItemContainer bank = client.getItemContainer(InventoryID.BANK);
			if (bank == null)
			{
				panel.setBankStatus("Open your bank first.", false);
				return;
			}
			int tab = client.getVarbitValue(Varbits.CURRENT_BANK_TAB);
			if (tab < 1 || tab > BANK_TAB_COUNTS.length)
			{
				panel.setBankStatus("Open a numbered bank tab (1-9) first, then convert.", false);
				return;
			}
			int offset = 0;
			for (int i = 0; i < tab - 1; i++)
			{
				offset += client.getVarbitValue(BANK_TAB_COUNTS[i]);
			}
			int count = client.getVarbitValue(BANK_TAB_COUNTS[tab - 1]);
			Item[] items = bank.getItems();
			List<Integer> ids = new ArrayList<>();
			for (int i = offset; i < offset + count && i < items.length; i++)
			{
				int id = items[i].getId();
				if (id > 0)
				{
					ids.add(id);
				}
			}
			bankOrganizer.saveTabAsTag(name, ids, msg -> panel.setBankStatus(msg, true));
		});
	}

	/**
	 * Publish the currently-open bank tag (its layout + items) to the user's gecharts.com profile,
	 * where others can view it and import it. Reads the tag on the client thread, names it on the
	 * EDT, then POSTs; on success copies the share link and opens the layout page.
	 */
	private void exportBankTag()
	{
		if (!hasKeyAndConsent())
		{
			panel.setBankStatus("Enable consent in settings before exporting to the site.", false);
			return;
		}
		clientThread.invoke(() ->
		{
			String tag = bankTagsService.getActiveTag();
			if (tag == null || tag.isEmpty())
			{
				panel.setBankStatus("Open one of your bank tabs in-game first, then export.", false);
				return;
			}
			JsonArray cells = new JsonArray();
			Layout layout = layoutManager.loadLayout(tag);
			if (layout != null)
			{
				int[] arr = layout.getLayout();
				for (int i = 0; i < arr.length; i++)
				{
					if (arr[i] > 0)
					{
						JsonObject c = new JsonObject();
						c.addProperty("pos", i);
						c.addProperty("item_id", arr[i]);
						cells.add(c);
					}
				}
			}
			else
			{
				int pos = 0;
				for (int id : tagManager.getItemsForTag(tag))
				{
					if (id > 0)
					{
						JsonObject c = new JsonObject();
						c.addProperty("pos", pos++);
						c.addProperty("item_id", id);
						cells.add(c);
					}
				}
			}
			if (cells.size() == 0)
			{
				panel.setBankStatus("That tab has no items to export.", false);
				return;
			}
			int icon = cells.get(0).getAsJsonObject().get("item_id").getAsInt();
			SwingUtilities.invokeLater(() ->
			{
				String name = JOptionPane.showInputDialog(panel, "Name this shared layout:", tag);
				if (name == null || name.trim().isEmpty())
				{
					return;
				}
				JsonObject body = new JsonObject();
				body.addProperty("name", name.trim());
				body.addProperty("icon_item_id", icon);
				body.add("cells", cells);
				panel.setBankStatus("Publishing…", true);
				api.postBankLayout(body, bankLayoutCallback());
			});
		});
	}

	private Callback bankLayoutCallback()
	{
		return new Callback()
		{
			@Override
			public void onFailure(Call call, IOException ex)
			{
				panel.setBankStatus("Export failed - are you online and connected?", false);
			}

			@Override
			public void onResponse(Call call, Response resp)
			{
				try (Response r = resp)
				{
					if (r.isSuccessful())
					{
						String url = null;
						try
						{
							String s = RuneToolsApiClient.readBoundedBody(r);
							url = new JsonParser().parse(s).getAsJsonObject().get("url").getAsString();
						}
						catch (Exception ignored)
						{
							// no URL in body - still a success
						}
						// Only auto-open/copy a link that points at our own (or the configured dev)
						// server. A malicious/compromised responder must not be able to drive the
						// browser to an arbitrary phishing URL or poison the clipboard.
						final String u = isSafeShareUrl(url) ? url : null;
						SwingUtilities.invokeLater(() ->
						{
							if (u != null)
							{
								copyToClipboard(u);
								panel.setBankStatus("Published! Link copied - opening your layout…", true);
								LinkBrowser.browse(u);
							}
							else
							{
								panel.setBankStatus("Published to your profile.", true);
							}
						});
					}
					else if (r.code() == 401)
					{
						panel.setBankStatus("Connect your API key first (Test connection).", false);
					}
					else
					{
						panel.setBankStatus("Export rejected (HTTP " + r.code() + ").", false);
					}
				}
				catch (Exception ex)
				{
					panel.setBankStatus("Export failed.", false);
				}
			}
		};
	}

	private void copyToClipboard(String text)
	{
		try
		{
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
		}
		catch (Exception ignored)
		{
			// clipboard may be unavailable; the browser still opens
		}
	}

	private void updateTradesPanel()
	{
		if (panel != null)
		{
			panel.setSessionProfit(sessionTracker.realizedProfit());
			panel.setRecentTrades(sessionTracker.recent());
			panel.setOfferSummary(activeOffers);
		}
	}

	// -- in-game authoring (all live reads on the client thread) ------------- //

	private void addCurrentSetup(String name)
	{
		if (blockedByConsent())
		{
			return;
		}
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
		if (blockedByConsent())
		{
			return;
		}
		clientThread.invoke(() ->
		{
			if (client.getGameState() != GameState.LOGGED_IN)
			{
				return;
			}
			// Refresh only the physical loadout; keep the setup's identity and all its metadata.
			GearSetup fresh = loadoutReader.snapshot();
			fresh.setId(current.getId());
			fresh.setName(current.getName());
			fresh.setFuzzy(current.getFuzzy());
			fresh.setNotes(current.getNotes());
			fresh.setSource(current.getSource());
			fresh.setStyle(current.getStyle());
			fresh.setMonsterId(current.getMonsterId());
			setupManager.pushUpdate(fresh);
		});
	}

	private void pushSetup(GearSetup s)
	{
		if (blockedByConsent())
		{
			return;
		}
		setupManager.pushUpdate(s);
	}

	private void deleteSetup(long id)
	{
		if (blockedByConsent())
		{
			return;
		}
		setupManager.pushDelete(id);
	}

	/**
	 * Guard for user-initiated publish actions: nothing that attaches the API key or uploads
	 * account/loadout data may fire until consent is on (the disclosure promises exactly this).
	 * Returns true (and shows why in the panel) when the action must be blocked.
	 */
	private boolean blockedByConsent()
	{
		if (hasKeyAndConsent())
		{
			return false;
		}
		panel.setStatus(config.apiKey() == null || config.apiKey().trim().isEmpty()
			? "Add your API key first (Connection settings)."
			: "Enable consent in settings before syncing.", false);
		return true;
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
				return false;   // sprites not cached yet - retry next tick
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
		AccountSnapshot snap = snapshot.get();
		String curRsn = snap.rsn;
		String curHash = snap.accountHash;
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
							log.info("RuneTools: trade sync rejected (401) - bad/revoked key");
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
		if (!config.syncState() || !stateDirty.get())
		{
			return;
		}
		// Clear up-front so a change that lands mid-request re-marks it dirty and is not lost.
		stateDirty.set(false);
		// Send one coherent, immutable cut (with the capture time stamped in).
		AccountSnapshot toSend = snapshot.get().toBuilder()
			.snapshotTsMillis(System.currentTimeMillis()).build();
		api.postState(toSend, new Callback()
		{
			@Override
			public void onFailure(Call call, IOException ex)
			{
				stateDirty.set(true);
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
						log.info("RuneTools: state sync rejected (401) - bad/revoked key");
					}
					else
					{
						stateDirty.set(true);
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
			// Honor the disclosed gate on every outbound path - nothing leaves until consent is on.
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
					String bodyStr = RuneToolsApiClient.readBoundedBody(r);
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
							if (rsns.size() >= 50)   // cap: no user has anywhere near this many linked RSNs
							{
								break;
							}
							if (!el.isJsonNull())
							{
								rsns.add(clean(el.getAsString(), 20));
							}
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
		else if (snapshot.get().hasIdentity())
		{
			panel.setStatus("Syncing " + snapshot.get().rsn, true);
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

	/** Null-safe string accessor with a fallback (untrusted-JSON friendly). */
	private static String str(JsonObject o, String key, String fallback)
	{
		try
		{
			JsonElement e = o.get(key);
			return e != null && !e.isJsonNull() ? e.getAsString() : fallback;
		}
		catch (RuntimeException ex)
		{
			return fallback;
		}
	}

	/** Null-safe int accessor with a fallback - never throws on a missing/non-numeric field. */
	private static int asInt(JsonObject o, String key, int fallback)
	{
		try
		{
			JsonElement e = o.get(key);
			return e != null && !e.isJsonNull() ? e.getAsInt() : fallback;
		}
		catch (RuntimeException ex)
		{
			return fallback;
		}
	}

	/** Strip control characters and cap length - server strings must not carry control chars into a UI/notification. */
	private static String clean(String s, int max)
	{
		if (s == null)
		{
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < s.length() && sb.length() < max; i++)
		{
			int c = s.charAt(i);
			boolean drop = (c < 0x20) || (c == 0x7f)               // C0 controls + DEL
				|| (c >= 0x80 && c <= 0x9f)                          // C1 controls
				|| (c == 0x2028 || c == 0x2029)                      // line / paragraph separators
				|| (c >= 0x202a && c <= 0x202e)                      // bidi embedding / override
				|| (c >= 0x2066 && c <= 0x2069);                     // bidi isolate
			if (!drop)
			{
				sb.append((char) c);
			}
		}
		return sb.toString();
	}

	/** Only echo a comparison operator we recognise; anything else becomes empty (no injected text). */
	private static String allowedOp(String op)
	{
		if (op == null)
		{
			return "";
		}
		switch (op.trim())
		{
			case ">":
			case "<":
			case ">=":
			case "<=":
			case "=":
			case "==":
			case "above":
			case "below":
				return op.trim();
			default:
				return "";
		}
	}

	/** Render a numeric alert field as a plain number, dropping anything non-numeric the server sent. */
	private static String numeric(JsonObject o, String key)
	{
		JsonElement e = o.get(key);
		if (e == null || e.isJsonNull())
		{
			return "";
		}
		try
		{
			double d = e.getAsDouble();
			return d == Math.floor(d) && !Double.isInfinite(d)
				? String.format("%,d", (long) d) : String.valueOf(d);
		}
		catch (RuntimeException ex)
		{
			return "";
		}
	}

	/**
	 * True only if {@code url} is safe to auto-open/copy: https (or loopback http for a dev server)
	 * pointing at the configured RuneTools host (or a subdomain of it), or a loopback address.
	 */
	private boolean isSafeShareUrl(String url)
	{
		if (url == null || url.trim().isEmpty())
		{
			return false;
		}
		try
		{
			java.net.URI uri = new java.net.URI(url.trim());
			String scheme = uri.getScheme();
			String host = uri.getHost();
			if (scheme == null || host == null)
			{
				return false;
			}
			scheme = scheme.toLowerCase();
			if (!scheme.equals("https"))
			{
				return false;      // https only - no cleartext http, javascript:, file:, custom schemes
			}
			boolean loopback = RuneToolsApiClient.isLoopbackHost(host);
			String baseHost = RuneToolsApiClient.hostOf(api.base());
			boolean sameSite = !baseHost.isEmpty()
				&& (host.equalsIgnoreCase(baseHost) || host.toLowerCase().endsWith("." + baseHost.toLowerCase()));
			return loopback || sameSite;
		}
		catch (Exception ex)
		{
			return false;
		}
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
