# ForestEar — low-cost acoustic surveillance for forests

ForestEar listens to the forest and tells rangers what it hears: **chainsaws,
gunshots, vehicles and humans**, while **ignoring thunder, rain and wind** and
**logging biodiversity** (birds) as a by-product. A 9-model ensemble runs on a
cheap edge device (laptop / Raspberry-Pi-class), only tiny JSON alerts and
optional 5 s non-speech clips leave the node, and a FastAPI backend +
dashboard correlate multi-node detections into a localisation estimate
(TDOA) and push near-real-time alerts (Telegram, dry-run by default).

Everything in this repository runs end-to-end on one laptop — no cluster, no
paid APIs, no fabricated numbers.

```
  mic / file / simulated forest  ──►  edge agent (9 models, TFLite)
                                          │  JSON alerts only (privacy: no speech stored)
                                          ▼
                     FastAPI backend ──► correlator (TDOA) ──► Telegram + dashboard
                                          ▲                        (ERA-style live UI)
                                          │  heartbeats + live telemetry
                                     SQLite offline queue (survives network loss)
```

## The 9-model ensemble

| # | Model | What it is | Input |
|---|-------|-----------|-------|
| M1 | YAMNet | Google AudioSet embedding CNN (frozen, hub SavedModel + official TFLite) | waveform |
| M2 | gunshot CNN | small custom CNN | log-mel 64×98 |
| M3 | chainsaw head | MLP on YAMNet embeddings | 1024-d |
| M4 | vehicle head | MLP on YAMNet embeddings | 1024-d |
| M5 | weather CNN | rain/wind/thunder/quiet classifier (false-alarm suppressor) | log-mel 64×98 |
| M6 | anomaly AE | conv autoencoder trained on benign only, rolling z-score | log-mel 64×98 |
| M7 | human head | MLP on YAMNet embeddings | 1024-d |
| M8 | BirdNET | Google INT8 TFLite, duty-cycled (1/10 windows) for biodiversity only | waveform |
| M9 | LightGBM fusion | OOF-stacked on all of the above + handcrafted + temporal context, isotonic-calibrated | 47 features |

Features: log-mel **64×98** @16 kHz, 1.0 s window / 0.5 s hop, per-window
normalisation (identical code path at train and inference — unit-tested).

## Quick start

```bash
make setup          # create ../.venv and install pinned requirements
make data           # download datasets + pretrained models (idempotent, cached)
make dataset        # build data/dataset/manifest.csv (unified, fold-respecting)
make backgrounds    # benign/ambient bank for augmentation + M6
make cache          # ONE heavy pass: log-mel + YAMNet embeddings + handcrafted
make train          # M2..M7, then M9 fusion (OOF stacking + calibration)
make export         # TFLite export + parity check + edge latency benchmark
make eval           # full evaluation -> reports/ (JSON + summary.md)

make backend        # FastAPI + dashboard at http://127.0.0.1:8000
make demo           # 4 virtual forest nodes -> backend (needs `make train`)
make test           # pytest suite (133 tests)
```

`make data` needs ~2 GB and a few minutes (parallel downloads, resumable).
If you are on a slow link: `make data-small` (caps UrbanSound8K classes and
FSD50K per-label counts).

### One-liner demo (no training needed for the UI)

```bash
make backend &          # terminal 1
make smoke              # terminal 2: synthetic traffic into the dashboard
```

### Real audio

```bash
make agent-mic                          # laptop microphone
make agent-file F=path/to/file.wav      # replay a wav file or directory
```

## Simulator scenarios (`make demo*` / `simulator/run_nodes.py`)

| scenario | what it proves |
|----------|----------------|
| `chainsaw_approach` | k-of-n persistence, chainsaw WARNING → sustained CRITICAL escalation |
| `gunfire_burst` | 3+ node TDOA localisation of impulsive shots (six impulses → three cooldown-limited alerts, 11 m error, 29 m radius) |
| `thunderstorm_with_distant_thunder` | **must not** raise a gunshot alert (loud thunder, no false alarm; veto path unit-tested) |
| `rainstorm_with_chainsaw` | **must not** over-veto a real chainsaw in rain |
| `poacher_party` | humans + vehicle + gunshot composite event |
| `vehicle_track` | vehicle persistence as a real clip passes from ~760 m to ~11 m and away again: two WARNINGs (node-03 at 41 m, node-01 at 11 m), each 3/5 |
| `quiet_day` | zero false alarms on benign ambience (biodiversity only) |

