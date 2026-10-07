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

The Collection Log and Combat Achievements are published under the character's in-game name, so the
site refuses them (HTTP 409, `code: "rsn_taken"`) when that name is already linked to a different
RuneTools account. The key still works, so other syncs carry on; the plugin just stops re-sending the
refused data, says so in the status line, and tries again once a different character logs in, the
name changes, or the character is reconnected with a new key (or the client restarts). Likewise a
payload the server rejects outright (400 or 413) is not re-sent until it changes, and a 429's
`Retry-After` is honoured before the next sweep.

Keys live in RuneLite's own config like any other plugin setting, one per character
(`runetools.key_<account hash>`), and are sent only to the server you configure, always over HTTPS:
an `http://` **Server URL** is upgraded before any request goes out. The **API key** setting is
hidden and legacy - you need it only to paste a key you minted yourself against `/api/auth/keys`,
which is also where a key is revoked.

## What it sends

| Data | When | Why |
|---|---|---|
| RSN + account hash + a capture timestamp | with every sync | link the character to your site account |
| Completed GE fills (item, buy/sell, qty, unit price, time) | as they fill | automatic P/L tracking |
| Coins, free GE slots, open GE offers, inventory, members-world flag | when they change while playing | bankroll- and slot-aware flip suggestions |
| Skill levels & experience | when they change while playing | level-aware calculators (BIS, DPS, skills), and proving the character is yours: the server compares the exact XP with the official hiscores before any synced data is shown publicly under that name |
| Combat Achievements (task list + completion) | when they change | the site's CA planner |
| Collection Log progress counts, your complete obtained set, and the items on pages you open | counts while playing; the obtained set when you open the log in-game | a browsable collection-log replica |
| A gear setup's worn gear, inventory, rune pouch, quiver & spellbook | when you save or update that setup | two-way gear-setup sync |
| A bank tab's item layout | only when you export a tab to share it | publish a shareable bank layout to your profile |
| Bank contents | only if you enable **Include bank**, and only when you open the bank | holdings P/L, best-loadout-from-bank |

That is the complete list: nothing else about the account, your client or other players is
collected. Nothing is sent until you accept the consent toggle **and** connect the character, and
each automatic sync can be switched off in the plugin settings. The in-client consent warning
carries the same list.

## Network use

The plugin talks only to the RuneTools server (`https://runetools.com`, or the **Server URL** you
set), only over HTTPS, and only while a connected, consenting character is logged in. Nothing is
sent from the login screen.

- **Uploads** (trades, account state, Combat Achievements, Collection Log) go out only when
  something actually changed, checked every 20 seconds. A completed GE buy is sent straight away.
- **Changes made on the website** (gear setups, bank layouts you imported) are fetched with ETag
  requests that cost a `304` when nothing changed. They start 30 seconds apart and back off to once
  every 5 minutes while nothing changes, so an idle logged-in client makes fewer than 600 of these
  requests a day. Opening the RuneTools side panel or your bank fetches them right away (at most
  once every 10 and 60 seconds respectively), and a change found on the site brings the 30-second
  gap back.
- If the server is unreachable or rate-limits the plugin, the whole loop backs off (up to 10
  minutes, or as long as a `429`'s `Retry-After` asks, capped at an hour), and a key the server keeps
  rejecting stops the loop until you reconnect the character.

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
- `POST /api/plugin/state` - latest-wins snapshot of gp / offers / inventory / bank / skills
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
