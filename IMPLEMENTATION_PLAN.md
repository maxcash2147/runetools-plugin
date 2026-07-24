# RuneTools — implementation plan (audit follow-up)

Twelve features from the product audit, sequenced into five phases. Each phase is a coherent,
shippable release. Server work lands in `ge-trader` (auto-deploys on push to master); frontend is
**SvelteKit only** (`web-sveltekit/` — never the legacy `app.js`); plugin work stays local until
the Plugin Hub submission pass.

**Cross-cutting rules (every phase):**
- Any new synced field ⇒ update the consent `warning` text in `RuneToolsConfig` in the SAME change
  (Hub requires the disclosure to name every field sent).
- Every new endpoint gets a pytest in `tests/test_plugin_sync.py` (Bearer `_mint_key`/session
  `_signup` patterns).
- New `account_state` fields are JSON TEXT columns retrofitted via the `_get_engine()` ALTER block
  (create_all never ALTERs existing tables).
- Plugin: game reads on the client thread, network via async `enqueue`, everything gated on
  `hasKeyAndConsent()`.
- Before Hub submission: delete the `if (true) return "http://localhost:5173";` override in
  `RuneToolsApiClient.base()` (see plugin-hub-readiness notes).

---

## Phase 1 — Flipping core (buy limits · in-game alerts · GE dashboard)

Closes the loops around the original product. All data already flows; this makes it work for the user.

### 1a. Real buy-limit tracking
- **Model:** remaining limit per item = `mapping.buy_limit − Σ qty of the user's BUYS in the
  trailing 4h window`; window reset = oldest in-window buy + 4h. Pure query over the existing
  `trade` table — no schema change.
- **Server:** `GET /api/plugin/buy-limits` (Bearer) → `{items: [{item_id, name, limit, used,
  remaining, resets_at}]}` for items bought in the last 4h. Session twin `GET /api/account/buy-limits`.
- **Flips integration:** in the flip-suggestion personalization path (where `capital`/slots apply),
  cap `effective_qty` by *remaining* limit instead of the full `buy_limit` when the user has synced
  trades.
- **Plugin:** "Buy limits" block in the Grand Exchange panel section — icon, `used/limit`, reset
  countdown (client-side ticking; refresh on trade + on the 20s sync).

### 1b. In-game price alerts
- **Server:** `GET /api/plugin/alerts/triggered?since=<ts>` (Bearer) → alerts whose
  `last_triggered > since` (table + evaluation already exist; this only exposes them).
- **Plugin:** poll in the 20s `sync()`; persist last-seen ts in config; fire RuneLite `Notifier`
  ("RuneTools: Twisted bow ≥ 1.2B — now 1.21B"). Config toggle **"In-game alerts"** (default on,
  under "What to sync"… no data leaves, so no disclosure change needed).

### 1c. Live GE dashboard (site)
- **Server:** `GET /api/account/offers` (session) → latest `offers_json` merged with current
  prices + hourly volumes; per-offer `est_fill_minutes = remaining_qty / (side_vol_per_hour ×
  capture_share)` reusing the engine's liquidity helpers.
- **Frontend:** new SvelteKit route `/ge` (nav: My tools → "GE Offers"): 8 slot cards — item,
  buy/sell @ price, filled/total progress bar, offer-vs-market delta (flag over/under-priced),
  est. fill time. Empty state → "connect the plugin".

---

## Phase 2 — Account-data quick wins (skills · setup gaps · CA plan)

### 2a. Skills/levels sync
- **Plugin:** add `skills` to `AccountSnapshot`/`postState` — `{skill: {level, xp}}` for all
  `Skill` values via `getRealSkillLevel`/`getSkillExperience` (read in the same client-thread pass
  as gp). **Update consent disclosure** (+"skill levels and experience").
- **Server:** `skills_json` column on `account_state` (retrofit); preserve-if-absent like the bank.
  `GET /api/account/levels` (session).
