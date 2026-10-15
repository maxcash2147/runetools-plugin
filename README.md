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
  calculator with the gear you have on. The link it opens in your browser carries your worn items'
  IDs and your weapon's style, so the page can load them.
- **Combat timing** (off until you turn on **Measure combat timing**). Measures how your real clicks
  land on game ticks: your effective latency (the Ping to set in the site's Prayer and Gear Switch
  trainers) and whether your protection prayers beat Jad and the Hunllef. The side panel shows it
  as you play, and
  [runetools.com/timing](https://runetools.com/timing) links each weak spot to the drill that trains it.
- **Live drop timeline.** When the game announces a new Collection Log item, it reaches your profile's
  drop timeline within a minute, stamped with when you got it, without opening the log.
- **Adventurer's Log.** Your achievements go on your public RuneTools profile as they happen: level-ups
  and 99s, quests, achievement diaries, combat tasks, pets, first kills, kill-count milestones and
  personal bests, and clue milestones. Drops, chest loot and clue rewards are logged too once you turn
  on **Drops and loot** (off by default). Each kind has its own switch under **Adventurer's Log**, and a
  private profile hides them all. Public entries show the day, not the time, and no GP values.

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
| Coins (in your inventory and, once you open your bank, your banked coins), free GE slots, open GE offers, inventory, the gear you're wearing, members-world flag | when they change while playing | bankroll- and slot-aware flip suggestions, and best-in-slot from the gear you own |
| Skill levels & experience | when they change while playing | level-aware calculators (BIS, DPS, skills), and proving the character is yours: the server compares the exact XP with the official hiscores before any synced data is shown publicly under that name |
| Combat Achievements (task list + completion) | when they change | the site's CA planner |
| Collection Log progress counts, your complete obtained set, and the items on pages you open | counts while playing; the obtained set when you open the log in-game | a browsable collection-log replica |
| The name of each new Collection Log item the game announces in chat, and when | with the next sync after the message (Sync Collection Log on) | your profile's drop timeline, at the time you got it |
| Achievements for your Adventurer's Log (see [How the Adventurer's Log is filled](#how-the-adventurers-log-is-filled)): what happened, with its level, quest, area, task, item and its value, boss, kill count, pet milestone or time | a few seconds after each one, while that kind is switched on | your profile's Adventurer's Log and the site's latest-achievements feed |
| Combat timing totals for the session (see [How combat timing is measured](#how-combat-timing-is-measured)) | about every 30 seconds while they change, only with **Measure combat timing** on | your real latency and prayer stats on runetools.com/timing |
| A gear setup's worn gear, inventory, rune pouch, quiver & spellbook | when you save or update that setup | two-way gear-setup sync |
| A bank tab's item layout | only when you export a tab to share it | publish a shareable bank layout to your profile |
| Bank contents | only if you enable **Include bank**, and only when you open the bank | holdings P/L, best-loadout-from-bank |

That is the complete list: nothing else about the account, your client or other players is
sent. (The DPS button is a link, not a sync: it opens the calculator in your browser with your worn
items' IDs and weapon style in the address.) Nothing is sent until you accept the consent toggle **and** connect the character, and
each automatic sync can be switched off in the plugin settings. The in-client consent warning
carries the same list.

## Network use

The plugin talks only to the RuneTools server (`https://runetools.com`, or the **Server URL** you
set), only over HTTPS, and only while you are logged into the game with consent on. The background
sync below also needs the character to be connected; everything else is a direct result of
something you clicked (Connect, saving a setup, exporting a bank tab). Nothing is sent from the
login screen.

- **Uploads** (trades, account state, Combat Achievements, Collection Log, and combat timing when it
  is on) go out only when something actually changed, checked every 20 seconds; combat timing at most
  every 30 seconds. A completed GE buy is sent straight away.
- **Changes made on the website** (gear setups, bank layouts you imported) reach the client within a
  second or two: while you're logged in with **Sync gear setups** on, the plugin keeps one request
  open to the site, which answers the moment you save something there (or with an empty reply
  after about 50 seconds, when the plugin asks again: under 2,000 small requests a day for a client
  left logged in). As a fallback, they are also fetched with ETag requests that cost a `304` when
  nothing changed. They start 30 seconds apart and back off to once
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

## How the Adventurer's Log is filled

Everything except levels, quests, clue rewards and chest loot comes from a message the game itself
prints, matched on the same texts RuneLite's own Screenshot and Chat Commands plugins use. The message
formats were also checked against [Dink](https://github.com/pajlads/DinkPlugin) (BSD 2-Clause); the
code here is its own. Lines other plugins print are ignored, and nothing is logged on Leagues, Deadman,
beta, tournament, Fresh Start, quest-speedrun, PvP Arena or Last Man Standing worlds.

| Entry | What the plugin watches for |
|---|---|
| Level-ups, 99s, total-level milestones, maxing | your real (unboosted) levels, compared with what was read a few ticks after login, big jumps (a quest's XP reward) included; the site works out total-level milestones from the new total |
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

## How combat timing is measured

Off by default. With **Measure combat timing** on (plus consent and a connected character) the plugin
watches four things it already has in front of it: your equip clicks in the inventory, your clicks on
the three protection prayers (and the quick-prayer orb, only to know a reading is ambiguous), your
overhead prayer icon, and the attack animations and projectiles of Jad and the Hunllef aimed at you.
It never changes, blocks or repeats a click; it only reads.

The client processes everything the server sends for a tick and only then announces the tick
(`GameTick`), so each change can be placed on the exact tick it happened:

- **Latency.** For each equip click (and each click that turns a protection prayer on), the plugin
  notes how long before the next tick appeared it was made, and whether the item (or overhead) came
  back on that tick or the one after. Clicks made later than your latency before a tick miss it, so
  the boundary between the two groups is your latency, in the sense the trainers use "ping".
  Ticks that arrive irregularly (lag spikes, the first tick after a hop) are skipped.
- **Prayers.** Jad's attack is decided 3 ticks after his animation starts (after your input on that
  tick); the Hunllef checks the prayer that was up at the start of its attack tick. Each attack is
  counted as blocked, one tick late, the wrong prayer, or no prayer. These are the same rules the site's
  Prayer Trainer drills.

What is sent, about every 30 seconds while it changes, is only the session's running totals: hit and
miss counts in 20 ms buckets, and the prayer counts per fight. No single click, its time or position is sent. Turning the
setting off stops all measuring and drops anything not yet sent.

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
- `GET /api/plugin/changes` - held open by the site until a setup or bank layout is saved there (or about 50 seconds pass), so those reach the client at once
- `POST /api/plugin/combat-achievements` - CA catalogue + completion
- `POST /api/plugin/collection-log` - totals, the whole obtained set, and any pages you opened
- `GET /api/plugin/bank-layouts` (ETag) · `POST` - pull imported layouts / publish a shared bank tab
- `POST /api/plugin/timing` - this session's combat-timing totals (only with **Measure combat timing** on)
- `POST /api/plugin/achievements` - achievements for your Adventurer's Log, a batch at a time

All of them authenticate with `Authorization: Bearer rtk_…`. Keys can be listed and revoked on the
site (`/api/auth/keys`, signed in with your RuneTools account).

## License

BSD 2-Clause. See [`LICENSE`](LICENSE).
