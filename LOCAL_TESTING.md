# Local testing — RuneTools plugin + RuneTools site

Everything you need to run the whole stack on your machine: the RuneLite dev client
(with the plugin side-loaded), a Jagex-account login for that dev client, and the
local website/API the plugin syncs to.

Two repos are involved:

| Repo | Path | Role |
|---|---|---|
| `runetools_plugin` | `C:\Users\you\Git_Projects\runetools_plugin` | the RuneLite plugin (Java/Gradle) |
| `ge-trader` | `C:\Users\you\Git_Projects\ge-trader` | the site + API (`gecharts.com`) |

> **The site is now two processes.** The frontend was rewritten in **SvelteKit**
> (`ge-trader/web-sveltekit/`) and that's what `gecharts.com` serves. The Python
> **FastAPI backend** still owns everything under `/api` and `/static` (the DPS engine,
> prices, auth, plugin sync). So local testing runs **both**: the FastAPI backend *and*
> the SvelteKit frontend. `ge-trader serve` only starts the backend — its own web page is
> the **old, pre-migration** frontend, so don't use it to check the UI.

---

## 1. Start the backend / API server (ge-trader)

The plugin talks to `POST /api/plugin/*`, and the SvelteKit frontend calls `/api` for
everything. Run the backend so you can test sync + the site without touching production.

```powershell
# from the ge-trader repo
cd C:\Users\you\Git_Projects\ge-trader

# EVERY session: just activate the existing venv and serve.
.\.venv\Scripts\Activate.ps1
ge-trader serve --port 8010           # -> http://127.0.0.1:8010  (API + plugin sync target)
```

> This is the **API + plugin sync target**, port **8010**. The web page it serves at
> `http://127.0.0.1:8010` is the **old vanilla-JS frontend** — ignore it for UI testing;
> the current UI comes from the SvelteKit frontend in step 2.

> Use **port 8010**, not 8000 — on this machine `HYTE.Nexus.Service` (HYTE case/RGB software)
> already listens on 8000, so `serve` there fails with *"only one usage of each socket address …
> (WinError 10048)"*. Point the plugin's **Server URL** at `http://127.0.0.1:8010` to match.

> The `.venv` already exists and has the deps installed — you do **not** need to create it.
> Do NOT re-run `python -m venv .venv`: if the venv is active (or the server is running) its
> `python.exe` is locked and the recreate fails with *"Unable to copy … to python.exe"*.
>
> First-time-only, or to rebuild from scratch (deactivate + stop the server first so nothing holds
> the lock):
>
> ```powershell
> # deactivate            # if a venv is active in this shell
> # Remove-Item -Recurse -Force .venv   # only when rebuilding
> python -m venv .venv
> .\.venv\Scripts\Activate.ps1
> pip install -e .[web,dev]
> ```

Useful variants:

```powershell
ge-trader serve --port 8010           # match the port the plugin is pointed at
ge-trader serve --no-warm             # don't poll the upstream prices API (quieter, offline-friendly)
ge-trader serve --host 0.0.0.0 --port 8010   # reachable from another device on your LAN
```