- **Site consumers:** BIS builder + from-bank (pass real levels to `compute_bis`), DPS calc
  prefill, skill calcs "current XP" prefill, combat-level calc. One `$lib` helper (`myLevels()`),
  wired into each page with a "use my synced levels" affordance.

### 2b. Setup "missing items + cost to complete"
- **Plugin (local, offline):** in the setup detail view, diff the setup's item ids against the
  live bank (+inventory/equipment) and price via `itemManager.getItemPrice` → "Missing 3 items ·
  ~2.4M gp" row with per-item breakdown tooltip.
- **Server:** `GET /api/setups/{id}/missing` (session): diff vs latest `bank_json` +
  `inventory_json`, price via the latest-prices cache → `{missing: [{item_id, name, price}],
  total}`. Site setups editor shows the same line + list.

### 2c. CA plan server-side + plugin CA section
- **Server:** `ca_plan` table (`user_id` PK, `plan_json`, `updated_at`); `GET/PUT
  /api/combat-achievements/plan` (session); include `plan` in the authed
  `GET /api/combat-achievements`; `GET /api/plugin/ca-plan` (Bearer).
- **Frontend:** planner reads/writes the server plan when signed in (anon keeps localStorage;
  migrate local→server on first signed-in mutation).
- **Plugin:** new home-menu card **"Combat Achievements"**: per-tier progress bars + earned points
  (computed from the existing `CombatAchievements.read` snapshot), **"X points to next tier"**
  (threshold varbits 4132 / 10660 / 10661 / 14812 / 14813 / 14814, read at runtime), and the
  synced plan grouped by boss. Link to the site planner.

---

## Phase 3 — Sharing repository (the strategic direction)

### 3a. Gallery listing model
- **Server:** add `listed` (default 0) to `bank_layout` (retrofit ALTER) — `is_public` = link-viewable,
  `listed` = shown in the public gallery (explicit opt-in, avoids surprise-listing existing exports).
  `GET /api/library/layouts?sort=imports|new&q=&page=` (public).
- **My Layouts page:** "List in gallery" toggle per layout.

### 3b. Gear-setup sharing (mirrors bank layouts)
- **Server:** retrofit `code`, `is_public` (default 0), `listed` (0), `import_count` (0) onto
  `gear_setup`. Endpoints: `POST /api/setups/{id}/publish` → `{code,url}` · `DELETE …/publish` ·
  `GET /api/setups/public/{code}` (public) · `POST /api/setups/{code}/import` (session; copies with
  `source='imported'`) · `GET /api/library/setups…`.
- **Frontend:** `/setups/[code]` public read-only page (equipment cross + inventory grid — reuse
  editor rendering read-only); Publish button + share link in the setups editor.
- **Plugin:** ZERO work — imported setups flow through the existing `SetupManager` sync and appear
  in-game automatically.

### 3c. Library page
- **Frontend:** `/library` (nav under Reference or top level): tabs **Layouts | Gear Setups**,
  search, sort by most-imported/newest, cards reusing `LayoutGrid`/setup renderer, import buttons.
  SSR the first page for SEO.

### 3d. Public profiles + social links (the social layer, lean v1)
The creator loop that makes the repository work: everything you share links back to *you*.
- **Server:** on `users` (retrofit): `profile_public` (0), `profile_slug` (unique, derived from
  display_name), `bio` (280 chars), `socials_json`. Socials are stored as **handles, not URLs**
  (`{twitter, discord, twitch, kick, youtube, tiktok}`) — validated per-platform
  (`[A-Za-z0-9_.-]{2,32}`), URLs built server-side against an allowlisted domain per platform, so
  a profile can never render an arbitrary link (phishing guard).
  Endpoints: `GET/PUT /api/profile` (session, own settings) ·
  `GET /api/profile/{slug}` (public) → bio, socials, listed layouts/setups, CA tier summary.
- **Frontend:** `/u/[slug]` public page — name, bio, social icon row, shared layouts + setups
  (import buttons), CA progress bars. Account settings section to opt in, set slug/bio/handles.
  Every public layout/setup page gets a "by <name>" link to the profile. SSR + per-profile meta
  (OG tags) so profiles unfurl nicely when shared on Twitter/Discord.
