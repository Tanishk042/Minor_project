# ForestEar architecture

## 1. System overview

```
                       ┌────────────────────────────────────────────────┐
                       │                 EDGE NODE (x N)                │
 mic / file / sim ──►  │  sources ─► 1 s windows (0.5 s hop)            │
                       │            ─► zoo: M1..M9 (TFLite where built) │
                       │            ─► DecisionEngine (k-of-n, veto,    │
                       │               cooldown, escalation)             │
                       │            ─► Transport (HTTP + offline queue)  │
                       └───────────────────┬────────────────────────────┘
                                           │ POST /ingest/* (JSON, ~1 KB)
                                           │ + X-ForestEar-Token
                                           ▼
        ┌──────────────────────────── FastAPI backend ───────────────────────┐
        │  /ingest/event ─► SQLite ─► alert router (Telegram dry-run)        │
        │  /ingest/heartbeat ─► node health            ─► WS /live fan-out   │
        │  /ingest/scores ─► relay only (never stored)─► WS /live fan-out   │
        │  gunshot events ─► correlator (TDOA / bearing / radius)           │
        │  /nodes /events /estimates /biodiversity/summary /analytics       │
        │  /export/events.csv  /reports/{name}.json  /clips/{name}          │
        └───────────────┬──────────────────────────────────────────────────┘
                        ▼
                 dashboard (static, served at /)
```

### Edge node (the honest centre of the project)

`edge_agent/zoo.py` loads all models once and runs them per 1 s window:

1. cheap gate: silence (RMS < −60 dB) skips heavy models;
2. M1 YAMNet → 521 scores + 1024-d embedding;
3. M2 / M5 on log-mel; M3 / M4 / M7 on the embedding; M6 AE every window;
4. M9 LightGBM fusion on the 47-feature vector (embeddings + heads +
   M1-selected scores + handcrafted + M6 z + temporal context);
5. M8 BirdNET on 1-in-10 windows (duty cycle) for biodiversity only;
6. `DecisionEngine` turns scores into alerts (below);
7. `Transport` POSTs the event; on failure it lands in a SQLite offline
   queue that flushes on reconnect (drop-tolerant: `tries > 30` dropped).

Privacy: only scores ever leave for `human`; 5 s clips are attached only
for non-speech classes; speech is never recorded or transmitted.

### Decision logic (`edge_agent/decision.py`, config-driven)

- **k-of-n persistence** per class (e.g. chainsaw 3-of-5 windows) — a
  single window never raises a persistent-class alert.
- **gunshot exception**: one window ≥ 0.85 confidence, or 2-of-3 above a
  relaxed threshold — impulsive events should not wait.
- **weather veto** (`M5`): if max(rain, wind, thunder) ≥ 0.6 *and* gunshot
  confidence < 0.80 → suppressed (logged to `/ingest/suppressed`, shown in
  the dashboard veto feed). A ≥ 0.80 gunshot **always** alerts — the
  thunderstorm demo cannot hide a real shot.
- **cooldown** per class (60 s chainsaw … 600 s anomaly) stops alert
  storms.
- **chainsaw escalation**: ≥ 8 detections within 30 s → CRITICAL; counted
  on detections (not fires, which the cooldown throttles); bypasses
  cooldown exactly once, resets when the sustain lapses.
- **anomaly escalation** (M6): z-score ≥ 0.7 *and* loud (> 35 dB) for 2
  windows → INFO.

### Correlator (`backend/correlator.py`)

Same-class events within 6 s across nodes are grouped:

- **≥ 3 nodes** — Gauss-Newton TDOA in a local ENU frame (speed of sound
  343 m/s). Multi-start detects the two-fold 3-node hyperbola ambiguity
  and inflates the radius to cover both solutions; 4+ nodes are unique.
  A fit with median residual > 40 m (clock jitter / inconsistent arrivals)
  is rejected instead of publishing a meaningless point, and a solution
  > 25 km from the array centroid is refused. The same shot is estimated
  again each time another node reports it, so `_maybe_correlate` keeps one
  row per shot within ±6 s and replaces it when a later fit uses more
  nodes (3-node subset → 4-node).
- **2 nodes** — hyperbola bearing band (honest: radius = node separation).
- **1 node** — coarse inverse-square radius from received RMS, labelled
  `single` (never drawn as a position claim on the map).

Estimates land in `source_estimates` and are broadcast to the dashboard,
which draws the uncertainty circle.

## 2. Data pipeline

