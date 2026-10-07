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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetType;
import net.runelite.api.widgets.WidgetUtil;
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
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;
import net.runelite.client.game.ItemEquipmentStats;
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
	description = "Sync GE trades + account to RuneTools (runetools.com) for auto P/L and personalized flips",
	tags = {"grand", "exchange", "ge", "flip", "flipping", "merch", "money", "profit", "runetools", "osrs"}
)
public class RuneToolsPlugin extends Plugin
{
	private static final int COINS_ITEM_ID = 995;
	// The equipment-bonuses interface ("Equip Your Character"), where we inject a "RuneTools DPS"
	// button - copying RuneLite's Wiki "View DPS" button (net.runelite...plugins.wiki.WikiDpsManager).
	private static final int EQUIP_GROUP = InterfaceID.EQUIPMENT;
	private static final int EQUIP_PARENT = InterfaceID.Equipment.CONTENTS;         // inject parent
	private static final int EQUIP_SETBONUS = InterfaceID.Equipment.NEXT_PAGE;      // native "Set Bonus" button (styling ref)
	private static final String PENDING_KEY = "pending";

	// Sync-loop pacing. @Schedule's period is fixed, so backoff is applied as a "not before" gate
	// rather than by rescheduling: the tick still fires every 20s, it just does nothing until the
	// penalty has elapsed. A rejected key can't be waited out, so that stops the loop outright.
	// Each tick only uploads what has changed; asking the site for ITS changes (setups, imported
	// layouts) follows the slower, separate PollSchedule.
	private static final long SYNC_PERIOD_MS = 20_000L;                  // must match the @Schedule below
	private static final long SYNC_BACKOFF_MAX_MS = 10L * 60L * 1000L;   // ten minutes between sweeps, at worst
	private static final int SYNC_AUTH_FAILURES_MAX = 3;                 // consecutive rejected sweeps before we stop
	private static final long RETRY_AFTER_MAX_MS = 60L * 60L * 1000L;    // a 429's Retry-After is honoured up to an hour
	private static final String SYNC_REVOKED_MSG = "Key rejected - click \"Connect to RuneTools\" to reconnect.";
	private static final String SYNC_NAME_TAKEN_MSG = "This character's name is linked to another RuneTools account.";

	// Background endpoints, as SyncParking knows them. Trades are parked per RSN group ("trades:<rsn>").
	private static final String EP_TRADES = "trades:";
	private static final String EP_STATE = "state";
	private static final String EP_COMBAT_ACHIEVEMENTS = "combat-achievements";
	private static final String EP_COLLECTION_LOG = "collection-log";
	private static final String EP_BANK_LAYOUTS = "bank-layouts";

	// The unsynced-trade queue is re-serialised into RuneLite's config on EVERY fill, so it can't be
	// unbounded: a client left offline (or with a dead key) for weeks would otherwise grow a multi-MB
	// blob and rewrite all of it per trade. Past the cap the oldest fills are dropped - the newest are
	// the ones the site's P/L is still missing, and 500 is far beyond any real backlog.
	private static final int MAX_PENDING_TRADES = 500;

	// Bounds on untrusted, server-supplied data so a hostile/compromised server can't crash or flood
	// the client (all confirmed in the security audit - DoS / resource-exhaustion class).
	private static final int MAX_LAYOUT_CELLS = 4096;               // bank layout grid upper bound
	private static final int MAX_IMPORTED_LAYOUTS_PER_POLL = 100;

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

	// Health of the upload loop, shared by every background request rather than tracked per endpoint,
	// so one unreachable/rate-limiting server slows the WHOLE loop down. The scheduler thread reads
	// these and OkHttp callback threads write them, hence the atomics.
	private volatile boolean loggedIn;         // a character is actually in game (nothing to sync otherwise)
	private volatile long nextSyncAtMillis;    // backoff gate: don't start another sweep before this
	private volatile boolean authRevoked;      // key rejected too often; stop until the user reconnects
	private final AtomicInteger syncFailures = new AtomicInteger();
	private final AtomicInteger authFailures = new AtomicInteger();
	private final AtomicLong syncSweep = new AtomicLong();          // one increment per sync() pass
	private final AtomicLong penalizedSweep = new AtomicLong(-1);   // last sweep already charged a failure
	// A 429's Retry-After, as a "not before" time. Separate from nextSyncAtMillis because any 2xx
	// resets that one, and the server's rate limit is per IP, so another endpoint answering fine (or a
	// character switch) doesn't mean the wait is over.
	private final AtomicLong retryAfterUntilMillis = new AtomicLong();
	// Refusals that retrying can't fix (a name linked to another account, a payload the server can
	// never accept), held per endpoint so they aren't re-posted every sweep. See SyncParking.
	private final SyncParking parking = new SyncParking();
	// When to next ask the site for setups and imported bank layouts. See PollSchedule.
	private final PollSchedule remotePoll = new PollSchedule();

	private int inventoryCoins;
	private int bankCoins;
	private int activeOffers;
	private volatile String layoutsEtag;
	private volatile boolean caCatalogSent;
	private volatile int caCompletedHash;
	private boolean caResyncPending;   // client thread only (set on chat, read on tick)
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

	// Whole-log capture. Opening the Collection Log asks the SERVER to transmit every entry once
	// (CollectionLog.requestFullLog), and each entry arrives as its own COLLECTION_TRANSMIT script
	// pre-fire. We collect them into clogAll and flush a couple of ticks after the stream stops.
	// This replaces an earlier version that clicked through every page itself: one button press
	// drove minutes of synthesised interface actions, which is both slow and exactly the kind of
	// automation the Plugin Hub rejects. Client thread only, except clogAllTick which onGameTick reads.
	private static final int CLOG_MAX_ITEMS = 5000;   // the real log is ~1,600; caps a hostile/garbled stream
	private static final int CLOG_REQUEST_TIMEOUT_TICKS = 10;   // no entries by now = the request didn't take
	private final Map<Integer, Integer> clogAll = new LinkedHashMap<>();   // item id -> quantity
	private int clogAllTick = -1;          // tick of the last transmit; -1 = no stream in progress
	private int clogRequestTick = -1;      // tick we asked the server; watchdog for a request that never lands
	private boolean clogRequested;         // already asked the server this time the log was opened
	private boolean clogAllPending;        // a completed stream is waiting to be sent
	private volatile JsonArray clogObtained;   // the complete obtained set, pending send
	// Bumped whenever the queues are discarded (someone else's log, logout, character switch), so a
	// request already in flight can't put stale pages back after the fact.
	private int clogGeneration;

	private Widget dpsButton;   // our injected "RuneTools DPS" widget in the equipment interface (client thread only)

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
		api.setKeySupplier(this::activeApiKey);   // per-character keys: authenticate as the logged-in character
		offerTracker = new OfferTracker();
		sessionTracker = new SessionTracker();
		setupManager = new SetupManager(configManager, api);
		bankFilter = new BankFilterController(bankTagsService, tagManager, layoutManager, itemManager, clientThread);
		bankOrganizer = new BankOrganizer(bankTagsService, tagManager, layoutManager, tabManager, itemManager,
			clientThread, configManager);
		setupManager.setOnDelete(id -> bankFilter.forget(id));
		setupManager.setOnRemoteChange(remotePoll::onChanged);
		loadoutReader = new LoadoutReader(client, itemManager);
		loadPending();

