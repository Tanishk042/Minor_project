# Dashboard map

The live dashboard at `/` has a **Sector map** section: an embedded Leaflet map
showing node pins and, when the correlator produces one, the estimated source
position with its uncertainty circle.

## What is drawn

| Layer | Source | Behaviour |
|---|---|---|
| Basemap tiles | CARTO light basemap (`basemaps.cartocdn.com`), OpenStreetMap data | loaded from the internet; no offline tile bundle is shipped |
| Node pins | `GET /nodes` every 20 s | one pulsing pin per node with coordinates; colour = healthy / degraded / offline; click selects the node and its score panel |
| Alert pulse | WebSocket `/live` `event` messages | short pulse at the event's reported position |
| Source estimate | WebSocket `source_estimate` + newest row of `GET /estimates?limit=50` on page load | circle centred on `lat`/`lon` with radius `radius_m`; toast shows method and ±metres; circle disappears after 20 s |
| Footer | live client state | `N nodes · M healthy · HH:MM:SS UTC` |

The legend in the section header repeats the healthy / degraded / offline /
estimate colours.

## How it behaves

- Nodes **without** coordinates are not pinned. They still appear in the
  health grid and the node dropdown; the map does not invent a position.
- The map fits its bounds to the node positions once, then keeps the user's
  pan/zoom. New estimates refit the view to the circle.
- On page load only the **newest** stored estimate is drawn. Older estimates
  remain in the database and in `/estimates`, but there is no history layer or
  timeline scrubber yet.
- Switching tabs calls `map.invalidateSize()` on return, so the map is not left
  blank after visiting Analytics or Biodiversity.
- Dashboard assets are served with `Cache-Control: no-cache, must-revalidate`,
  so a browser never keeps an old dashboard after an update.

## Changing the basemap (no code edit)

The dashboard asks the backend for its tile settings at
`GET /dashboard-config`. Values come from `configs/default.yaml`:

```yaml
dashboard:
  tile_url: "https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}{r}.png"
  tile_attribution: "&copy; OpenStreetMap &copy; CARTO"
  tile_max_zoom: 19
```

Edit that block and restart the backend. Environment variables override the
file, which is convenient for Docker or a one-off run:

```bash
FORESTEAR_TILE_URL="https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png" \
FORESTEAR_TILE_ATTRIBUTION="&copy; OpenStreetMap &copy; CARTO" \
FORESTEAR_TILE_MAX_ZOOM=19 \
../.venv/bin/python -m backend.main --port 8000
```

`tile_url` must be `http(s)` and contain `{z}`, `{x}` and `{y}`; anything else
is rejected with a log warning and the built-in default is served.
`tile_max_zoom` is clamped to 1–22. The dashboard starts with the default
CARTO layer, then swaps to the configured one when the fetch succeeds, so a
failed request leaves a working map.

`/dashboard-config` is public because the dashboard is public. Never put a
secret in `tile_url`; a key placed there is visible to every visitor.

## Using a CARTO API key

Keep the key out of tracked files. Put it in `forestear/.env` (gitignored) and
start the backend with `make backend`, which sources that file. Docker Compose
reads the same `.env` automatically.

```bash
# forestear/.env  (never commit; see .env.example)
FORESTEAR_TILE_URL="https://basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}.png?key=YOUR_KEY"
FORESTEAR_TILE_ATTRIBUTION="&copy; OpenStreetMap &copy; CARTO"
FORESTEAR_TILE_MAX_ZOOM=19
```

The **raster** Voyager URL works with the current Leaflet map — no code change.
Verified on this checkout: 9 tiles loaded at 256×256, all HTTP 200, no console
errors.

The **vector** style URL
(`https://basemaps.cartocdn.com/gl/voyager-gl-style/style.json?key=...`)
returns HTTP 200, but it is a MapLibre/Mapbox style document, not a raster tile
template. Using it means replacing Leaflet with MapLibre GL JS and porting the
pins, pulses and estimate circles to that renderer. That is **not implemented**
here.