```
scripts/download_data.py   # idempotent, parallel, per-file cache
        │  data/raw/{ESC-50-master, urbansound8k, fsd50k} + models_store/
        ▼
scripts/build_dataset.py   # unified manifest, fold-respecting
        │  data/dataset/manifest.csv  (path, class, source, fold, origin_id)
        ▼
scripts/make_background_bank.py   # benign/weather ambience slices
        │  data/background/
        ▼
ml/window_cache.py         # ONE heavy pass; every later stage reads this
        │  data/cache/windows.npz  (log-mel f16, emb f32, handcrafted, M1 scores,
        │                          y, split, origin)
        ▼
ml/train_m2_m7.py  ─► ml/train_m9_fusion.py (OOF stacking + isotonic)
        │  ml/models_store/{m2..m7, m9}/
        ▼
ml/export_tflite.py        # + parity check + latency benchmark
        ▼
scripts/evaluate.py        # reports/*.json + summary.md (test split only)
```

### Fold discipline

| source | train | val | test |
|--------|-------|-----|------|
| ESC-50 | folds 1-3 | 4 | 5 |
| UrbanSound8K | folds 1-6 | 7 | 8-10 |
| FSD50K | dev/train | dev/val | eval |
| synthetic | 80 % | 10 % | 10 % (index hash) |

`tests/test_split.py` asserts no `origin_id` spans folds or splits, and the
cache builder re-checks at build time.

### M9 stacking (no leakage)

`train_m2_m7.py` fits M2–M7 on the **train** split only. `train_m9_fusion.py`
then runs those frozen models over the **val** split — data none of them ever
trained on — so their outputs there are out-of-fold (OOF) predictions. The
M9 LightGBM fits a stratified 75 % slice of that OOF(val) feature matrix;
per-class isotonic calibration is fit on the held-out 25 % (same seed,
same split as the deployed artifact). The test split is touched exactly
once, by `scripts/evaluate.py`. Evaluation-time ablations (`ablation.json`)
retrain LightGBM with a feature group zeroed, following this exact protocol,
so they are comparable to the deployed M9.

## 3. Simulator (`simulator/`)

`forest_sim.py` renders each virtual node's microphone: real dataset clips
(test-fold preferred) attenuated by distance (inverse-square + causal
one-pole HF roll-off `max(1200, 9000−9d)`) with propagation delay `d/343`,
mixed over synthetic forest ambience and occasional gusts. Sources with
`clip_select: sustained` (used by `vehicle_track`) audition eight candidate
clips and keep the one whose *quietest* 0.5 s window is loudest, so a looped
pass-by recording cannot silently drop out at an arbitrary scenario time.
The lowpass is causal with per-source state carried across hops: a zero-phase
FFT filter pre-rings, which leaked energy before an impulse's true onset and
corrupted TDOA. Each node builds its 1 s analysis window from consecutive
0.5 s hops (no zero padding), and the edge agent refines each event timestamp
to the impulsive onset inside that window (4 ms RMS envelope, anchored on the
loudest frame, walked back to the attack) so cross-node arrival times are
comparable. Sources are silent before their first trajectory point.
Per-node clock offsets are injected via the scenario YAML. `run_nodes.py`
wires each node to an in-process `EdgeAgent` (own decision state, own
offline queue) against a real backend so the demo exercises the full stack —
ingest, correlation, alerts, dashboard. Ground truth per source (active
interval + position) is written to `data/sim/<scenario>/ground_truth.csv`.

## 4. Backend details

- SQLite via SQLAlchemy (`data/backend.db`); one row per event, heartbeat,
  suppressed event, biodiversity detection, source estimate.
- Ingest rate limit: 120 requests/min/node (guards a misbehaving node).
- `/ingest/scores`: relay-only live telemetry (WS fan-out, never stored).
- WebSocket `/live`: dashboard subscribes; a 0.25 s flusher drains the
  pending broadcast queue only while clients are connected (replay-safe
  otherwise).
- `/reports/{name}` serves `reports/*.json` to the analytics tab; missing
  reports (eval not run yet) 404 and the UI shows a note.
- Static dashboard mounted at `/` (no build step; Chart.js from CDN).

## 5. Configuration

`configs/default.yaml` is the single source of truth — thresholds, fold
maps, seeds, veto and persistence parameters, duty cycles, export
tolerances. Edge agent, training, simulator and tests all read it through
`common/config.py`, so behaviour cannot drift between phases.