		panel = new RuneToolsPanel(this::onFilterSetup, this::onClearFilter, itemManager,
			this::addCurrentSetup, this::updateFromGear, this::pushSetup, this::deleteSetup, slotEditor, this::openHistory,
			this::organizeBank, this::removeOrganizedTabs, this::convertBankTab, this::exportBankTag, this::openSiteLogin,
			this::checkDps, this::syncAllCollectionLog, this::importInventorySetup);
		setupManager.setOnChange(() -> panel.setSetups(setupManager.getSetups()));
		setupManager.setOnError(msg -> panel.setStatus(msg, false));
		// Opening the side panel is the clearest sign the player wants current setups: poll now
		// instead of waiting out the backoff.
		panel.setOnOpened(() -> requestRemotePoll(PollSchedule.PANEL_GAP_MS));
		panel.setMissingComputer(this::computeMissingItems);
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
		// Drop the reference to our injected equipment DPS button; the client clears the interface's
		// dynamic children (and resets the nudged native buttons) when it's next reopened. We avoid
		// deleteAllChildren() here so we never wipe the Wiki plugin's own button off the shared parent.
		dpsButton = null;
		clientToolbar.removeNavigation(navButton);
		savePending();
		// Drop any captured Collection Log. While the plugin is disabled the event bus is
		// unregistered, so no logout is ever seen - without this, disabling, switching character and
		// re-enabling would upload the previous character's pages under the new one's key.
		discardCollectionCapture();
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
		// The sync loop only runs while a character is in game. LOADING is a region change rather than
		// a logout, so it leaves the gate alone; every other non-LOGGED_IN state closes it.
		if (e.getGameState() == GameState.LOGGED_IN)
		{
			loggedIn = true;
		}
		else if (e.getGameState() != GameState.LOADING)
		{
			loggedIn = false;
		}
		// LOGGING_IN and CONNECTION_LOST are on this list too: a character switch can go straight
		// from one login to the next without passing through LOGIN_SCREEN, and captured state must
		// never survive into a different character's session (the reference plugins reset on the
		// same set).
		if (e.getGameState() == GameState.LOGIN_SCREEN || e.getGameState() == GameState.HOPPING
			|| e.getGameState() == GameState.LOGGING_IN || e.getGameState() == GameState.CONNECTION_LOST)
		{
			// Character context is gone; drop identity so we never attribute to the wrong RSN.
			snapshot.updateAndGet(s -> s.toBuilder().rsn(null).build());
			// Drop any half-read Collection Log: the next character's log must never inherit it.
			discardCollectionCapture();
			// Whoever logs in next gets a clean slate: a different character has a different key, and
			// even the same one may have been reconnected on the site while we sat at the login screen.
			resumeSync();
			// ...and a fresh look at the site's setups as soon as they're in. A world hop keeps the
			// same character, so it keeps the current backoff rather than polling on every hop.
			if (e.getGameState() != GameState.HOPPING)
			{
				remotePoll.reset();
			}
		}
	}

	/** Opening the bank is when setups and imported layouts get used, so fetch any new ones. */
	@Subscribe
	public void onWidgetLoaded(WidgetLoaded e)
	{
		if (e.getGroupId() == InterfaceID.BANKMAIN)
		{
			requestRemotePoll(PollSchedule.BANK_GAP_MS);
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
		// StatChanged also fires for boosted levels (hitpoints regenerating, a potion wearing off),
		// which leave real levels and XP alone. Only an actual change is worth an upload.
		if (skills.equals(snapshot.get().skills))
		{
			return;
		}
		snapshot.updateAndGet(s -> s.toBuilder().skills(skills).build());
		stateDirty.set(true);
	}

	/**
	 * Fail-safe: the moment the game says the open Collection Log belongs to the POH host, drop
	 * anything captured while it was on screen. The capture paths check this too; this catches the
	 * window between the interface opening and the varbit arriving. Client thread.
	 */
	@Subscribe
	public void onVarbitChanged(VarbitChanged e)
	{
		if (e.getVarbitId() == CollectionLog.VB_POH_HOST_BOOK_OPEN && CollectionLog.isOtherPlayers(client))
		{
			discardCollectionCapture();
		}
	}

	@Subscribe
	public void onGameTick(GameTick e)
	{
		updateIdentity();
		// A CA-completion chat message fired: resync now (a tick later, so the completion varp has
		// settled) instead of waiting for the 20s scheduler.
		if (caResyncPending)
		{
			caResyncPending = false;
			if (config.syncCombatAchievements() && syncAllowed())
			{
				syncCombatAchievements();
			}
		}
		finishCollectionLogStream();
	}

	/**
	 * The whole-log transmit arrives as a burst of per-item scripts with no "done" marker, so treat
	 * it as finished once two ticks pass with no new entry (the same settle used by WikiSync and
	 * RuneProfile) and hand the result to the sync queue. Client thread, from onGameTick.
	 */
	private void finishCollectionLogStream()
	{
		// Watchdog: if the request never produced a single entry (a game update moves the search
		// op, or the menu action is swallowed), re-arm instead of leaving the feature off for the
		// rest of the session - clogRequested is what stops us asking again.
		if (clogRequested && clogAllTick < 0 && clogRequestTick >= 0
			&& client.getTickCount() - clogRequestTick > CLOG_REQUEST_TIMEOUT_TICKS)
		{
			clogRequested = false;
			clogRequestTick = -1;
			log.debug("RuneTools: collection log transmit never arrived - will ask again next open");
		}
		if (clogAllTick < 0 || client.getTickCount() - clogAllTick <= 2)
		{
			return;
		}
		clogAllTick = -1;
		clogRequested = false;   // the stream is done; opening the log again may ask for it again
		clogRequestTick = -1;
		if (CollectionLog.isOtherPlayers(client) || clogAll.isEmpty())
		{
			return;
		}
		JsonArray obtained = new JsonArray();
		for (Map.Entry<Integer, Integer> en : clogAll.entrySet())
		{
			JsonObject o = new JsonObject();
			o.addProperty("id", en.getKey());
			if (en.getValue() != null && en.getValue() > 1)
			{
				o.addProperty("qty", en.getValue());
			}
			obtained.add(o);
		}
		synchronized (clogLock)
		{
			clogObtained = obtained;
			clogDirty = true;
		}
		clogAllPending = true;
		if (panel != null)
		{
			if (parking.isNameParked(EP_COLLECTION_LOG, syncIdentity()))
			{
				panel.setStatus(SYNC_NAME_TAKEN_MSG, false);   // read, but it won't be sent as this character
			}
			else
			{
				panel.setStatus("Collection Log read (" + obtained.size() + " items) - syncing…", true);
			}
		}
	}

	/**
	 * Throw away EVERYTHING captured from the collection log so far. Called when the log on screen
	 * turns out to be someone else's, and whenever the character context goes away.
	 *
	 * <p>It has to clear the queued pages too, not just the item stream: the varbit that says "this
	 * is the POH host's log" can land a cycle after the interface draws, so a page of THEIR items
	 * can already be sitting in the queue by the time we find out - and on a character switch, a
	 * page queued by the previous character would otherwise upload under the new one's key and rsn.
	 * clogSentHash goes as well, or the next character silently never uploads a page whose contents
	 * happen to hash the same (very common for content neither of them has started).
	 *
	 * <p>Bumping the generation invalidates any send already in flight, so a failed request can't
	 * put the discarded pages back afterwards.
	 */
	private void discardCollectionCapture()
	{
		clogAll.clear();
		clogAllTick = -1;
		clogRequested = false;
		clogRequestTick = -1;
		synchronized (clogLock)
		{
			clogGeneration++;
			clogObtained = null;
			clogChanged.clear();
			clogChangedHash.clear();
			clogSentHash.clear();
			clogTotals = null;
			clogTotalsHash = 0;
			clogDirty = false;
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

	/**
	 * One entry of the whole-log transmit: {@code args = {?, itemId, quantity}}. Fires once per
	 * obtained item after {@link CollectionLog#requestFullLog}. Client thread.
	 */
	private void collectTransmittedClogItem(ScriptPreFired e)
	{
		if (!config.syncCollectionLog() || CollectionLog.isOtherPlayers(client))
		{
			return;
		}
		Object[] args = e.getScriptEvent() != null ? e.getScriptEvent().getArguments() : null;
		if (args == null || args.length < 3 || !(args[1] instanceof Integer) || !(args[2] instanceof Integer))
		{
			return;
		}
		clogAllTick = client.getTickCount();   // the stream is considered done a couple of ticks after the last one
		int id = (int) args[1];
		int qty = (int) args[2];
		if (id > 0 && clogAll.size() < CLOG_MAX_ITEMS)
		{
			clogAll.put(id, Math.max(0, qty));
		}
	}

	/**
	 * Collection-log interface events. SETUP means the log was just opened: ask the server for the
	 * whole thing once. DRAW means a page was rendered: capture it, which is what gives pages their
	 * names and per-page grouping.
	 *
	 * <p>Both bail out when the log on screen is someone else's, viewed through their POH Adventure
	 * Log. Runs on the client thread.
	 */
	@Subscribe
	public void onScriptPostFired(ScriptPostFired e)
	{
		if (e.getScriptId() == CollectionLog.SETUP_SCRIPT)
		{
			if (!config.syncCollectionLog())
			{
				return;
			}
			if (CollectionLog.isOtherPlayers(client))
			{
				discardCollectionCapture();   // theirs: drop anything the open queued
				return;
			}
			// requestFullLog re-fires SETUP, so only ask once per time the log is opened.
			if (!clogRequested)
			{
				clogRequested = true;
				clogRequestTick = client.getTickCount();
				clogAll.clear();
				CollectionLog.requestFullLog(client);
			}
			return;
		}
		if (e.getScriptId() != CollectionLog.DRAW_SCRIPT || !config.syncCollectionLog())
		{
			return;
		}
		if (CollectionLog.isOtherPlayers(client))
		{
			discardCollectionCapture();
			return;
		}
		if (clogRequested)
		{
			// Mid whole-log request: the search toggle repopulates the item panel, and a draw fired
			// now would be filed under whichever page happened to be open - which then rewrites that
			// page in the SHARED, cross-user item catalog on the server. The stream is carrying the
			// data anyway, so ignore draws until it settles.
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
			AccountSnapshot before = snapshot.get();
			if (inv.equals(before.inventory) && gp == before.gp)
			{
				return;   // same items (e.g. only rearranged): nothing new to upload
			}
			snapshot.updateAndGet(s -> s.toBuilder().inventory(inv).gp(gp).build());
			stateDirty.set(true);
		}
		else if (e.getContainerId() == InventoryID.BANK.getId())
		{
			bankCoins = c.count(COINS_ITEM_ID);
			final JsonArray bank = config.syncBank() ? itemsToArray(c) : null;
			final long gp = (long) inventoryCoins + (long) bankCoins;
			AccountSnapshot before = snapshot.get();
			if (gp == before.gp && (bank == null || bank.equals(before.bank)))
			{
				return;
			}
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
			queuePending(t);
			savePending();
			updatePanelStatic();
			log.debug("RuneTools: queued {} {} x item {} @ {}gp",
				t.getSide(), t.getQty(), t.getItemId(), t.getPrice());
			// A buy fill is what the site's holdings/P-L view is most often waiting on, so expedite the
			// flush instead of leaving it to the next 20s sweep.
			if ("buy".equals(t.getSide()) && syncAllowed())
			{
				flushTrades();
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
		// Ticks only fire in game, so this also opens the sync gate when the plugin is ENABLED
		// mid-session, where no GameStateChanged ever arrives to open it.
		loggedIn = true;
		Player p = client.getLocalPlayer();
		String name = p != null ? p.getName() : null;
		if (name == null || name.isEmpty())
		{
			return;
		}
		boolean firstIdentity = snapshot.get().rsn == null;
		long ah = client.getAccountHash();
		final String hash = ah == -1 ? null : Long.toString(ah);
		final boolean members = client.getWorldType().contains(WorldType.MEMBERS);
		snapshot.updateAndGet(s -> s.toBuilder().rsn(name).accountHash(hash).members(members).build());
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
		if (!syncAllowed())
		{
			return;   // login screen, no key for this character, no consent, or the key was rejected
		}
		if (System.currentTimeMillis() < nextSyncAtMillis)
		{
			return;   // still backing off from a failed sweep (server down, rate-limited, ...)
		}
		syncSweep.incrementAndGet();   // every response below reports into THIS sweep's health
		flushTrades();                       // needs only the API key (it identifies the account)
		if (snapshot.get().hasIdentity())
		{
			flushState();                    // account state is per-(user, RSN), so it needs the RSN
		}
		else
		{
			log.debug("RuneTools: state sync waiting for in-game identity (RSN not captured yet)");
		}
		pollRemoteIfDue();                   // setups + imported layouts, on their own slower schedule
		if (config.syncCombatAchievements())
		{
			syncCombatAchievements();
		}
		if (config.syncCollectionLog())
		{
			refreshCollectionTotals();   // keep overall progress fresh even if the log isn't open
			syncCollectionLog();
		}
	}

	/**
	 * Ask the site for setup and bank-layout changes, if {@link #remotePoll} says one is due. Both
	 * requests are ETag polls (a 304 when nothing changed) and are only queued here, so this returns
	 * at once and is safe from any thread. Gated like the rest of the background loop: nothing goes
	 * out unless a connected, consenting character is logged in and the server isn't backing us off.
	 */
	private void pollRemoteIfDue()
	{
		if (!config.syncSetups() || !syncAllowed() || System.currentTimeMillis() < nextSyncAtMillis)
		{
			return;
		}
		if (!remotePoll.tryStart(System.currentTimeMillis()))
		{
			return;
		}
		setupManager.poll();             // full pull + ETag -> 304 when unchanged
		pollImportedLayouts();           // apply bank layouts imported on the site
	}

	/**
	 * The player is about to use synced data (opened the side panel or the bank): poll now, or as soon
	 * as {@code minGap} has passed since the last poll, instead of waiting out the backoff.
	 */
	private void requestRemotePoll(long minGap)
	{
		remotePoll.pollSoon(System.currentTimeMillis(), minGap);
		pollRemoteIfDue();
	}

	/**
	 * Gate for the BACKGROUND loop: there has to be something to sync (a character in game, a key for
	 * that character, consent), the key must not already have been rejected, and the server must not
	 * have asked us to wait (a 429's Retry-After). Without the in-game check a client parked on the
	 * login screen posted every 20s forever - ~21,600 requests a day that can only ever be no-ops.
	 * User-initiated actions keep using {@link #hasKeyAndConsent()}: an explicit click should always be
	 * attempted, however the background loop is feeling.
	 */
	private boolean syncAllowed()
	{
		return loggedIn && !authRevoked && System.currentTimeMillis() >= retryAfterUntilMillis.get()
			&& hasKeyAndConsent();
	}

	/**
	 * Back to the normal period: no penalty, no rejected-key halt. A 429's Retry-After is left alone:
	 * the rate limit is per IP, so it outlasts a character switch or a new key.
	 */
	private void resumeSync()
	{
		syncFailures.set(0);
		authFailures.set(0);
		authRevoked = false;
		nextSyncAtMillis = 0;
	}

	/** A transport-level failure (no response at all) is charged the same as a 5xx. */
	private void noteSyncFailure()
	{
		chargeSyncFailure(0, false);
	}

	/**
	 * Every background response funnels through here so the whole loop shares ONE health signal:
	 * a failure slows every endpoint down instead of each carrying on hammering independently, and any
	 * 2xx puts it straight back to the normal period. See {@link SyncResponse} for how each status is
	 * read.
	 *
	 * <p>{@code endpoint}, {@code identity} and {@code payload} say what was sent, so a refusal that
	 * retrying can't fix is parked rather than re-posted every sweep: a name linked to another
	 * RuneTools account (409, or 403 {@code rsn_taken}) holds the endpoint until the character or key
	 * changes, and a 400/413 holds that exact payload until it changes. Neither is an outage or a bad
	 * key, so neither slows the loop down or counts toward the rejected-key halt.
	 *
	 * <p>Returns the outcome so the caller can decide whether to keep the payload queued.
	 */
	private SyncResponse.Outcome noteSyncResult(Response r, String endpoint, String identity, long payload)
	{
		int code = r.code();
		String errorCode = SyncResponse.needsErrorCode(code) ? errorCodeOf(r) : null;
		SyncResponse.Outcome outcome = SyncResponse.classify(code, errorCode);
		switch (outcome)
		{
			case OK:
				parking.release(endpoint, identity);
				resumeSync();
				break;
			case IGNORED:
				break;
			case NAME_TAKEN:
				parking.parkName(endpoint, identity);
				updatePanelStatic();
				log.debug("RuneTools: {} refused (HTTP {}) - this character's name is linked to another RuneTools"
					+ " account; holding it until the character or key changes", endpoint, code);
				break;
			case PAYLOAD_REJECTED:
				parking.parkPayload(endpoint, identity, payload);
				log.debug("RuneTools: {} rejected the payload (HTTP {}) - holding it until it changes", endpoint, code);
				break;
			case RATE_LIMITED:
				long now = System.currentTimeMillis();
				long wait = SyncResponse.retryAfterMillis(r.header("Retry-After"), now, RETRY_AFTER_MAX_MS);
				if (wait > 0)
				{
					// Set before the once-per-sweep charge below, so it holds even when another response
					// already charged this sweep.
					retryAfterUntilMillis.accumulateAndGet(now + wait, Math::max);
					log.debug("RuneTools: rate limited - server asked for {}s", wait / 1000L);
				}
				chargeSyncFailure(code, false);
				break;
			case KEY_REJECTED:
				chargeSyncFailure(code, true);
				break;
			default:
				chargeSyncFailure(code, false);
				break;
		}
		return outcome;
	}

	/** The {@code code} of a JSON error body, read without buffering more than a small error page. */
	private static String errorCodeOf(Response r)
	{
		try
		{
			return r.body() != null ? SyncResponse.errorCode(r.peekBody(16 * 1024).string()) : null;
		}
		catch (IOException | RuntimeException ex)
		{
			return null;
		}
	}

	/**
	 * Charge one failed response to the loop's backoff ({@code code} is 0 for a transport failure).
	 * {@code keyRejected} marks a 401 (or a 403 that isn't a name conflict), which also counts toward
	 * stopping the loop outright.
	 */
	private void chargeSyncFailure(int code, boolean keyRejected)
	{
		// One sweep fires a dozen requests, so charge only the FIRST bad response of each. Otherwise a
		// single outage would count a dozen failures at once and vault straight to the ceiling (and
		// trip the rejected-key halt) before we had retried even once.
		long sweep = syncSweep.get();
		if (penalizedSweep.getAndSet(sweep) == sweep)
		{
			return;
		}
		if (keyRejected)
		{
			if (authFailures.incrementAndGet() >= SYNC_AUTH_FAILURES_MAX)
			{
				// A revoked key never heals on its own: stop, and put the fix in the panel instead of
				// posting a dead key at the server until the client is closed.
				authRevoked = true;
				updatePanelStatic();
				log.warn("RuneTools: sync stopped after {} rejected sweeps - reconnect this character",
					SYNC_AUTH_FAILURES_MAX);
				return;
			}
		}
		else
		{
			authFailures.set(0);   // "consecutive" rejections - any other failure breaks the streak
		}
		int fails = syncFailures.incrementAndGet();
		long wait = Math.min(SYNC_BACKOFF_MAX_MS, SYNC_PERIOD_MS << Math.min(fails, 5));
		nextSyncAtMillis = System.currentTimeMillis() + wait;
		log.debug("RuneTools: sync failure #{} (HTTP {}) - next sweep in {}s", fails, code, wait / 1000L);
	}

	/**
	 * Who a background request goes out as: the character (name + account hash) and the key it
	 * authenticates with. Parks are scoped to this, so another character, a rename or a newly-issued
	 * key always gets a fresh try. Only the key's hash is kept.
	 */
	private String syncIdentity(String rsn, String accountHash)
	{
		return (rsn != null ? rsn : "") + '|' + (accountHash != null ? accountHash : "") + '|'
			+ Integer.toHexString(api.activeKey().hashCode());
	}

	/** {@link #syncIdentity(String, String)} for the character logged in right now. */
	private String syncIdentity()
	{
		AccountSnapshot s = snapshot.get();
		return syncIdentity(s.rsn, s.accountHash);
	}

	/** Assemble the panel's Combat Achievements view from the last cache read. */
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

		panel.setCaProgress(overall, next, rows);
	}

	/**
	 * One click: sync your WHOLE Collection Log. Requires the log to be open in-game, then asks the
	 * server to transmit every entry at once - a single action, no clicking through pages. It
	 * changes nothing in-game: the interface is put back exactly as you left it.
	 *
	 * <p>Opening the log already does this automatically; the button is for re-reading it on demand.
	 */
	private void syncAllCollectionLog()
	{
		if (blockedByConsent())
		{
			return;
		}
		if (!config.syncCollectionLog())
		{
			panel.setStatus("Turn on \"Sync Collection Log\" in settings first.", false);
			return;
		}
		clientThread.invoke(() ->
		{
			if (client.getGameState() != GameState.LOGGED_IN || !CollectionLog.isOpen(client))
			{
				panel.setStatus("Open your Collection Log in-game, then click Sync all.", false);
				return;
			}
			if (CollectionLog.isOtherPlayers(client))
			{
				panel.setStatus("That's someone else's Collection Log - open your own.", false);
				return;
			}
			// Ask the server for the whole log again. One request, no clicking through pages.
			clogAll.clear();
			clogRequested = true;
			CollectionLog.requestFullLog(client);
			refreshCollectionTotals();   // overall counts land immediately
			panel.setStatus("Reading your Collection Log…", true);
		});
	}

	/** POST any collection-log totals/pages changed since the last successful sync. Scheduler thread. */
	private void syncCollectionLog()
	{
		JsonObject totals;
		JsonArray obtained;
		// Key -> page, kept together in ONE map: these two used to be a values() list and a separate
		// hash map zipped by position on retry, which re-filed pages under each other's keys the
		// moment a send failed.
		final Map<String, JsonObject> sentPages = new LinkedHashMap<>();
		final Map<String, Integer> sentHashes = new HashMap<>();
		final int generation;
		final String identity = syncIdentity();
		synchronized (clogLock)
		{
			if (!clogDirty)
			{
				return;
			}
			if (parking.isNameParked(EP_COLLECTION_LOG, identity))
			{
				return;   // the name is linked to another account: keep it all queued for another character/key
			}
			generation = clogGeneration;
			totals = clogTotals;
			obtained = clogObtained;
			for (Map.Entry<String, JsonObject> en : clogChanged.entrySet())
			{
				sentPages.put(en.getKey(), en.getValue());
				Integer h = clogChangedHash.get(en.getKey());
				if (h != null)
				{
					sentHashes.put(en.getKey(), h);
				}
			}
			// Optimistically dequeue; re-queue on failure so a page is never silently lost.
			clogChanged.clear();
			clogChangedHash.clear();
			clogObtained = null;
			clogDirty = false;
		}
		List<JsonObject> pages = new ArrayList<>(sentPages.values());
		if (totals == null && pages.isEmpty() && obtained == null)
		{
			return;
		}
		JsonObject body = new JsonObject();
		stampIdentity(body);
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
		// The complete obtained set from the whole-log transmit. Pages carry the names and grouping
		// for whatever the player has actually opened; this carries everything, and the site fills
		// the rest of the pages in from it.
		if (obtained != null)
		{
			body.add("obtained", obtained);
		}
		final JsonArray sentObtained = obtained;
		final long payload = body.toString().hashCode();
		if (parking.isParked(EP_COLLECTION_LOG, identity, payload))
		{
			// The server already refused exactly this. Keep it queued, unsent: a newly-read page
			// changes the payload and earns another try.
			requeueCollectionPages(generation, sentPages, sentHashes, sentObtained);
			return;
		}
		api.postCollectionLog(body, new Callback()
		{
			@Override
			public void onFailure(Call call, IOException ex)
			{
				requeueCollectionPages(generation, sentPages, sentHashes, sentObtained);
				noteSyncFailure();
				log.debug("RuneTools: collection-log sync failed (will retry): {}", ex.getMessage());
			}

			@Override
			public void onResponse(Call call, Response resp)
			{
				try (Response r = resp)
				{
					noteSyncResult(r, EP_COLLECTION_LOG, identity, payload);
					if (r.isSuccessful())
					{
						synchronized (clogLock)
						{
							clogSentHash.putAll(sentHashes);   // don't re-send these unchanged pages
						}
						clogAllPending = false;
					}
					else
					{
						requeueCollectionPages(generation, sentPages, sentHashes, sentObtained);
						log.debug("RuneTools: collection-log sync HTTP {}", r.code());
					}
				}
			}
		});
	}

	/**
	 * Put pages back on the queue after a failed send so the next tick retries them. Each page goes
	 * back under its OWN key with its OWN hash, and a newer read of the same page always wins.
	 */
	private void requeueCollectionPages(int generation, Map<String, JsonObject> pages,
										Map<String, Integer> hashes, JsonArray obtained)
	{
		synchronized (clogLock)
		{
			if (generation != clogGeneration)
			{
				// The queue was discarded while this request was in flight (someone else's log, a
				// logout, a character switch). Putting it back would undo exactly that.
				return;
			}
			for (Map.Entry<String, JsonObject> en : pages.entrySet())
			{
				String key = en.getKey();
				if (clogChanged.containsKey(key))
				{
					continue;   // a newer read of this page is already queued
				}
				Integer sent = clogSentHash.get(key);
				Integer mine = hashes.get(key);
				if (sent != null && mine != null && !sent.equals(mine))
				{
					continue;   // a newer read of this page already made it to the server
				}
				clogChanged.put(key, en.getValue());
				Integer h = hashes.get(key);
				if (h != null)
				{
					clogChangedHash.put(key, h);
				}
			}
			if (obtained != null && clogObtained == null)
			{
				clogObtained = obtained;
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
			// What this post would carry, cheaply: the catalogue (~145 KB) or not, plus the completion
			// state. Checked before building the body, so a refused catalogue costs nothing per sweep.
			final String identity = syncIdentity();
			final long payload = ((long) snap.completedHash << 1) | (sendCatalog ? 1L : 0L);
			if (parking.isParked(EP_COMBAT_ACHIEVEMENTS, identity, payload))
			{
				return;   // already refused for this character (name) or for exactly this content
			}
			JsonObject body = new JsonObject();
			stampIdentity(body);
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
					noteSyncFailure();
					log.debug("RuneTools: CA sync failed: {}", ex.getMessage());
				}

				@Override
				public void onResponse(Call call, Response resp)
				{
					try (Response r = resp)
					{
						noteSyncResult(r, EP_COMBAT_ACHIEVEMENTS, identity, payload);
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
		final String identity = syncIdentity();
		if (parking.isParked(EP_BANK_LAYOUTS, identity, 0L))
		{
			return;   // a GET has no payload to change, so a refusal holds until the character or key does
		}
		final String sentEtag = layoutsEtag;
		api.getBankLayouts(sentEtag, new Callback()
		{
			@Override
			public void onFailure(Call call, IOException ex)
			{
				noteSyncFailure();
				log.debug("RuneTools: layout sync failed: {}", ex.getMessage());
			}

			@Override
			public void onResponse(Call call, Response resp)
			{
				try (Response r = resp)
				{
					noteSyncResult(r, EP_BANK_LAYOUTS, identity, 0L);
					if (r.code() == 304 || r.code() == 401 || !r.isSuccessful())
					{
						return;
					}
					String body = RuneToolsApiClient.readBoundedBody(r);
					JsonObject o = new JsonParser().parse(body).getAsJsonObject();
					String received = o.has("etag") && !o.get("etag").isJsonNull() ? o.get("etag").getAsString() : null;
					if (received != null)
					{
						layoutsEtag = received;
					}
					if (SetupManager.etagChanged(sentEtag, received))
					{
						remotePoll.onChanged();   // something was added on the site; more may follow
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
						// Mark applied FIRST so a payload that somehow still errors can never be
						// re-applied on every poll; the allocation is already bounded in cellsToArray.
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
					price = itemManager.getItemPrice(id) * qty;
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
	 * Publish the currently-open bank tag (its layout + items) to the user's runetools.com profile,
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

	/** Create a RuneTools setup from one imported out of the Inventory Setups plugin (saved to the site). */
	private void importInventorySetup(GearSetup s)
	{
		if (s == null || blockedByConsent())
		{
			return;
		}
		setupManager.pushCreate(s);
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
		panel.setStatus(activeApiKey() == null
			? "Connect this character first - click \"Open RuneTools\"."
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
		return config.consent() && activeApiKey() != null;
	}

	// --- Per-character API keys: each game account gets its OWN RuneTools account + key, so a main
	// and an ironman/GIM never share one profile. Keys are stored per account hash ("key_<hash>"). ---

	/** The API key for the CURRENTLY logged-in character, or null if this character isn't linked yet. */
	private String activeApiKey()
	{
		return keyForHash(snapshot.get().accountHash);
	}

	/**
	 * Stamp the logged-in character's identity (rsn + account_hash) onto a sync body. The site
	 * publishes the collection log and combat achievements under the RSN, so it will only accept
	 * a name this character has linked - the account hash is what proves the link on a first sync.
	 */
	private void stampIdentity(JsonObject body)
	{
		AccountSnapshot s = snapshot.get();
		if (s.rsn != null)
		{
			body.addProperty("rsn", s.rsn);
		}
		if (s.accountHash != null)
		{
			body.addProperty("account_hash", s.accountHash);
		}
	}

	private String keyForHash(String hash)
	{
		if (hash == null || hash.isEmpty())
		{
			return null;   // no character logged in yet
		}
		String k = configManager.getConfiguration(RuneToolsConfig.GROUP, keyConfigKey(hash));
		return k != null && !k.trim().isEmpty() ? k.trim() : null;
	}

	private void storeKeyForHash(String hash, String key)
	{
		if (hash != null && !hash.isEmpty() && key != null && !key.trim().isEmpty())
		{
			configManager.setConfiguration(RuneToolsConfig.GROUP, keyConfigKey(hash), key.trim());
			resumeSync();   // a freshly-issued key is the fix for a rejected one: start syncing again
		}
	}

	private static String keyConfigKey(String hash)
	{
		return "key_" + hash;
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
		for (Map.Entry<String, List<Trade>> entry : byRsn.entrySet())
		{
			List<Trade> group = entry.getValue();
			String rsn = entry.getKey().isEmpty() ? null : entry.getKey();
			String hash = hashByRsn.get(entry.getKey());
			final String endpoint = EP_TRADES + entry.getKey();
			final String identity = syncIdentity(rsn, hash);
			final long payload = group.hashCode();
			if (parking.isParked(endpoint, identity, payload))
			{
				continue;   // refused before; the fills stay queued until the character, key or batch changes
			}
			log.debug("RuneTools: flushing {} trade(s) to {}", group.size(), api.base());
			api.postTrades(rsn, hash, group, new Callback()
			{
				@Override
				public void onFailure(Call call, IOException ex)
				{
					noteSyncFailure();
					log.debug("RuneTools: trade sync failed (will retry): {}", ex.getMessage());
				}

				@Override
				public void onResponse(Call call, Response resp)
				{
					try (Response r = resp)
					{
						SyncResponse.Outcome outcome = noteSyncResult(r, endpoint, identity, payload);
						if (r.isSuccessful())
						{
							synchronized (pending)
							{
								pending.removeAll(group);
							}
							savePending();
							panel.setLastSync(nowString());
							updatePanelStatic();
							log.debug("RuneTools: synced {} trade(s) OK", group.size());
						}
						else if (r.code() == 401)
						{
							panel.setStatus(SYNC_REVOKED_MSG, false);
							log.debug("RuneTools: trade sync rejected (401) - bad/revoked key");
						}
						else if (outcome == SyncResponse.Outcome.NAME_TAKEN
							|| outcome == SyncResponse.Outcome.PAYLOAD_REJECTED)
						{
							log.debug("RuneTools: trade sync HTTP {} - fills kept queued, not resent as-is", r.code());
						}
						else
						{
							log.debug("RuneTools: trade sync HTTP {} (will retry)", r.code());
						}
					}
				}
			});
		}
	}

	private void flushState()
	{
		if (!config.syncState())
		{
			return;
		}
		if (!stateDirty.get())
		{
			return;
		}
		final String identity = syncIdentity();
		if (parking.isNameParked(EP_STATE, identity))
		{
			return;   // the name is linked to another account; left dirty so another character or key sends it
		}
		// Clear up-front so a change that lands mid-request re-marks it dirty and is not lost.
		stateDirty.set(false);
		// Send one coherent, immutable cut. The payload hash leaves out the capture time, which
		// differs on every send, so a refused state is recognised if nothing has changed since.
		JsonObject body = RuneToolsApiClient.stateBody(snapshot.get());
		final long payload = body.toString().hashCode();
		if (parking.isParked(EP_STATE, identity, payload))
		{
			return;   // exactly this state was refused; the next change re-marks it dirty and is tried
		}
		body.addProperty("ts", System.currentTimeMillis());
		api.postState(body, new Callback()
		{
			@Override
			public void onFailure(Call call, IOException ex)
			{
				stateDirty.set(true);
				noteSyncFailure();
				log.debug("RuneTools: state sync failed (will retry): {}", ex.getMessage());
			}

			@Override
			public void onResponse(Call call, Response resp)
			{
				try (Response r = resp)
				{
					SyncResponse.Outcome outcome = noteSyncResult(r, EP_STATE, identity, payload);
					if (r.isSuccessful())
					{
						log.debug("RuneTools: state synced OK");
					}
					else if (r.code() == 401)
					{
						panel.setStatus(SYNC_REVOKED_MSG, false);
						log.debug("RuneTools: state sync rejected (401) - bad/revoked key");
					}
					else if (outcome == SyncResponse.Outcome.PAYLOAD_REJECTED)
					{
						// Parked, and deliberately not re-marked dirty: the next real change sends again.
						log.debug("RuneTools: state sync HTTP {} - not resending this state", r.code());
					}
					else
					{
						stateDirty.set(true);
						log.debug("RuneTools: state sync HTTP {} (will retry)", r.code());
					}
				}
			}
		});
	}

	// -- panel + connection test --------------------------------------------- //

	/**
	 * One-click "open the site signed in": mint a browser-login code from the plugin's key (or, on a
	 * brand-new install, bootstrap an account keyed by the game account hash), store any key we're
	 * issued so future syncs are authenticated, then open the site already logged in. Consent-gated,
	 * since it links this character's name + account hash to a RuneTools account.
	 */
	private void openSiteLogin()
	{
		if (!config.consent())
		{
			panel.setStatus("Enable consent in settings to connect your account.", false);
			return;
		}
		clientThread.invoke(() ->
		{
			Player p = client.getGameState() == GameState.LOGGED_IN ? client.getLocalPlayer() : null;
			final String rsn = p != null ? p.getName() : null;
			if (rsn == null || rsn.isEmpty())
			{
				panel.setStatus("Log into the game first, then open the site.", false);
				return;
			}
			long ah = client.getAccountHash();
			final String accountHash = ah == -1 ? null : Long.toString(ah);
			panel.setStatus("Opening the site…", true);
			api.webLogin(accountHash, rsn, new Callback()
			{
				@Override
				public void onFailure(Call call, IOException ex)
				{
					panel.setStatus("Couldn't reach the site - are you online?", false);
				}

				@Override
				public void onResponse(Call call, Response resp)
				{
					try (Response r = resp)
					{
						if (r.code() == 403)
						{
							panel.setStatus("This account is secured - sign in with your email on the site.", false);
							return;
						}
						if (!r.isSuccessful())
						{
							panel.setStatus("Sign-in failed (HTTP " + r.code() + ").", false);
							return;
						}
						JsonObject o = new JsonParser().parse(RuneToolsApiClient.readBoundedBody(r)).getAsJsonObject();
						// First run hands us a key; persist it so background syncs are authenticated too.
						String key = str(o, "api_key", null);
						if (key != null && key.startsWith("rtk_"))
						{
							storeKeyForHash(accountHash, key);   // this character's own key (per-character accounts)
							updatePanelStatic();   // flip immediately to "Connected as ..." / "Open RuneTools"
						}
						String url = str(o, "url", null);
						// Only ever open a link to our own (configured) server - never an arbitrary URL.
						final String full = url != null ? api.base() + url : null;
						if (full != null && isSafeShareUrl(full))
						{
							SwingUtilities.invokeLater(() ->
							{
								panel.setStatus("Opening the site in your browser…", true);
								LinkBrowser.browse(full);
							});
						}
						else
						{
							panel.setStatus("Signed in - open runetools.com to view your account.", true);
						}
					}
					catch (Exception ex)
					{
						panel.setStatus("Unexpected response from the site.", false);
					}
				}
			});
		});
	}

	// Colours copied from WikiDpsManager (the game's stone-button text colours).
	private static final int DPS_BTN_COLOUR = 0xff981f;
	private static final int DPS_BTN_HOVER = 0xffffff;

	/**
	 * Inject a "RuneTools DPS" button into the equipment-bonuses interface ("Equip Your Character"),
	 * copying RuneLite's own Wiki "View DPS" button: the same trigger (the stat-bonus setup script)
	 * and the same technique of cloning the native "Set Bonus" button's sprites so ours matches the
	 * game UI exactly. Best-effort: any widget-API hiccup is caught so it can never break the plugin.
	 */
	@Subscribe
	public void onScriptPreFired(ScriptPreFired e)
	{
		if (e.getScriptId() == CollectionLog.TRANSMIT_SCRIPT)
		{
			collectTransmittedClogItem(e);
			return;
		}
		if (e.getScriptId() != ScriptID.EQUIPMENT_SET_STAT_BONUS_SETUP)
		{
			return;
		}
		Object[] args = e.getScriptEvent() != null ? e.getScriptEvent().getArguments() : null;
		if (args == null || args.length < 5 || !(args[1] instanceof Integer) || !(args[4] instanceof Integer))
		{
			return;
		}
		if ((int) args[4] == 1 || WidgetUtil.componentToInterface((int) args[1]) != EQUIP_GROUP)
		{
			return;   // only the stats page (not the set-bonus page) of the equipment interface
		}
		try
		{
			// The setup script rebuilds the interface's children on every fire (destroying our old
			// button), so re-add it each time - the same as the Wiki plugin - rather than dedup-guard;
			// otherwise toggling to Set Bonus and back would leave our button gone until reopen.
			dpsButton = null;
			addDpsButton();
		}
		catch (RuntimeException ex)
		{
			log.debug("RuneTools: couldn't add equipment DPS button: {}", ex.toString());
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed e)
	{
		if (e.getGroupId() == EQUIP_GROUP)
		{
			dpsButton = null;   // dynamic children are destroyed with the interface; allow a fresh add
		}
	}

	/**
	 * Build a "RuneTools DPS" button in the equipment-bonuses interface by cloning the native "Set
	 * Bonus" button's 9 sprite pieces + text (the WikiDpsManager technique), placed in the free space
	 * at the top-left of the interface so it doesn't collide with the game's / Wiki plugin's buttons.
	 * Re-run on every setup-script fire (onScriptPreFired resets {@code dpsButton} first).
	 */
	private void addDpsButton()
	{
		Widget parent = client.getWidget(EQUIP_PARENT);
		Widget setBonus = client.getWidget(EQUIP_SETBONUS);
		Widget[] ref;
		if (parent == null || setBonus == null || (ref = setBonus.getChildren()) == null || ref.length < 10)
		{
			return;
		}
		// Clone the native "Set Bonus" button's look, but drop ours in the free space at the TOP-LEFT
		// of the interface (left of the helmet slot) rather than the crowded bottom row - the bottom
		// already holds the game's Set Bonus + the Wiki plugin's View DPS, so ours was getting clipped.
		final int w = setBonus.getOriginalWidth();
		final int h = setBonus.getOriginalHeight();
		final int x = 4;
		final int y = 4;
		log.debug("RuneTools equip button: parent {}x{}, setBonus x={} y={} w={} h={}",
			parent.getWidth(), parent.getHeight(), setBonus.getOriginalX(), setBonus.getOriginalY(), w, h);

		int bgWidth = w - ref[0].getOriginalWidth();
		int bgHeight = h - ref[0].getOriginalHeight();
		int bgX = x + ref[0].getOriginalX() + (w - bgWidth) / 2;
		int bgY = y + ref[0].getOriginalY() + (h - bgHeight) / 2;
		parent.createChild(-1, WidgetType.GRAPHIC)
			.setSpriteId(ref[0].getSpriteId())
			.setPos(bgX, bgY)
			.setSize(bgWidth, bgHeight)
			.revalidate();

		for (int i = 1; i < 9; i++)
		{
			parent.createChild(-1, WidgetType.GRAPHIC)
				.setSpriteId(ref[i].getSpriteId())
				.setSize(ref[i].getOriginalWidth(), ref[i].getOriginalHeight())
				.setPos(x + ref[i].getOriginalX(), y + ref[i].getOriginalY())
				.revalidate();
		}

		final Widget text = parent.createChild(-1, WidgetType.TEXT)
			.setText("RuneTools DPS")
			.setTextColor(DPS_BTN_COLOUR)
			.setFontId(ref[9].getFontId())
			.setTextShadowed(ref[9].getTextShadowed())
			.setXTextAlignment(ref[9].getXTextAlignment())
			.setYTextAlignment(ref[9].getYTextAlignment())
			.setPos(x, y)
			.setSize(w, h);
		text.setHasListener(true);
		text.setOnMouseOverListener((JavaScriptCallback) ev -> text.setTextColor(DPS_BTN_HOVER));
		text.setOnMouseLeaveListener((JavaScriptCallback) ev -> text.setTextColor(DPS_BTN_COLOUR));
		text.setAction(0, "View DPS with RuneTools");
		text.setOnOpListener((JavaScriptCallback) ev -> checkDps());
		text.revalidate();
		parent.revalidate();
		dpsButton = text;
	}

	/**
	 * Open the RuneTools DPS calculator pre-filled with the gear you're currently wearing. Reads the
	 * worn equipment on the client thread, encodes the item ids, and opens {@code /calculators/dps} -
	 * the site resolves each item to its slot and infers your combat style from the weapon. No account
	 * data is sent (the ids ride the URL the browser opens), so this isn't consent-gated.
	 */
	private void checkDps()
	{
		clientThread.invoke(() ->
		{
			if (client.getGameState() != GameState.LOGGED_IN)
			{
				panel.setStatus("Log into the game first, then check DPS.", false);
				return;
			}
			ItemContainer eq = client.getItemContainer(InventoryID.EQUIPMENT);
			if (eq == null)
			{
				panel.setStatus("Equip some gear first, then check DPS.", false);
				return;
			}
			// Send an explicit slot->id map plus the combat style, not a flat id list. The site then
			// never has to guess a slot, and - crucially - the style comes from the worn weapon's own
			// stats, so a charged weapon (trident/sceptre/shadow) whose id isn't in the site's gear
			// catalog still loads as magic/ranged instead of silently defaulting to melee.
			StringBuilder gear = new StringBuilder();
			String style = null;
			for (EquipSlot s : EQUIP_SLOTS)
			{
				Item it = eq.getItem(s.slot.getSlotIdx());
				if (it == null || it.getId() <= 0)
				{
					continue;
				}
				if (gear.length() > 0)
				{
					gear.append(',');
				}
				gear.append(s.name).append(':').append(itemManager.canonicalize(it.getId()));
				if (s.slot == EquipmentInventorySlot.WEAPON)
				{
					style = weaponStyle(it.getId());
				}
			}
			if (gear.length() == 0)
			{
				panel.setStatus("Equip some gear first, then check DPS.", false);
				return;
			}
			String url = api.base() + "/calculators/dps?gear=" + gear;
			if (style != null)
			{
				url += "&style=" + style;
			}
			if (isSafeShareUrl(url))
			{
				panel.setStatus("Opening the DPS calculator with your gear…", true);
				final String open = url;
				SwingUtilities.invokeLater(() -> LinkBrowser.browse(open));
			}
		});
	}

	/**
	 * The worn combat style inferred from a weapon's own equipment bonuses (magic / ranged / melee) -
	 * catalog-independent, so it's correct even for a charged weapon the site's gear list doesn't know.
	 * Returns null if the item has no equipment stats (then the site keeps its default).
	 */
	private String weaponStyle(int itemId)
	{
		ItemStats st = itemManager.getItemStats(itemId);
		if (st == null)
		{
			return null;
		}
		ItemEquipmentStats e = st.getEquipment();
		if (e == null)
		{
			return null;
		}
		int mag = e.getAmagic();
		int rng = e.getArange();
		int mel = Math.max(e.getAstab(), Math.max(e.getAslash(), e.getAcrush()));
		if (mag > mel && mag >= rng)
		{
			return "magic";
		}
		if (rng > mel && rng >= mag)
		{
			return "ranged";
		}
		return "melee";
	}

	/** Equipment slot -> the DPS site's slot name, in the order the handoff lists them. */
	private static final class EquipSlot
	{
		final EquipmentInventorySlot slot;
		final String name;

		EquipSlot(EquipmentInventorySlot slot, String name)
		{
			this.slot = slot;
			this.name = name;
		}
	}

	private static final EquipSlot[] EQUIP_SLOTS = {
		new EquipSlot(EquipmentInventorySlot.HEAD, "head"),
		new EquipSlot(EquipmentInventorySlot.CAPE, "cape"),
		new EquipSlot(EquipmentInventorySlot.AMULET, "neck"),
		new EquipSlot(EquipmentInventorySlot.WEAPON, "weapon"),
		new EquipSlot(EquipmentInventorySlot.BODY, "body"),
		new EquipSlot(EquipmentInventorySlot.SHIELD, "shield"),
		new EquipSlot(EquipmentInventorySlot.LEGS, "legs"),
		new EquipSlot(EquipmentInventorySlot.GLOVES, "hands"),
		new EquipSlot(EquipmentInventorySlot.BOOTS, "feet"),
		new EquipSlot(EquipmentInventorySlot.RING, "ring"),
		new EquipSlot(EquipmentInventorySlot.AMMO, "ammo"),
	};

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
		boolean linked = activeApiKey() != null;
		// A key the server keeps rejecting is functionally "not connected", so put the CTA back to
		// "Connect to RuneTools" - re-linking the character is exactly what mints a working key.
		panel.setConnectButton(linked && !authRevoked);   // "Connect to RuneTools" vs "Open RuneTools"
		if (!snapshot.get().hasIdentity())
		{
			panel.setStatus("Waiting for login", true);
		}
		else if (!linked)
		{
			panel.setStatus("Not connected", false);
		}
		else if (authRevoked)
		{
			// Sticky: every other panel update would otherwise paint "Connected as ..." back over the
			// only message that tells the user why nothing is syncing any more.
			panel.setStatus(SYNC_REVOKED_MSG, false);
		}
		else if (!config.consent())
		{
			panel.setStatus("Enable consent in settings", false);
		}
		else if (parking.anyNameParked(syncIdentity()))
		{
			// Sticky for the same reason: the key works and other syncs carry on, but the ones published
			// under the in-game name (Collection Log, Combat Achievements) are held, and this says why.
			panel.setStatus(SYNC_NAME_TAKEN_MSG, false);
		}
		else
		{
			panel.setStatus("Connected as " + snapshot.get().rsn, true);
		}
	}

	// -- persistence of the unsynced-trade queue ----------------------------- //

	/**
	 * Queue a captured fill for upload and cap the backlog, oldest-first. See
	 * {@link #MAX_PENDING_TRADES}: the queue is rewritten into RuneLite's config on every fill, so an
	 * unbounded one turns a long outage into an ever-growing blob re-serialised per trade. The newest
	 * fills are the ones the site's P/L is still missing, so it's the oldest that go.
	 */
	private void queuePending(Trade t)
	{
		synchronized (pending)
		{
			pending.add(t);
			if (pending.size() > MAX_PENDING_TRADES)
			{
				// Worth a line in the log: losing a fill is only ever the symptom of a much longer
				// outage, and this is the one place that says so.
				log.warn("RuneTools: unsynced-trade queue full ({}) - dropping the oldest fill",
					MAX_PENDING_TRADES);
				trimPending();
			}
		}
	}

	/** Caller must hold the {@code pending} lock. */
	private void trimPending()
	{
		while (pending.size() > MAX_PENDING_TRADES)
		{
			pending.remove(0);
		}
	}

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
				loaded.add(Trade.fromPendingJson(el.getAsJsonObject()));
			}
			synchronized (pending)
			{
				pending.clear();
				pending.addAll(loaded);
				trimPending();   // a blob written before the cap existed must not be inherited whole
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
					arr.add(t.toPendingJson());
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
		// Gold "R" on dark stone with a gold bevel - matches the Plugin Hub icon.png + the runetools.com theme.
		BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setColor(new Color(0x22, 0x1E, 0x16));
		g.fillRoundRect(0, 0, 16, 16, 5, 5);
		g.setColor(new Color(0xC9, 0xA2, 0x4A));
		g.drawRoundRect(0, 0, 15, 15, 5, 5);
		g.setColor(new Color(0xF3, 0xCB, 0x5C));
		g.setFont(new Font("SansSerif", Font.BOLD, 12));
		g.drawString("R", 4, 12);
		g.dispose();
		return img;
	}
}
