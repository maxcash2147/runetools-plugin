# RuneTools

The companion plugin for **[RuneTools](https://runetools.com)**, a set of Old School RuneScape
calculators and Grand Exchange tools. It syncs your account to the site so the tools work from
your real data instead of numbers you type in.

## Features

- **Grand Exchange profit tracking.** Every buy and sell you complete is uploaded and turned into a
  live profit/loss breakdown on the site, with this session's P/L, recent flips and open offers in
  the side panel.
- **Flip suggestions that fit you.** The site sizes suggestions to your real coins, free GE slots,
  open offers and the 4-hour buy limits you have left.
- **Gear setups, both ways.** Save what you are wearing and carrying as a setup from the side
  panel, or build setups on the site; either way they show up in both places. Pick a setup to
  filter (and optionally arrange) your bank to exactly its items, see what you are missing and what
  it costs, and copy setups to and from the Inventory Setups plugin.
- **Bank Organizer.** Sort your bank into category tabs with one click (as Bank Tags tabs; no items
  are moved), turn a native bank tab into a tag, and share a tab's layout on your RuneTools profile.
- **Combat Achievements and Collection Log.** Tier progress and points in the panel, with the
  planner and a browsable copy of your log on the site.
- **RuneTools DPS.** A button in the Equipment Stats screen (and the side panel) opens the DPS
  calculator with the gear you have on.

The plugin sends nothing until you tick the consent setting and connect a character. The full list
of what it sends, and why, is under [What it sends](#what-it-sends).

## How it connects

1. In RuneLite, open the **RuneTools** plugin settings and tick **I consent to sending my account
   data to RuneTools** (a disclosure dialog explains exactly what is sent). Nothing is sent until
   this is on.
2. Log into the game, open the **RuneTools** side panel and click **Connect to RuneTools**.

That opens the site's approve page with a short code, which the panel shows too. Check the two match,
then either connect the character to the RuneTools account you're signed into, sign in or create one,
or continue without an email (an account the plugin opens; you can add an email any time). The plugin
picks up this character's key by itself a few seconds later, the panel reads *“Connected as <you>”*, and
the button becomes **Open RuneTools** - press it to jump to the site signed in (for an account with an
email, sign in with that).

One RuneTools account can hold several characters: connect each one while you're signed in. A
character never connects without someone approving it in a browser, and the game's account hash is
never enough to open an account. If another account has already *proven* it plays the character (by
syncing XP the public hiscores didn't have yet), only that account can connect it.

If the server ever rejects a stored key (you disconnected that install on the site, or reset your
password) the status line says so and the button reverts to **Connect to RuneTools**: connecting again
is the same approval as the first time.

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
sent. Nothing is sent until you accept the consent toggle **and** connect the character, and
each automatic sync can be switched off in the plugin settings. The in-client consent warning
carries the same list.

## Network use

The plugin talks only to the RuneTools server (`https://runetools.com`, or the **Server URL** you
set), only over HTTPS, and only while you are logged into the game with consent on. The background
sync below also needs the character to be connected; everything else is a direct result of
something you clicked (Connect, saving a setup, exporting a bank tab). Nothing is sent from the
login screen.

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

## How the Collection Log is read

The game only sends the client your full Collection Log on request, so when you open **your own**
Collection Log in-game the plugin makes that request once: it fires the log's own *Search* toggle
(`client.menuAction(-1, InterfaceID.Collection.SEARCH_TOGGLE, MenuAction.CC_OP, 1, -1, "Search", null)`)
and puts the view straight back. This is the one use of `menuAction` that RuneLite's
[Plugin Hub rules](https://github.com/runelite/runelite/wiki/Rejected-or-Rolled-Back-Features) list as
acceptable ("fetching collection log data"). It only touches the interface (nothing happens in the
game world), runs at most once each time you open the log, never runs for a log you are viewing in
someone else's house, and only runs when **Sync Collection Log** is on, you have consented and the
character is connected. Nothing else triggers it: there is no button or timer for it. Pages you
then click through are read straight from the interface, which adds their names and quantities.

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
(default `https://runetools.com`).

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

## API endpoints

These are every request the plugin makes, all to the configured server:

- `POST /api/plugin/web-login` - mint the one-time browser-login code behind **Connect to
  RuneTools**, creating this character's account + key on first sight
- `POST /api/plugin/pair`, then `POST /api/plugin/pair/poll` every few seconds - pair this client with an email-locked account (only while you're connecting one)
- `POST /api/plugin/trades` - completed GE fills (the server dedupes on `ext_id`)
- `POST /api/plugin/state` - latest-wins snapshot of gp / offers / inventory / bank / skills
- `GET /api/plugin/setups` (ETag) · `POST` · `PUT /{id}` · `DELETE /{id}` - two-way gear-setup sync
- `POST /api/plugin/combat-achievements` - CA catalogue + completion
- `POST /api/plugin/collection-log` - totals, the whole obtained set, and any pages you opened
- `GET /api/plugin/bank-layouts` (ETag) · `POST` - pull imported layouts / publish a shared bank tab

All of them authenticate with `Authorization: Bearer rtk_…`. Keys can be listed and revoked on the
site (`/api/auth/keys`, signed in with your RuneTools account).

## License

BSD 2-Clause. See [`LICENSE`](LICENSE).