Virtual nodes get per-node clock offsets, distance attenuation, HF roll-off
and propagation delay; the audio is mixed from **real dataset clips** when
available (synthetic generator fallback). Ground truth CSV is written to
`data/sim/<scenario>/`.

## What is evaluated (and how honestly)

`make eval` produces `reports/`:

- `metrics_per_model.json` — per-model test-split metrics (M2–M7, M9, baselines)
- `confusion.json` — M9 confusion matrix + per-class P/R/F1
- `ablation.json` — M9 feature-group ablations (LightGBM retrained per group)
- `robustness.json` — per-data-source breakdown (domain-shift view)
- `calibration.json` — ECE + Brier before/after isotonic calibration
- `decision_sim.json` — k-of-n persistence + weather-veto system simulation
  (false alarms per node-day)
- `summary.md` — every number above, generated **only** from actual
  predictions made by this script on the held-out test split

Fold discipline: ESC-50 folds 1-3/4/5 → train/val/test; UrbanSound8K
1-6/7/8-10; FSD50K dev(train/val)/eval; synthetic 80/10/10 by index hash.
A unit test enforces that no `origin_id` spans folds or splits.

## Dashboard

`dashboard/` (served at `/`): live map with pulsing node pins and TDOA
estimate circle, per-model score bars, streaming spectrogram, event feed
with expandable per-model detail, thunder-veto feed, biodiversity tab with
species list + hourly Chart.js charts, analytics tab (loads
`reports/*.json`), event ticker and health grid.

The map uses Leaflet + CARTO basemap tiles, so **basemap tiles need internet**;
pins and estimate circles still render without it. The tile URL, attribution
and max zoom are configurable in `configs/default.yaml` (`dashboard:`) or with
`FORESTEAR_TILE_URL` / `FORESTEAR_TILE_ATTRIBUTION` / `FORESTEAR_TILE_MAX_ZOOM`.
Details, accuracy caveats and the measured rendering check:
[`docs/map.md`](docs/map.md).

## Phone node (Android)

`android/ForestEarNode/` is a second, fully on-phone implementation of the
edge agent for one retired **OnePlus Nord CE 2 Lite 5G**. It runs the same
9 models with TFLite, the same feature contract and the same decision logic;
the Python code remains the reference and the Android code is parity-tested
against it.

- **All inference is local.** Network carries JSON alerts/heartbeats (and, if
  explicitly enabled, raw PCM for debugging). No speech is stored or sent.
- **9-model pipeline**: YAMNet + gunshot/chainsaw/vehicle/human heads +
  weather CNN + anomaly AE + duty-cycled BirdNET + M9 tree ensemble with
  isotonic calibration.
- **Reliability**: foreground service, wake lock, boot receiver, WorkManager +
  AlarmManager watchdog, task-removal restart, battery-exemption wizard,
  thermal governor, low-battery modes, battery CSV, heartbeat telemetry.
- **Field features**: built-in vs USB-C/TRRS headset mic selection and
  calibration, dual-SIM SMS fallback for CRITICAL events, opt-in ambient
  collection with human-presence deletion, raw PCM WebSocket, self-test,
  CPU-vs-GPU benchmark and a 24 h soak report.
- **UI**: Compose app with Status / Setup / Diagnose / Settings / Tools.

Build and test:

```bash
cd android/ForestEarNode
export JAVA_HOME=/Library/Java/JavaVirtualMachines/openjdk-21.jdk/Contents/Home
./gradlew :app:testDebugUnitTest     # 11 JVM parity/config/decision tests
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Measured JVM parity (this checkout): log-mel max abs diff **1.01e-6**,
handcrafted **2.88e-8**, M9 trees **1.11e-16** vs Python. Instrumented
on-phone parity/latency tests are written but **have not been run** here, so
no phone latency number is claimed. See
[`android/ForestEarNode/README.md`](../android/ForestEarNode/README.md),
[`docs/phone_setup_nord_ce2_lite.md`](docs/phone_setup_nord_ce2_lite.md) and
[`docs/HARDWARE.md`](docs/HARDWARE.md).

## Repository layout

```
configs/default.yaml     single source of truth (thresholds, folds, seeds)
common/                  config, logging
ml/                      features, augment, synth, models M2-M9, training, export
edge_agent/              zoo (9-model inference), decision engine, transport, sources
backend/                 FastAPI app, correlator (TDOA), alerts (Telegram), ORM
simulator/               forest simulator + 7 scenario YAMLs
scripts/                 download_data, build_dataset, make_background_bank, evaluate, smoke_ingest
dashboard/               ERA-style single-page UI (no build step)
tests/                   pytest suite (features, decision, TDOA, API, transport, splits, metrics, simulator)
docs/                    architecture.md, model_cards.md, demo_script.md,
                         phone_setup_nord_ce2_lite.md, HARDWARE.md
