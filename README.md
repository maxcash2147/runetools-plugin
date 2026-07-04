# RuneTools RuneLite plugin

Sync your Old School RuneScape account to **[RuneTools / GE Charts](https://gecharts.com)** so the
site can:

- **auto-track your Grand Exchange profit/loss** — every buy and sell you make is uploaded and
  turned into a live P/L breakdown (no more CSV exports), and
- **personalize flip suggestions** to your *real* bankroll, free GE slots and remaining 4-hour buy
  limits instead of generic assumptions.

This is the first slice of a broader companion plugin — see [`ROADMAP.md`](ROADMAP.md).

## How it connects

1. Sign in at [gecharts.com](https://gecharts.com) and open **Account → Connect RuneLite**.
2. Click **Generate key** — you'll get a one-time key like `rtk_…`. Copy it.
3. In RuneLite, open the **RuneTools** plugin settings, paste the key into **API key**, and tick
   **I consent to sending my account data to RuneTools** (a disclosure dialog explains exactly what
   is sent).
4. Open the **RuneTools** side panel and hit **Test connection** — it should read *“Connected as
   <you>”*. The first time you connect, your site display name is set to your in-game name.

Your API key is stored locally (masked, excluded from RuneLite's config sync) and is sent only to
the server you configure, over HTTPS. Revoke it any time from the website.

## What it sends

| Data | When | Why |
|---|---|---|
| Completed GE fills (item, buy/sell, qty, unit price, time) | as they fill | automatic P/L tracking |
| RSN + account hash | on sync | link the character to your site account |
| Coins, open GE offers, inventory, current world + members status | while playing | bankroll- and slot-aware flip suggestions |
| Bank contents | only if you enable **Include bank**, and only when you open the bank | holdings P/L, best-loadout-from-bank |

Nothing is sent until you paste a key **and** accept the consent toggle. See the in-client warning
for the authoritative description.

## Build & run (development)

Requires a JDK 11+ and the RuneLite Gradle setup (this repo is generated from
[`runelite/example-plugin`](https://github.com/runelite/example-plugin)).

```bash
# launch a RuneLite client with the plugin side-loaded
./gradlew run

# build a fat jar you can hand to others to side-load (Plugin Hub not required)
./gradlew shadowJar        # -> build/libs/runetools-1.0.0-all.jar
```

> This machine had no JDK/Gradle when the code was written, so the Java has **not** been compiled
> here yet — run `./gradlew build` once to confirm on a machine with a JDK. `runeLiteVersion` is
> pinned to `latest.release`; if an API name has drifted, bump it or adjust the import.

To point at a local/staging server during development, change **Server URL** in the plugin settings
(default `https://gecharts.com`) and run the site with `ge-trader serve`.

## How GE capture works (and why it doesn't double-count)

We follow the same pattern as RuneLite's built-in Grand Exchange plugin and Flipping Copilot
([`OfferTracker`](src/main/java/com/runetools/OfferTracker.java)):

- keep a per-slot `SavedOffer` baseline, persisted in the RuneLite RSProfile config so it survives
  relogs and restarts;
- record a trade only for a **positive `quantitySold`/`spent` delta**, so multi-tick partial fills
  are each counted exactly once;
- at login the client replays all 8 slots — during that burst we just re-baseline and emit nothing;
- a changed item/price/total is treated as a fresh offer (desync guard);
- every trade carries an `ext_id` and the server dedupes on `(item, side, qty, price, ts)`, so a
  retry or a re-send is idempotent.

## Server side

The website endpoints this plugin talks to live in the `ge-trader` repo:

- `POST /api/plugin/trades` — ingest GE fills (JSON; reuses the trade table + dedup)
- `POST /api/plugin/state` — latest-wins snapshot of gp / offers / holdings
- `GET  /api/plugin/me` — validate the key, return linked RSNs
- `POST /api/auth/keys` · `GET /api/auth/keys` · `DELETE /api/auth/keys/{id}` — mint / list / revoke keys (session-authed, on the site)

All plugin endpoints authenticate with `Authorization: Bearer rtk_…`.

## License

BSD 2-Clause. See [`LICENSE`](LICENSE).
