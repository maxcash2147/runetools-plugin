# RuneTools RuneLite plugin

Sync your Old School RuneScape account to **[RuneTools / GE Charts](https://runetools.com)** so the
site can:

- **auto-track your Grand Exchange profit/loss** - every buy and sell you make is uploaded and
  turned into a live P/L breakdown (no more CSV exports), and
- **personalize flip suggestions** to your *real* bankroll, free GE slots and remaining 4-hour buy
  limits instead of generic assumptions.

This is the first slice of a broader companion plugin - see [`ROADMAP.md`](ROADMAP.md).

## How it connects

1. In RuneLite, open the **RuneTools** plugin settings and tick **I consent to sending my account
   data to RuneTools** (a disclosure dialog explains exactly what is sent). Nothing is sent until
   this is on.
2. Log into the game, open the **RuneTools** side panel and click **Connect to RuneTools**.

That click is the whole flow. The plugin asks the server for a one-time browser-login code, sending
this character's name and account hash; on first sight the server creates your site account from
that account hash, names it after your in-game name and hands back an API key, which the plugin
stores for this character. Your browser then opens runetools.com already signed in, the panel reads
*“Connected as <you>”*, and the button becomes **Open RuneTools** - press it whenever you want to
jump to the site signed in as that character.

Every character links itself, and each one gets its own key and its own RuneTools account, so a main
and an ironman never land in one profile. Add an email on the site if you also want to sign in from
a browser; once you do the account is email-locked, and the plugin says so instead of bootstrapping
a new one.

If the server ever rejects a stored key the status line says so and the button reverts to **Connect
to RuneTools** - clicking it re-links the character and mints a working key.

Keys live in RuneLite's own config like any other plugin setting, one per character
(`runetools.key_<account hash>`), and are sent only to the server you configure, always over HTTPS:
an `http://` **Server URL** is upgraded before any request goes out. The **API key** setting is
hidden and legacy - you need it only to paste a key you minted yourself against `/api/auth/keys`,
which is also where a key is revoked.

## What it sends

| Data | When | Why |
|---|---|---|
| Completed GE fills (item, buy/sell, qty, unit price, time) | as they fill | automatic P/L tracking |
| RSN + account hash + a capture timestamp | on sync | link the character to your site account |
| Coins, free GE slots, open GE offers, inventory, current world + members status | while playing | bankroll- and slot-aware flip suggestions |
| Skill levels & experience | while playing | level-aware BIS / DPS / skill tools |
| Quest progress | once per login | quest-gated gear/BIS filtering |
| Combat Achievements (tasks + completion) | when they change | the site's CA planner |
| Current Slayer task (creature, amount, assigning master, points, streak, task location) | when it changes | the site's Slayer optimizer |
| Collection Log progress, your complete obtained set, and the items on pages you open | opening the log reads the whole log in one request; page detail as you open pages | a browsable collection-log replica |
| Bronzeman unlocks (the item ids you have unlocked there) | while playing, if you run the [Another Bronzeman Mode](https://github.com/dennisdev/another-bronzeman-mode) plugin and leave **Include Bronzeman unlocks** on | count unlocked items as gear you own for BIS |
| A gear setup's worn gear, inventory, rune pouch, quiver & spellbook | when you save or update that setup | two-way gear-setup sync |
| A bank tab's item layout | only when you export a tab to share it | publish a shareable bank layout to your profile |
| Bank contents | only if you enable **Include bank**, and only when you open the bank | holdings P/L, best-loadout-from-bank |

Nothing is sent until you accept the consent toggle **and** connect the character. See the in-client
warning for the authoritative description.

## Build & run (development)

Requires a JDK 11+ and the RuneLite Gradle setup (this repo is generated from
[`runelite/example-plugin`](https://github.com/runelite/example-plugin)).

```bash
# launch a RuneLite client with the plugin side-loaded
./gradlew run

# build a fat jar you can hand to others to side-load (Plugin Hub not required)
./gradlew shadowJar        # -> build/libs/runetools-1.0.0-all.jar
```

> `runeLiteVersion` is pinned to `latest.release`, so the build tracks the current RuneLite API; if
> an API name ever drifts, bump it or adjust the import. Build with a JDK 11 (`./gradlew build`).

To point at a local/staging server during development, change **Server URL** in the plugin settings
(default `https://runetools.com`) and run the site with `ge-trader serve`.

## How GE capture works (and why it doesn't double-count)

We follow the same pattern as RuneLite's built-in Grand Exchange plugin and Flipping Copilot
([`OfferTracker`](src/main/java/com/runetools/OfferTracker.java)):

- keep a per-slot `SavedOffer` baseline, persisted in the RuneLite RSProfile config so it survives
  relogs and restarts;
- record a trade only for a **positive `quantitySold`/`spent` delta**, so multi-tick partial fills
  are each counted exactly once;
- at login the client replays all 8 slots - during that burst we just re-baseline and emit nothing;
- a changed item/price/total is treated as a fresh offer (desync guard);
- every trade carries an `ext_id` and the server dedupes on `(item, side, qty, price, ts)`, so a
  retry or a re-send is idempotent.

## Server side

The website endpoints this plugin talks to live in the `ge-trader` repo:

- `POST /api/plugin/trades` - ingest GE fills (JSON; reuses the trade table + dedup)
- `POST /api/plugin/state` - latest-wins snapshot of gp / offers / holdings / skills / quests / slayer
- `POST /api/plugin/web-login` - mint the one-time browser-login code behind **Connect to
  RuneTools**, creating this character's account + key on first sight
- `GET  /api/plugin/me` - validate a key, return linked RSNs (the connect flow uses web-login)
- `GET/POST … /api/plugin/setups` - two-way gear-setup sync
- `POST /api/plugin/combat-achievements` - CA catalogue + completion
- `POST /api/plugin/collection-log` - totals, the whole obtained set, and any pages you opened
- `GET/POST /api/plugin/bank-layouts` - pull imported layouts / publish a shared bank tab
- `POST /api/auth/keys` · `GET /api/auth/keys` · `DELETE /api/auth/keys/{id}` - mint / list / revoke keys (session-authed, on the site)

All plugin endpoints authenticate with `Authorization: Bearer rtk_…`.

## License

BSD 2-Clause. See [`LICENSE`](LICENSE).