../android/ForestEarNode/  Kotlin/Compose phone node (9 models on-device)
../android/scripts/        adb install/self-test/soak/collection helpers
../android/tools/          asset exporter + raw PCM WebSocket receiver
```

## Assumptions (deviations from the original spec, made deliberately)

1. **Python 3.12**, not 3.11 — 3.12 is the only interpreter on the demo
   machine; all pinned dependencies support it. No 3.11-only behaviour is
   used.
2. **tensorflow-hub is not used** — the package's `pkg_resources` import is
   broken on modern setuptools. YAMNet loads from the same hub SavedModel
   tarball via `tf.saved_model.load` (verified numerically equal), and the
   official Google TFLite build via `tf.lite.Interpreter`.
3. **UrbanSound8K is incomplete**: 3649 of the 3803 in-scope clips are on
   disk; 161 clips were unreachable after retries (marked FAILED in
   `data/raw/download_manifest.txt`). Training/eval counts reflect the
   clips that exist.
4. **FSD50K has no chainsaw label** — chainsaw data comes from ESC-50 +
   the synthetic generator (which is also used as augmentation).
5. **FSD50K is a subset** (per-label cap, dev+eval splits respected) to keep
   the download and window-cache tractable on an 8 GB laptop.
6. **Simulator audio uses real dataset clips when available** — this makes
   the *demo* realistic but it is **not** an evaluation set; reported
   metrics come only from `make eval` on the held-out test split.
7. **Telegram runs in dry-run by default** (`alerts.telegram.dry_run: true`)
   — alerts are logged to console. Provide `TELEGRAM_BOT_TOKEN` /
   `TELEGRAM_CHAT_ID` env vars and set `enable: true` to actually send.
8. **Auth token** defaults to `forestear-dev-token` (config), overridable
   via `FORESTEAR_TOKEN`. No secrets are stored in the repo.
9. Fixed seeds everywhere (`training.seed: 42`, per-window deterministic
   hashes) — reruns are reproducible up to TF/CPU non-determinism.
10. **The Android app compiles/targets SDK 34 while the target phone runs
    Android 13.** Android 14 foreground-service microphone rules are
    implemented defensively (start as `dataSync`, upgrade to `microphone` when
    the activity is visible) so the same APK is valid on both.
11. **M9 on Android is a generated tree ensemble**, not the Python LightGBM
    pickle: `android/tools/export_android_assets.py` serialises the trees and
    isotonic calibrators to `config/m9_trees.bin` + JSON, and a JVM test
    compares the result with native `predict_proba` (max abs diff 1.11e-16).

## Limitations (what this is not)

- **Not a production anti-poaching system.** It is a minor-project
  demonstration of the full pipeline on one laptop.
- **Model quality is bounded by small public data** (a few thousand clips,
  1 s windows). Numbers in `reports/summary.md` are honest for *this*
  dataset split, not benchmarks against literature.
- **3-node TDOA is two-fold ambiguous** (hyperbola intersection): the
  correlator multi-starts, keeps the lowest-residual solution and inflates
  the reported radius to cover the alternative when both fit; 4+ nodes
  resolve it. A fit with median residual > 40 m (bad geometry, clock
  skew) is refused rather than published.
- **Clock synchronisation is simulated** (known offsets); real deployments
  need NTP/PTP discipline for metre-level TDOA.
- **M6 anomaly detection is open-set by construction** and will also flag
  unusual benign sounds; it escalates to INFO only.
- **No human speech is ever stored or transmitted** (privacy rule): clips
  are only attached for non-speech classes, `human` events carry scores
  only.
- **Live telemetry is fire-and-forget** (`/ingest/scores` is WS-relay only,
  never persisted) — dashboards show what arrives while they are open.
- Battery/CPU numbers in heartbeats come from `psutil` on the host machine,
  not a real sensor board.
- **The Android port is parity-tested on the JVM but not yet benchmarked on the
  physical phone.** Instrumented pipeline/latency tests and the 24 h soak are
  included; run them on the device before quoting phone numbers. The 5.56 ms
  figure is the Python laptop pipeline, not the phone.
- **Synthetic golden gunshot is out-of-distribution for M9**: M2 scores 0.906
  on that impulse while M9 gives gunshot 0.131 / benign 0.869. The Python
  pipeline produces the same result, so this is a property of the synthetic
  fixture, not an Android bug. Real gunshot clips in the test split are what
  the 0.8186 macro-F1 refers to.
- **The phone microphone is not characterised**, and built-in processing can
  differ from training data when `UNPROCESSED` is unavailable. The calibration
  screen reports the actual source and effect state.
- The dashboard design intentionally mirrors the aesthetics of
  era-residence.com (typography-inspired stand-ins, no assets copied) as an
  exercise in editorial UI design.

## Data attributions

Full third-party inventory with links and redistribution notes: [`NOTICE.md`](NOTICE.md).
Original source code is MIT-licensed ([`LICENSE`](LICENSE)); models and datasets
keep their own licenses.

- **ESC-50** (Piczak) — CC BY-NC 3.0, github.com/karolpiczak/esc-50
- **UrbanSound8K** (Salamon, Jacoby, Bello) — CC BY-NC 3.0, via the
  HuggingFace mirror per-clip + community metadata CSV
- **FSD50K** (Fonseca et al.) — CC BY 4.0, via the Fhrozen HuggingFace mirror
- **YAMNet** (Google) — Apache 2.0 (SavedModel + official TFLite builds)
- **BirdNET 2.4** (Kahl et al., Cornell) — CC BY-NC-SA 4.0, INT8 TFLite build

Several of these are non-commercial. Check each dataset's terms before
redistributing any derived artifacts or deploying commercially.

## Tests

```bash
make test
```

Covers: log-mel contract (64×98, normalisation, truncation, dominant-bin
frequency), YAMNet hub↔TFLite parity, decision logic (k-of-n, high-conf
gunshot, weather veto + over-veto guard, cooldown, chainsaw escalation
reset), fold discipline + leakage guards, metric helpers (cross-checked
against sklearn), TDOA solver accuracy + ambiguity handling, API contract
(token auth, ingest/query/WS relay, isolated temp DB), offline queue flush,
privacy clip rule.

## Operations, privacy and CI

These are the non-model parts of running ForestEar. Every number quoted in the
linked docs was measured on this checkout; anything unexercised says so.

| Topic | Doc | Status |
|---|---|---|
| Docker backend image + compose | [`docs/docker.md`](docs/docker.md) | built and exercised locally (388 MB image) |
| Telegram alerts (fake-server tests + manual test) | [`docs/telegram_setup.md`](docs/telegram_setup.md) | 11 automated tests pass; real bot delivery **not verified** |
| Backend load test | [`docs/load_test.md`](docs/load_test.md) | measured: 20 nodes, 37.98 req/s, 0 errors, p95 119 ms |
| Field-audio evaluation | [`docs/field_eval.md`](docs/field_eval.md) | harness tested; **no real field audio evaluated** |
| Privacy, consent and retention | [`docs/PRIVACY.md`](docs/PRIVACY.md) | policy text; retention is manual, not enforced |
| CI (pytest) | [`.github/workflows/ci.yml`](.github/workflows/ci.yml) | configured; not yet run on GitHub |
| License and attributions | [`LICENSE`](LICENSE), [`NOTICE.md`](NOTICE.md) | present |

Useful commands:

```bash
docker compose build && docker compose up -d      # backend container
../.venv/bin/python scripts/load_test.py --nodes 20 --duration 20
../.venv/bin/python scripts/field_eval.py --dir data/field --dry-run
```

Honest limits that apply to all of the above:

- The backend token is a shared dev credential over HTTP; put TLS and real auth
  in front before exposing it.
- Telegram delivery to a real chat has not been tested from this workspace.
- No field recordings have been evaluated, so there are no field accuracy or
  false-alarm numbers.
- Docker compose was tested on one laptop; SQLite and a single process are not
  a production multi-node deployment.