A key used by a browser map is always visible to the browser. Restrict it in
the CARTO dashboard (allowed referrers/domains, usage limits) and treat a key
pasted into a chat, ticket or screenshot as compromised — rotate it.
Step-by-step rotation and the backend hardening checklist are in
[`security.md`](security.md). Run `make security-check` before committing.

## Offline / no-internet behaviour

Leaflet and Chart.js are **vendored** in `dashboard/vendor/`, so the dashboard
does not need a CDN to boot. If either library is missing, `app.js` guards its
initialisation: the map panel shows `map unavailable — local map library
missing`, and the live feed, charts, scores and alerts keep working. (Before
this guard, a blocked CDN threw at the first `L.map(...)` and left the whole
page in its placeholder state.)

The basemap itself is still a third-party tile service. Without internet:

- tiles fail to load and the map background is the dashboard's cream colour;
- node pins, alert pulses and estimate circles still render, because they are
  local vector/DOM layers;
- the browser console shows failed tile requests, but no dashboard feature
  breaks.

For a fully offline deployment you would need to host your own tile server or
bundle a small regional tile set. Neither is included.

`index.html` loads `style.css?v=2` and `app.js?v=2`. Bump that version when you
change either file; it stops browsers from running a stale cached copy after an
upgrade.

## Accuracy caveats (important)

- The circle is the correlator's **reported uncertainty radius**, not a
  statistically calibrated confidence interval.
- TDOA assumes the nodes' clocks are aligned closely enough. The backend does
  **not** correct per-node clock offsets; the Android app can report an SNTP
  offset, but the correlator does not currently apply it.
- With 2 nodes the estimate degrades to a bearing band; with 1 node to a coarse
  radius. The method field (`tdoa`, `bearing`, `single`) says which one you got.
- A fit whose residual exceeds the configured limit is refused and **nothing is
  plotted** — a blank map is a valid "no reliable estimate" result, not a bug.
- The measured localisation numbers (11 m error, 29 m radius in the
  `demo-gunfire` scenario) come from the **simulator**, not from a field
  deployment. Do not quote them as field accuracy.

## Verified on this checkout (2026-11-16)

Checked in headless Chromium against a scratch backend:

- page loads with no console errors;
- `#map` measures **435.9 px** tall (the container previously collapsed to
  **0 px** because no `#map` height rule existed — fixed, with regression
  tests);
- 10 CARTO tiles returned HTTP 200;
- 4 node pins rendered from `/nodes`;
- a source-estimate circle rendered as a **206 px**-diameter SVG path.

The tile and marker check was a UI rendering check with synthetic node rows in
a scratch database (`FORESTEAR_DB=/tmp/forestear_map.db`). It does not claim a
real TDOA run; use `make demo-gunfire` for that.

Basemap override was checked end to end the same day: running the backend with
`FORESTEAR_TILE_URL=".../dark_all/..."` made `/dashboard-config` return the
dark URL, and the browser requested **10 dark tiles** and showed the updated
attribution (`© OpenStreetMap © CARTO (dark)`), with `#map` still 435.9 px.

## Verified on this checkout (2026-11-17)

- Leaflet 1.9.4 and Chart.js 4.4.3 are served from `dashboard/vendor/`;
  the page made **zero** requests to `unpkg.com` or `cdn.jsdelivr.net`.
- With the keyed Voyager URL loaded from `.env`, the browser requested
  **8 keyed raster tiles**, all HTTP 200, at 256×256; `#map` measured 404 px;
  4 pins rendered; console errors: **0**.
- Degradation check: an iframe with the Leaflet `<script>` removed produced
  **0 uncaught errors**, showed `map unavailable — local map library missing`,
  and the live ticker still updated.

## Tests

`tests/test_api.py::TestDashboard` asserts that the page contains the map panel,
that the stylesheet sizes `#map`, that dashboard assets are revalidated, that
Leaflet/Chart.js are vendored (not CDN) and the init is guarded, and that
`/dashboard-config` reads YAML, prefers environment variables, rejects a
non-`http(s)` URL and clamps `tile_max_zoom`. Visual rendering is not covered by
pytest.