- **Why it's leveraged:** streamers (Twitch/Kick) sharing their own profile/layout links are a
  distribution loop — their audience imports their setups, sees RuneTools, installs the plugin.
- **Stretch (later, not v1):** OAuth verification of socials (checkmark), Twitch/Kick live badge
  on the profile, follower counts on the library ("by <streamer> · live now").

---

## Phase 4 — Trackers (net worth · slayer)

### 4a. Net-worth history
- **Server:** `wealth_history` table (`user_id, rsn, ts` PK, `gp, bank_value, offers_value`).
  Appended inside `plugin_state._persist`, throttled (skip if the last row is <55 min old);
  `bank_value` priced from the latest-prices cache at write time. Rollup: keep hourly 90 days,
  daily thereafter (prune opportunistically on write). `GET /api/account/wealth` (session) → series.
- **Frontend:** graph (uPlot, already in the stack) on the account dashboard or a `/wealth` page:
  total + gp/bank/offers series, 7d/30d/all ranges.

### 4b. Slayer task sync
- **Spike first:** exact varp/varbit + enum ids for current task (creature, amount, master) and
  points/streak — read them from the open-source core slayer plugin (they're public constants;
  same verification approach as the CA ids).
- **Plugin:** add `slayer` to the state payload `{task, amount, master, points, streak}` +
  **consent disclosure update**.
- **Server:** `slayer_json` on `account_state` (retrofit, preserve-if-absent).
- **Site:** Slayer Optimizer banner "Your task: 134 Nechryael" with kill-here/skip/block advice
  using the user's actual points; prefill the task in the optimizer.

---

## Phase 5 — Big bets (collection log · profiles · quest-gated BIS)

### 5a. Collection log sync + tracker
- **Spike first** (like CA): how to enumerate clog categories/pages/items from cache enums/structs
  and read obtained state (some data only resolves when the log interface is opened — the sync UX
  may be "open your collection log once", same as the bank).
- **Server:** global `clog_item` catalog + per-user `clog_progress` (obtained ids, kc per page) —
  the `ca_task`/`ca_progress` pattern again.
- **Site:** `/collection-log` tracker: categories, obtained %, missing lists, "cost to finish"
  for buyable items (prices tie-in). Fills the void left by collectionlog.net.

### 5b. Profile enrichment (v2 of the social layer)
Lean profiles ship in 3d; this deepens them once the trackers exist:
- Opt-in wealth sparkline (4a), collection-log completion % (5a), pinned/featured shared content,
  per-section visibility toggles.
- Social verification (OAuth checkmarks) + Twitch/Kick live-status badges; library surfaces
  creators ("most-imported this week").

### 5c. Quest-gated BIS ("only what I can wear")
- **Plugin:** quest states once per login (`Quest.values()` × `getState`) → `quests_json` +
  **disclosure update**.
- **Site:** curated `quest_locked_items` map (~50 flagship items: barrows gloves, fire cape, god
  capes, DT2 rings…) → BIS `allowed_ids` filter when "only items I can use" is on (levels from 2a
  already gate the rest). Curated list beats trying to scrape full wiki requirements.

---

## Sequencing rationale
1. **Phase 1** makes the existing flipping product sharply better with near-zero new surface.
2. **Phase 2** is three cheap integrations that touch five existing tools.
3. **Phase 3** is the declared strategic direction (repository **+ the social layer**): sharing
   gives profiles content; profiles give sharing a creator loop (streamer distribution). They ship
   together.
4. **Phase 4–5** grow scope (new domains); collection log is the biggest differentiator but also
   the biggest spike risk, so it goes last with research gates.

Rough sizing: P1 ≈ P2 ≈ a solid session each; P3 ≈ 1.5–2 sessions (incl. profiles);
P4 ≈ 1 (spike-dependent); P5 ≈ 2+ (clog spike gates it).