> If you see *"Web extras not installed"*, you skipped `pip install -e .[web,dev]`
> (or aren't in the venv).

Leave this running in its own terminal.

---

## 2. Start the SvelteKit frontend (the actual site UI)

This is the current site — the boss grids, Combat Achievements planner, bank layouts, the
reordered nav, etc. Run its dev server (in a **second terminal**) pointed at the backend
from step 1. Vite proxies `/api` and `/static` through to `8010`, so sign-in, prices and the
plugin-synced data all work, with hot reload.

```powershell
# from the SvelteKit frontend
cd C:\Users\you\Git_Projects\ge-trader\web-sveltekit

# first time only: install deps
# npm install

npm run dev                                  # -> https://localhost:5173 (after the §5a mkcert setup)
```

**Open `https://localhost:5173`** (the Vite URL) — **not** `:8010`. (Serves HTTPS once §5a is done;
the plugin requires HTTPS, and the browser trusts the mkcert cert.)

> The dev server proxies `/api` + `/static` to the `:8010` backend **by default**, so
> `npm run dev` alone works. Only if your backend is on a different port, set it first in the
> same terminal: `$env:API_ORIGIN = "http://127.0.0.1:<port>"`.

> **Sign in and generate the connect key *here*, on `:5173`.** `:8010` and `:5173` are
> different origins, so the login cookie must be set on `:5173` (Vite forwards it to the
> backend). Go to **Account → Connect RuneLite → Generate key** to get an `rtk_…` key for
> the plugin (step 5).

> **Exact production replica** (no hot reload) instead of `npm run dev`:
> ```powershell
> cd C:\Users\you\Git_Projects\ge-trader\web-sveltekit
> npm run build
> $env:API_ORIGIN = "http://127.0.0.1:8010"; $env:PORT = "3000"; node build/index.js   # -> http://localhost:3000
> ```

---

## 3. Start the RuneLite dev client with the plugin side-loaded

`run.ps1` points the shell at your JDK 11 and runs the Gradle `run` task (which
launches RuneLite with this plugin loaded from source).

```powershell
# from the plugin repo
cd C:\Users\you\Git_Projects\runetools_plugin

.\run.ps1                 # launch RuneLite with the plugin side-loaded (gradle 'run')
.\run.ps1 build           # just compile (no client) — fastest way to check it builds
.\run.ps1 shadowJar       # build a side-loadable fat jar -> build\libs\runetools-1.0.0-all.jar
```

Any argument you pass is forwarded to Gradle, e.g. `.\run.ps1 clean build`.

> `run.ps1` sets `JAVA_HOME` to `C:\Users\you\tools\jdk\jdk-11.0.31+11`. If you move
> or upgrade the JDK, edit that line.

---

## 4. Log the dev client into your Jagex account

The Jagex Launcher only launches the *official* RuneLite, not the `gradlew run` dev
build. To log the dev client into a Jagex account you pass the launcher's five
credential tokens as environment variables **in the same shell** before `run.ps1`:

```powershell
$env:JX_DISPLAY_NAME  = "<your display name>"
$env:JX_CHARACTER_ID  = "<character id>"
$env:JX_SESSION_ID    = "<session id>"
$env:JX_ACCESS_TOKEN  = "<access token>"
$env:JX_REFRESH_TOKEN = "<refresh token>"

.\run.ps1
```

Getting the token values is the one manual step — they're minted by the Jagex
Launcher when it starts a game session. The reliable way to capture them:

1. Launch the **official** RuneLite once from the Jagex Launcher (this creates a
   session and sets the `JX_*` vars on that process).
2. Read them off the running `JagexLauncher`/`RuneLite` process's environment and
   paste them into the block above. (A small helper can dump these; ask and I'll add
   a `capture-jx.ps1` that grabs them automatically.)

Tokens are short-lived — if login fails with an auth error, re-launch from the Jagex
Launcher and re-capture.

> If you only need to test UI/sync and not a specific Jagex character, you can also
> just log in with a regular (email/password) account in the dev client's login
> screen and skip this section entirely.

---

## 5. Point the plugin at your local server

> **The plugin now sends only over HTTPS.** For security it upgrades *any* `http://` Server URL
> to `https://` — **localhost included** (there is no cleartext-loopback exception anymore). So the
> local server the plugin talks to must serve TLS with a certificate the dev client's JVM trusts.
> Do the one-time **mkcert** setup in §5a first, then point the plugin at **`https://localhost:5173`**.

### 5a. One-time local HTTPS setup (mkcert)

`mkcert` creates a *locally-trusted* CA, so neither the browser nor the plugin's JVM throws cert
warnings (a bare self-signed cert would fail OkHttp's TLS handshake).

```powershell
# Install mkcert once (choose one), then install its CA into the OS + the SAME JDK run.ps1 uses,
# so the dev client's JVM trusts it. JAVA_HOME must point at that JDK when you run -install.
choco install mkcert            # or: scoop install mkcert
$env:JAVA_HOME = "C:\Users\you\tools\jdk\jdk-11.0.31+11"
mkcert -install                 # adds the CA to Windows + that JDK's cacerts (keytool via JAVA_HOME)

# Make a cert for localhost in the SvelteKit dir.
cd C:\Users\you\Git_Projects\ge-trader\web-sveltekit
mkcert localhost 127.0.0.1 ::1  # -> localhost+2.pem  +  localhost+2-key.pem
```

Then have Vite serve HTTPS with that cert (in `web-sveltekit/vite.config.js`) — the existing
`/api` + `/static` proxy to the plain-http backend on `:8010` stays exactly as-is, so only Vite
needs the cert:

```js
import fs from 'node:fs';
// inside defineConfig({ ... }):
server: {
  https: {
    key:  fs.readFileSync('localhost+2-key.pem'),
    cert: fs.readFileSync('localhost+2.pem'),
  },
  // keep your existing proxy block (/api + /static -> http://127.0.0.1:8010)
}
```

`npm run dev` now serves **`https://localhost:5173`** (open that in the browser too). The backend on
`:8010` stays plain http; Vite proxies to it over loopback. *(Ask and I'll wire the `vite.config.js`
change for you.)*

### 5b. Configure the plugin

In the running dev client:

1. Open the **RuneTools** plugin settings (config panel).
2. **Server URL** → `https://localhost:5173` — the TLS Vite dev server from §5a, which proxies
   `/api/plugin/*` through to the `:8010` backend.
   - Any `http://` you enter is auto-upgraded to `https://`, so use `https://` directly.
3. **API key** → paste the `rtk_…` key from step 2 (generated on `:5173`).
4. Tick **I consent to sending my account data to RuneTools**.
5. Tick **Include bank** if you want bank sync (needed for "From my bank" BIS and shared
   bank layouts).
6. Open the **RuneTools** side panel → **Test connection** → should read
   *"Connected as <you>"*. (A TLS/`SSLHandshakeException` here means `mkcert -install` didn't reach
   this JDK's `cacerts` — re-run it with `JAVA_HOME` set to the JDK `run.ps1` uses.)

Sync lands in the same database the browser reads through the proxy, so after syncing (e.g.
open the Combat Achievements interface in-game once), refresh
`http://localhost:5173/combat-achievements` and your tasks/completion appear.

Bank sync note: the bank only uploads **when you open your bank in-game** (with
*Include bank* on). Once captured it stays synced across logins.

---

## 6. Run the tests

```powershell
# site / API tests (from ge-trader, venv active)
cd C:\Users\you\Git_Projects\ge-trader
.\.venv\Scripts\Activate.ps1
pytest                                  # whole suite (quiet)
pytest tests/test_plugin_sync.py -v     # just the plugin-sync endpoints
pytest -k from_bank -v                  # just the "From my bank" BIS tests

# plugin compile check (from runetools_plugin)
cd C:\Users\you\Git_Projects\runetools_plugin
.\run.ps1 build
```

---

## Quick reference — the terminals

```powershell
# Terminal A — backend / API (plugin sync target)
cd C:\Users\you\Git_Projects\ge-trader
.\.venv\Scripts\Activate.ps1
ge-trader serve --port 8010                     # http://127.0.0.1:8010  (API only)

# Terminal B — SvelteKit frontend (the site UI you actually open)
cd C:\Users\you\Git_Projects\ge-trader\web-sveltekit
npm run dev                                      # https://localhost:5173  <- open this (proxies /api to :8010; needs §5a mkcert)

# Terminal C — RuneLite dev client (set JX_* first if using a Jagex account)
cd C:\Users\you\Git_Projects\runetools_plugin
.\run.ps1

# Terminal D — tests, on demand
cd C:\Users\you\Git_Projects\ge-trader
.\.venv\Scripts\Activate.ps1
pytest -q
```

> **In short:** backend on `:8010` (plain http, proxied), frontend on `https://localhost:5173` (the
> UI you open — TLS via §5a mkcert), and the **plugin points at `https://localhost:5173`** (it now
> requires HTTPS everywhere). Sign in on `:5173`.
