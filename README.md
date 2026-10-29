# RuneTools

The companion plugin for **[RuneTools](https://runetools.com)**, a set of Old School RuneScape
calculators and Grand Exchange tools. It syncs your account to the site so the tools work from
your real data instead of numbers you type in.

## Features

- **Grand Exchange profit tracking.** Every buy and sell you complete is uploaded and turned into a
  live profit/loss breakdown on the site, with this session's P/L, recent trades and open offers in
  the side panel.
- **Flip suggestions that fit you.** The site sizes suggestions to your real coins, free GE slots,
  open offers and the 4-hour buy limits you have left.
- **Gear setups, both ways.** Save what you are wearing and carrying as a setup from the side
  panel, or build setups on the site; either way they show up in both places. Pick a setup to
  filter (and optionally arrange) your bank to exactly its items, see what you are missing and what
  it costs, and copy setups to and from the Inventory Setups plugin (one at a time, or all of them
  as a file for its Mass Import).
- **Bank layouts.** Sort your bank into category tabs with one click (as Bank Tags tabs; no items
  are moved), turn a native bank tab into a tag, and share a tab's layout on your RuneTools profile.
- **Whole bank layouts.** Share every tab of your bank at once, placeholders included, to the
  repository at runetools.com/layouts. Import someone else's there and the plugin builds it as Bank
  Tags tabs, plus a Misc tab for anything you own that their layout doesn't place. Nothing is moved,
  and **Remove imported** undoes it.
- **Combat Achievements and Collection Log.** Tier progress and points in the panel, with the
  planner and a browsable copy of your log on the site.
- **RuneTools DPS.** A button in the Equipment Stats screen (and the side panel) opens the DPS
  calculator with the gear you have on. The link it opens in your browser carries your worn items'
  IDs and your weapon's style, so the page can load them.
- **Live drop timeline.** When the game announces a new Collection Log item, it reaches your profile's
  drop timeline within a minute, stamped with when you got it, without opening the log.
- **Adventurer's Log.** Your achievements go on your public RuneTools profile as they happen: level-ups
  and 99s, quests, achievement diaries, combat tasks, pets, first kills, kill-count milestones and
  personal bests, and clue milestones. Drops, chest loot and clue rewards are logged too once you turn
  on **Drops and loot** (off by default). Each kind has its own switch under **Adventurer's Log**, and a
  private profile hides them all. Public entries show the day, not the time, and no GP values.

The plugin sends nothing until you tick the consent setting, and no account data until you connect a
character. Leagues, Deadman, beta, tournament, quest-speedrun, Fresh Start, PvP Arena and Last Man
Standing worlds are never synced. The full list of what it sends is under [What it sends](#what-it-sends).

## How it connects

1. In RuneLite, open the **RuneTools** plugin settings and tick **I consent to sending my account
   data to RuneTools** (a disclosure dialog explains exactly what is sent).
2. Log into the game, open the **RuneTools** side panel and click **Connect to RuneTools**.

That opens the site's approve page with a short code, which the panel shows too. Check the two match,
then either connect the character to the RuneTools account you're signed into, sign in or create one,
or continue without an email (an account the plugin opens; you can add an email any time). The plugin
picks up this character's key by itself a few seconds later, the panel reads *Connected as <you>*, and
the button becomes **Open RuneTools**, which opens the site signed in (for an account with an email,
sign in with that).

You do this once per character. The key is kept, so from then on the character connects by itself
every time you log in. A character that isn't connected yet gets one reminder in chat each session
(once you've given consent).

One RuneTools account can hold several characters: connect each one while you're signed in. A
character never connects without someone approving it in a browser, and the game's account hash is
never enough to open an account. If another account has already *proven* it plays the character (by
syncing XP the public hiscores didn't have yet), only that account can connect it.

If the server rejects a stored key (you disconnected that install on the site, or reset your
password) the status line says so and the button reverts to **Connect to RuneTools**: connecting again
is the same approval as the first time.

The Collection Log, Combat Achievements and Adventurer's Log are published under the character's
in-game name, so the site refuses them (HTTP 409, `code: "rsn_taken"`) when that name is linked to a
different RuneTools account. Other syncs carry on; the refused data is held, the status line says why,
and it is tried again once a different character logs in, the name changes, the character is
reconnected or the client restarts. Any other refusal (a 409 limit, a 400 or 413) is held the same way
and shown in the server's own words.

Keys live in RuneLite's own config like any other plugin setting, one per character
(`runetools.key_<account hash>`), and are sent only to the server you configure, always over HTTPS:
an `http://` **Server URL** is upgraded before any request goes out, and an old `gecharts.com` setting
is moved to `https://runetools.com`. Keys can be revoked on the site.

## What it sends

| Data | When | Why |
|---|---|---|
| The plugin's version (`X-RuneTools-Plugin` and `X-RuneTools-Api` headers) | with every request | so the site can answer each version the way it expects |
| Your in-game name, your RuneLite account hash (an ID RuneLite gives the game account) and a capture time | with every sync | link the character to your site account |
| Completed GE fills (item, buy/sell, qty, unit price, time) | as they fill | automatic P/L tracking |
| Coins (in your inventory and, once you open your bank, your banked coins), free GE slots, open GE offers, inventory, the gear you're wearing, members-world flag | when they change while playing | bankroll- and slot-aware flip suggestions, and best-in-slot from the gear you own |
| Skill levels & experience | when they change while playing | level-aware calculators, and proving the character is yours: the server compares the exact XP with the official hiscores before any synced data is shown publicly under that name |
| Combat Achievements (task list + completion) | when they change | the site's CA planner |
| Collection Log progress counts, your complete obtained set, and the items on pages you open | counts while playing; the obtained set when you open the log in-game | a browsable collection-log replica |
| The name of each new Collection Log item the game announces in chat, and when | with the next sync after the message | your profile's drop timeline |
| Achievements for your Adventurer's Log (see [How the Adventurer's Log is filled](#how-the-adventurers-log-is-filled)) | a few seconds after each one, while that kind is switched on | your profile's Adventurer's Log |
| A gear setup's worn gear, inventory, rune pouch, quiver & spellbook | when you save or update that setup | two-way gear-setup sync |
| A bank tab's item layout | only when you export a tab | a shareable bank layout on your profile |
| Which items sit where in each bank tab (never quantities) | only when you click **Share whole bank** | your whole bank's layout, public only if you tick the box |
| Bank contents | only with **Include bank** on, after you open the bank, and only when it changed | holdings P/L, best-loadout-from-bank |

Nothing else about the account, your client or other players is sent. (The DPS button is a link,
not a sync: it opens the calculator in your browser with your worn items' IDs and weapon style in
the address.) Each automatic sync can be switched off in the plugin settings, and the in-client
consent warning carries the same list.

The site stores your gear setups and bank layouts. Some data is shown on your public RuneTools
profile: levels, the Collection Log and drop timeline, the Adventurer's Log, and the setups and
layouts you make public. A private profile hides them.

## Network use

The plugin talks only to the RuneTools server (`https://runetools.com`, or the **Server URL** you
set), only over HTTPS, and only while you are logged into the game with consent on. Nothing is sent
from the login screen. Account data also needs the character to be connected and a main-game world;
everything else is a direct result of something you clicked (Connect, saving a setup, exporting a
bank tab).

- **Uploads** (trades, account state, Combat Achievements, Collection Log, achievements) go out only
  when something changed, checked every 20 seconds. A completed GE buy is sent straight away. Each
  character's trades go with that character's own key; a character that isn't connected keeps its
  trades queued (for up to 30 days) until it is.
- **Changes made on the website** (gear setups, bank layouts you imported) reach the client within a
  second or two: while you're logged in with **Sync gear setups** on, the plugin keeps one request
  open to the site, which answers the moment you save something there (or with an empty reply after
  about 50 seconds). A new one starts no sooner than 10 seconds after the last. As a fallback, they
  are also fetched with ETag requests that cost a `304` when nothing changed: 30 seconds apart at
  first, backing off to once every 5 minutes. Opening the side panel or your bank fetches them right
  away (at most once every 10 and 60 seconds).
- **The site's settings for the plugin** (`GET /api/plugin/config`) are fetched once at login, every
  30 minutes, and when the site says they changed. See [Remote config](#remote-config).
- **Failures back off per request type**: an endpoint that fails waits 20 seconds, then twice as long
  each time, up to 10 minutes, without slowing the others. A `429`'s `Retry-After` pauses every
  background sync (capped at an hour). An upload the server keeps failing with a `500` (five times
  in a row, over half an hour) is given up on; an outage (`502`, `503`, `504`) never counts. A key
  the server keeps rejecting stops the loop until you reconnect the character.

## Remote config

`GET /api/plugin/config` lets the site ease a problem without a plugin update. It is optional: a
`404` or anything that isn't a JSON object leaves the last good copy (cached in the plugin's config)
or the built-in behaviour in place. It can only make the plugin do less, and it carries no code:

- `features`: a flag set to `false` turns that sync off (`trades`, `state`, `bank`, `ca`, `clog`,
  `clog_request`, `achievements`, `setups_pull`, `setups_write`, `layouts_apply`, `layouts_publish`,
  `live`, `pairing`, `web_login`). It can't turn on anything you turned off.
- `limits`: intervals in seconds (`sync_s`, `poll_min_s`, `poll_max_s`, `panel_gap_s`, `bank_gap_s`,
  `live_gap_s`, `backoff_max_s`, `config_s`, `pair_poll_s`), only ever longer than the built-in ones.
- `paths`: where each request goes, accepted only under `/api/plugin/` (no `..`, scheme or host), and
  `delete_method`: `DELETE` (default) or `POST` to `<path>/delete`.
- `message`: `{id, level, text, link}`, up to 200 characters, shown in the panel and once in chat. Its
  link is a path on the configured server, opened only when you click it.
- `min_version`: an older plugin pauses every upload and setup or layout write, and says
  *Restart RuneLite to update RuneTools*.

## How the Collection Log is read

The game only sends the client your full Collection Log on request, so when you open **your own**
Collection Log in-game the plugin makes that request once: it fires the log's own *Search* toggle
(`client.menuAction(-1, InterfaceID.Collection.SEARCH_TOGGLE, MenuAction.CC_OP, 1, -1, "Search", null)`)
and puts the view straight back. This is the one use of `menuAction` that RuneLite's
[Plugin Hub rules](https://github.com/runelite/runelite/wiki/Rejected-or-Rolled-Back-Features) list as
acceptable ("fetching collection log data"). It only touches the interface (nothing happens in the
game world), runs at most once each time you open the log, never runs for a log you are viewing in
someone else's house, and only runs when **Sync Collection Log** is on, you have consented, the
character is connected and the world is a main-game world. Nothing else triggers it: there is no
button or timer for it. Pages you then click through are read straight from the interface, which adds
their names and quantities.

## How the Adventurer's Log is filled

Everything except levels, quests, clue rewards and chest loot comes from a message the game itself
prints, matched on the same texts RuneLite's own Screenshot and Chat Commands plugins use. The message
formats were also checked against [Dink](https://github.com/pajlads/DinkPlugin) (BSD 2-Clause); the
code here is its own. Lines other plugins print are ignored, and nothing is logged on Leagues, Deadman,
beta, tournament, Fresh Start, quest-speedrun, PvP Arena or Last Man Standing worlds.

| Entry | What the plugin watches for |
|---|---|
| Level-ups, 99s, total-level milestones, maxing | your real (unboosted) levels, compared with what was read a few ticks after login (or after the plugin is switched on), big jumps (a quest's XP reward) included; the site works out total-level milestones from the new total |
| Quests | the quest completion scroll, matched to RuneLite's list of quests, Recipe for Disaster's parts included ("You have freed the Goblin Generals!") |
| Achievement diaries | "Congratulations! You have completed all of the *tier* tasks in the *area* area" |
| Combat tasks | "Congratulations, you've completed a *tier* combat task: *task*" |
| Pets | "You have a funny feeling like you're being followed", "...would have been followed" (a duplicate), "You feel something weird sneaking into your backpack", with the pet's name from the Collection Log line or clan broadcast that follows, and the broadcast's milestone ("at 50 killcount") when there is one |
| Drops and loot (**Drops and loot** on) | the game's "Valuable drop: ... (*n* coins)" (at or above **Smallest drop to log**) and "Untradeable drop: ..." messages, where an untradeable drop that also makes a new Collection Log entry is left to that entry; loot from chests and raids as RuneLite's Loot Tracker reports it, each stack worth at least **Smallest drop to log** (priced with RuneLite's item prices); and a clue reward screen worth at least that |
| First kills and kill milestones | "Your *boss* kill count is: *n*" and the raid, chest, completion and Herbiboar harvest variants, at 1, 10, 50, 100, 250, 500, 1,000, 2,000, 3,000, 5,000, 10,000 and so on |
| Personal bests | "... duration: *time* (new personal best)", paired with the kill count it came with (raids keep the team size; a Theatre of Blood room's own time is never taken for the raid's) |
| Clues | "You have completed *n* *tier* Treasure Trails" at 1, 10, 25, 50, 100, 250, 500, 1,000 and so on |

Each entry carries a random id, so a resend after a network failure is recognised and stored once.
The site accepts only names it can check (quests, diary areas, combat tasks, pets, items and bosses it
knows), and shows them on your profile only once the character is confirmed as yours and the profile
isn't private.

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
(default `https://runetools.com`). The plugin only ever talks HTTPS, `localhost` included, so serve the
local site over TLS (for example with a [mkcert](https://github.com/FiloSottile/mkcert) certificate) and
use `https://localhost:<port>`.

To log the dev client into a Jagex account, add `--insecure-write-credentials` to the RuneLite
launcher's client arguments and start RuneLite once from the Jagex Launcher; `./gradlew run` then
reuses the saved credentials. Remove the argument again when you're done.

## How GE capture works (and why it doesn't double-count)

We follow the same pattern as RuneLite's built-in Grand Exchange plugin and Flipping Copilot
([`OfferTracker`](src/main/java/com/runetools/OfferTracker.java)):

- keep a per-slot `SavedOffer` baseline in memory, per character: a different character starts from
  fresh baselines, while a logout or world hop keeps them, so fills made while logged out count once;
- record a trade only for a **positive `quantitySold`/`spent` delta**, so multi-tick partial fills
  are each counted exactly once;
- at login the client replays all 8 slots; an offer seen for the first time is only baselined;
- a changed item/price/total is treated as a fresh offer (desync guard);
- every trade carries an `ext_id` and the server dedupes on `(item, side, qty, price, ts)`, so a
  retry or a re-send is idempotent. A batch leaves the queue only once the server confirms it
  received every fill in it.

## API endpoints

Every request the plugin makes, all to the configured server, each with the `X-RuneTools-Plugin`
(version) and `X-RuneTools-Api` (API level) headers:

- `GET /api/plugin/config` - the site's settings for the plugin (see [Remote config](#remote-config));
  carries no key and no account data
- `POST /api/plugin/web-login` - the one-time browser-login code behind **Connect to RuneTools** /
  **Open RuneTools** and the site links in the panel (with a `next` path for the page to land on), for
  the account this character's stored key already opens. It never creates an account or a key:
  without a working key the site answers `code: "pair"` and the plugin starts pairing instead (for an
  account with an email it answers `code: "email_signin"` and the plugin opens the account page)
- `POST /api/plugin/pair`, then `POST /api/plugin/pair/poll` every few seconds - connect this
  character by approving its code in the browser (only while you're connecting one); the poll that
  sees the approval returns the character's key
- `POST /api/plugin/trades` - completed GE fills (the server dedupes on `ext_id`)
- `POST /api/plugin/state` - latest-wins snapshot of gp / offers / inventory / equipment / skills, and
  the bank when it changed
- `GET /api/plugin/setups` (ETag) · `POST` · `PUT /{id}` · `DELETE /{id}` - two-way gear-setup sync
- `GET /api/plugin/changes` - held open by the site until a setup or bank layout is saved there (or
  about 50 seconds pass), so those reach the client at once
- `POST /api/plugin/combat-achievements` - CA catalogue + completion
- `POST /api/plugin/collection-log` - totals, the whole obtained set, and any pages you opened
- `GET /api/plugin/bank-layouts` (ETag) · `POST` - pull imported layouts / publish a bank tab or
  whole bank
- `POST /api/plugin/achievements` - achievements for your Adventurer's Log, a batch at a time

All of them except the config fetch and the two pairing requests authenticate with
`Authorization: Bearer rtk_...` (pairing is how a character without a key gets one).

## License

BSD 2-Clause. See [`LICENSE`](LICENSE).
