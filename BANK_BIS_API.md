# Bank-scan BIS API — plugin wiring contract

Server side is **live** (`POST /api/plugin/bis`, ge-trader commit `a5c299f`, deployed on runetools.com).
Computes "the highest-DPS setup this player OWNS for a given monster" in ~20–30ms.

## Auth
Same as every `/api/plugin/*` route: `Authorization: Bearer rtk_<key>` (the key the user
minted on the site). 401 without it. Rate limit: 240 req/min per IP (shared plugin bucket).

## Request

`POST https://runetools.com/api/plugin/bis`

```json
{
  "monster": 8059,
  "items": [22978, 11865, 6585, 4894, 995, 385],
  "levels": {"attack": 118, "strength": 118, "ranged": 99, "magic": 99},
  "on_task": true,
  "rsn": "Zezima"
}
```

- `monster` (required) — the NPC id the site uses (`/api/calc/dps/monster-search?q=` to
  resolve names → ids; same ids the DPS calculator and Monster Examine pages use).
- `items` (recommended) — **flat list of live game item ids** from the client scan:
  bank + equipped + inventory, whatever container mix you want counted as "owned".
  Any of these element shapes are accepted: `123`, `{"id":123,"qty":4}`,
  `{"itemId":123}`, `[123, 4]`. Quantities are ignored (ownership is boolean).
  **Send raw ids as-is** — the server normalizes charge/degradation states by name
  (a Dharok's platebody at `50` (id 4894), an uncharged trident, a Black mask (7) all
  map onto their canonical items). Junk/non-equipment ids are silently dropped.
  Capped at 20,000 ids.
- `items` omitted/empty → the server falls back to the **last synced snapshot**
  (`/api/plugin/state`'s `bank` + `inventory` for this user; optional `rsn` selects the
  character, else the freshest snapshot). 400 if there's no snapshot either.
- `levels` (optional) — VISIBLE levels (boosts included, 1–125 each); the plugin knows
  real-time boosted stats, so send them for accurate results. Missing keys default 99.
- `on_task` (optional, default `true`) — whether the target is the player's current
  Slayer task (gates the Slayer helm / Black mask bonus).

## Response

Same shape as the public BIS API, restricted to owned items:

```json
{
  "monster": {"id": 8059, "name": "Vorkath", "level": 732, "hp": 750,
              "attributes": ["dragon", "undead"], "weakness": null, "slayer": true},
  "best_style": "melee",
  "styles": {
    "melee": {
      "dps": 8.412, "max_hit": 48, "accuracy": 71.3, "ttk": 96.4,
      "attack_type": "stab", "stance": "aggressive", "spell": null,
      "effects": {"dragon_hunter": true, "target_bonus": "slayer"},
      "loadout": [{"slot": "weapon", "id": 22978, "name": "Dragon hunter lance", "version": ""}, ...]
    },
    "ranged": { ... }, "magic": { ... }
  },
  "unusable": {"magic": "no owned gear can damage this target with this style"},
  "owned": {"provided": 431, "recognized": 118}
}
```

- `loadout` ids are **canonical** item ids (the undamaged/charged variant), which may
  differ from the exact id scanned — match on these when highlighting bank items, or
  re-normalize your ids the same way (by item name).
- `unusable` explains styles omitted because nothing owned can hurt the target
  (eligibility gates are modelled: leafy needs leaf-bladed/broad, tier-2/3 vampyres
  need silver/vampyrebane, flying monsters need a polearm for melee, Corp halving,
  Kraken melee-immunity, etc.).
- `styles` can be empty + `best_style` null if the player owns nothing usable at all.
- `owned.recognized` = how many distinct canonical equipment items survived
  normalization — useful for a "scanned N items" UI line and for debugging.
- Errors: 400 with `{"error": "..."}` (bad monster id, no items + no snapshot,
  nothing recognizable), 401 unauthenticated.

## Notes
- Assumes best-per-style prayer (Piety/Rigour/Augury) and potions, like the site BIS.
- Ruby bolt DPS is a whole-kill average (proc worth ~half max HP), intentionally
  slightly different from the wiki calculator's fresh-target number.
- The search itself costs ~20–30ms server-side; calling it per boss-selection UI event
  is fine, no client caching required.
