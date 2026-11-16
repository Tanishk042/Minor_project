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

## Offline / no-internet behaviour

The basemap is a third-party tile service. Without internet:

- tiles fail to load and the map background is the dashboard's cream colour;
- node pins, alert pulses and estimate circles still render, because they are
  local vector/DOM layers;
- the browser console shows failed tile requests, but no dashboard feature
  breaks.

For a fully offline deployment you would need to host your own tile server or
bundle a small regional tile set. Neither is included.

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

## Tests

`tests/test_api.py::TestDashboard` asserts that the page contains the map panel,
that the stylesheet sizes `#map`, and that dashboard assets are revalidated.
Visual rendering is not covered by pytest.
