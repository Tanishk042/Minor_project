# ForestEar Node — Android edge agent

Fully on-phone acoustic node for the ForestEar project. It runs the same
9-model ensemble as `forestear/edge_agent`, makes decisions locally, and sends
only JSON alerts/telemetry (plus optional 5 s non-speech clips on the Python
agent). It runs on **any Android 8.0+ device with a microphone**; the OnePlus
Nord CE 2 Lite 5G is one entry in the OEM profile table, not a code path.

A **Device Adaptation Layer** probes the handset at first launch and after every
app update, benchmarks the bundled models, chooses a performance tier, and
degrades honestly when a capability is missing. See
[`docs/phone_setup_generic.md`](docs/phone_setup_generic.md),
[`docs/oem_guides.md`](docs/oem_guides.md) and
[`docs/supported_devices.md`](docs/supported_devices.md).

```
AudioRecord 16 kHz mono ──► log-mel 64×98 + handcrafted
        │                         │
        │                         ├── M1 YAMNet (TFLite)  ─┐
        │                         ├── M2 gunshot CNN       │
        │                         ├── M3 chainsaw head     │ 47 features
        │                         ├── M4 vehicle head      ├──► M9 trees + isotonic
        │                         ├── M5 weather CNN       │      (5 classes)
        │                         ├── M6 anomaly AE        │
        │                         └── M7 human head       ─┘
        │                                   │
        │                          decision engine (k-of-n, cooldown,
        │                          weather veto, chainsaw escalation)
        ▼                                   ▼
  optional raw PCM WS              HTTP /ingest/* + offline SQLite queue
  (debug only)                     SMS fallback for CRITICAL (dual SIM)
```

M8 (BirdNET) is duty-cycled and only used for biodiversity, never for alerts.

---

## Build

Requirements: JDK 17+ (JDK 21 recommended), Android SDK 34, one device or
emulator.

```bash
cd android/ForestEarNode
export JAVA_HOME=/Library/Java/JavaVirtualMachines/openjdk-21.jdk/Contents/Home
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The SDK path is read from `local.properties` (`sdk.dir=...`) or `ANDROID_HOME`.
If `local.properties` is missing, create it:

```properties
sdk.dir=/Users/<you>/Library/Android/sdk
```

Model assets (~63 MB) are packaged from `app/src/main/assets` and marked
`noCompress` so TFLite can memory-map them.

### Regenerate assets / golden vectors

The Python side owns the contract. From the repository root:

```bash
./.venv/bin/python android/tools/export_android_assets.py
```

This exports the TFLite models, feature matrices, M9 binary trees, isotonic
parameters, golden inputs and Python-computed golden outputs, then validates
the binary tree export against native `predict_proba`. Re-run it whenever a
model, feature formula or config changes.

---

## Tests

### JVM (no device)

```bash
./gradlew :app:testDebugUnitTest
```

**88 tests across 13 suites, all passing on this checkout:**

| Suite | What it locks down |
|---|---|
| `LogMelParityTest` | Kotlin log-mel vs Python golden, all 6 windows — max abs diff **1.01e-6** |
| `HandcraftedParityTest` | rms/crest/flux/flatness/onset/ZCR vs Python — max abs diff **2.88e-8** |
| `FusionTreesParityTest` | binary M9 trees + isotonic vs Python `predict_proba` — max abs diff **1.11e-16** |
| `DecisionEngineTest` | golden decision sequences, rain veto, no over-veto |
| `ConfigTest` | asset contract (sample rate, shapes, 9 models, 47 features) |
| `TierSelectorTest` | A/B/C/D policy, hardware/model guards, manual override, JSON round-trip |
| `LatencyBudgetTest` | runtime hop → M6 → M8 → streaming-only escalation and hysteretic relaxation |
| `DeviceProfileTest` | profile JSON round-trip with null capabilities, `headline`, `profileKey`, heartbeat summary, mock profile |
| `OemProfilesTest` | OEM table loader, known-device → manufacturer → generic matching, `{package}` expansion, shipped asset |
| `ResamplerTest` | identity scaling, 48k→16k length, DC continuity across blocks, rate negotiation |
| `SntpTest` | 48-byte request, offset/delay maths on a synthetic reply, zero/short timestamp rejection |
| `CompatibilityReportTest` | unmeasured tier, NOT RUN recovery rows, pipe escaping, file slugs |
| `TokenStoreTest` | encrypted round-trip, legacy plaintext migration, Keystore-failure fallback, unreadable ciphertext, clearing |

### Instrumented (needs a device/emulator)

```bash
./gradlew :app:connectedDebugAndroidTest
```

- `PipelineParityTest` runs all nine models on the golden windows and compares
  scores with the Python vectors.
- `LatencyTest` warms up and times 10 full-pipeline windows, enforcing a
  1500 ms sanity budget.
- `DeviceAdaptationTest` exercises the probe, a deliberately incomplete mock
  profile, the shipped OEM table, the compatibility report and the
  recovery recorder without a running service.
- `TokenKeystoreTest` proves the real Android Keystore path encrypts the token
  and leaves no plaintext in SharedPreferences.

**These instrumented tests ran on OnePlus CPH2381 (Android 14) on
2026-11-17:** 9 tests across 4 suites, 0 failures, 0 errors — 5
`DeviceAdaptationTest`, 1 `LatencyTest`, 1 `PipelineParityTest` and 2
`TokenKeystoreTest`. Raw result:
`app/build/outputs/androidTest-results/connected/debug/TEST-CPH2381 - 14-_app-.xml`.

Measured on that handset: full pipeline **49.47 ms mean**, tier A, CPU only
(GPU delegate unavailable), **160.87% CPU**, **321.09 MB RAM**. The earlier
**5.56 ms/hop** figure is the laptop Python pipeline, not a phone number.
The emulator matrix and signed release remain NOT RUN.

---

## What is implemented

### Audio

- `AudioRecord` mono 16-bit. It requests **16 kHz**; if the phone refuses that
  rate it negotiates the nearest supported rate and resamples to 16 kHz in
  `audio/Resampler.kt` (streaming linear interpolation, DC-continuous across
  blocks). The active capture rate and whether resampling is on are shown in
  Diagnostics and the heartbeat.
- Prefers `UNPROCESSED`, falls back to `MIC`; tries to disable AGC/NS/AEC and
  logs which effects were actually present (`EffectStatus`).
- 1.0 s window / 0.5 s hop, silence gate, clipping detection, zero-signal
  detector (a 10 s run of digital zeros is reported as a fatal capture fault
  rather than being analysed as quiet forest).
- Automatic capture restart on read errors; restart and dropped-frame counters;
  `onGap` reports stalls with a reason.
- Input choice: **Auto** (USB → wired → built-in), Built-in, 3.5 mm, USB. An
  `AudioManager.AudioRecordingCallback` (API 29+) reports route changes, and the
  app offers the switch instead of silently changing the input mid-deployment.
  Calls and other apps taking the microphone surface as a capture gap and are
  retried; they are not silently ignored.
- Calibration (non-bar tab): live RMS/peak dBFS, clipping %, detected input,
  effects, and a per-input gain (`builtin` / `headset` / `usb`).
- Optional ambient collection is explicit opt-in, capped and rotated by bytes,
  suspended while inference is paused, and **deletes a whole chunk** if human
  presence is detected. No alert clips are stored on the phone.

### Models and features

- Kotlin log-mel 64×98, handcrafted descriptors and FFT with Python parity.
- TFLite runners handle float32 and int8 quantisation exactly like
  `zoo.py`; LiteRT **CPU + XNNPACK is the default**, GPU delegate is selectable
  per model and only kept when it is both faster and stable. The app does not
  rely on NNAPI.
- M9 fusion runs as a compact little-endian binary tree ensemble
  (`config/m9_trees.bin`, 1500 trees) plus isotonic calibration.
- M8 BirdNET duty-cycled (1/10 by default) and excluded from alerts.
- Adaptive hop: lengthened when a window exceeds the analysis budget, stepped
  back down after sustained fast windows.

### Performance tiers and latency governor

A benchmark with a warm-up pass times every bundled model and feeds
`device/TierSelector.kt`:

| Tier | Models | Hop | Selection rule |
|---|---|---|---|
| A | all 9 | 0.5 s | measured ≤60 % of budget, ≥4 cores, ≥2500 MB RAM, all models loaded |
| B | all 9 | 1.0 s | measured ≤100 % of budget, all models loaded; M6/M8 duty-cycled |
| C | core (M1–M5, M9) | 1–2 s | over budget, or a model failed to load |
| D | core | long hop | far over budget; inference paused, raw PCM streamed |

Before the first benchmark the app starts at **tier C**; a benchmark that does
not produce a finite timing stays there. A manual override in Settings is always
honoured, with a note when it exceeds the budget. `device/LatencyBudget.kt` runs
at runtime: lengthen the hop → disable M6 → disable M8 → streaming-only, then
relax after 12 fast windows. Every change is logged and shown on Status.

### Decision and transport

- Kotlin port of `decision.py`: k-of-n persistence, cooldowns, high-confidence
  gunshot path, weather veto with over-veto guard, chainsaw escalation to
  CRITICAL, anomaly persistence.
- HTTP endpoints match the Python agent (`/ingest/event`, `/ingest/heartbeat`,
  `/ingest/scores`, `/ingest/biodiversity`, `/ingest/suppressed`) with
  `X-ForestEar-Token`.
- Durable SQLite offline queue with exponential backoff and a size cap.
- Optional raw 16 kHz PCM WebSocket (debug only).
- SMS fallback: CRITICAL only, compact payload, dual-SIM subscription
  selection, persisted rate limit, delivery status.

### Reliability (Device Adaptation Layer)

- Foreground service with microphone/dataSync types, partial wake lock, boot
  receiver. `POST_NOTIFICATIONS` (13+) and `FOREGROUND_SERVICE_MICROPHONE` (34+)
  are declared; on API 29 only dataSync is used, and on older releases no type
  is passed.
- **The app does not assume a boot receiver or watchdog can restart the
  microphone.** If a background start is refused, it posts a high-priority
  notification (full-screen intent only when permitted), marks the node
  `DEGRADED`, and says what to do. Android 14+ blocks a microphone FGS from
  starting in the background, so opening the app once after reboot is expected.
- WorkManager (15 min) + AlarmManager (5 min) watchdog; `onTaskRemoved` restart;
  explicit Stop disables auto-start and cancels both watchdogs and WorkManager.
- Persisted uptime and restart count in heartbeats (first launch excluded).
- Capability probe at first launch and after every update writes
  `device_profile.json`; a compact summary rides every heartbeat. Missing
  capabilities are recorded in `missing`/`warnings`, never guessed, and never
  crash the node. `MockCapabilities` provides a deterministic
  missing-capability profile for tests.
- OEM table (`assets/config/oem_profiles.json`, 10 families + the Nord CE 2
  Lite) is data. Deep links are resolved before launch and wrapped in
  try/catch; if none resolve the app opens app-details, then the standard
  battery screen. Plain-language steps are the source of truth;
  dontkillmyapp.com is linked.
- Battery exemption is OS-verified; overlay is OS-verified; every other
  maker switch is user-confirmed and labelled as such. Optional checks never
  fail the overall protection state.
- Thermal governor with hysteresis and low-battery modes; system events and
  notifications on CRITICAL/PAUSED and low/critical battery.
- Battery CSV once a minute; heartbeats include charging, current, temperature,
  thermal status, signal dBm, network type, free storage, uptime, restart count,
  and the SNTP clock offset (reported only; the system clock is never changed).
- Connectivity callbacks drive the offline queue with backoff; a Wi-Fi-only
  device runs fully and reports SMS as missing.

### Diagnostics and recovery

- Hardware self-test: mic/clipping, headset, permissions, protections, network,
  backend, models, SMS, thermal — PASS/WARN/FAIL.
- Per-model CPU vs GPU benchmark with mean/p95 latency, CPU %, RAM; writes
  `benchmark.json` and persists the chosen delegate and tier.
- 24 h soak mode with report (`soak_report.json`, `soak_samples.csv`).
- Recovery-path recorder (`device/RecoveryPaths.kt`, `recovery_paths.json`) for
  kill, swipe-away, reboot, boot receiver, screen-off and microphone theft.
  Results are written through debug broadcasts so a kill test can record after
  the service process has died. Unexercised paths print **NOT RUN**, never PASS.
- Screen-off continuity test: user-driven; counts audio windows and gaps over a
  chosen duration, passes at ≥90 % coverage with zero gaps, and stores the
  result for the compatibility report.
- Generated `docs/compatibility_report_<model>.md` per handset: measured tier
  and timings only when measured, missing capabilities listed, recovery rows
  verbatim, and an explicit "what this report does not claim" section.
- Live spectrogram, per-class score bars, threat score, queue/back-end state.

### UI

Single-activity Jetpack Compose with bottom tabs **Status / Setup / Diagnose /
Settings / Tools**, plus Calibration. Layout adapts to the screen; nothing
assumes a fixed resolution.

The first-run wizard has seven steps with a live checklist: permissions, battery
optimisation (verified), OEM protections (user-confirmed, OEM guide inline),
screen-off continuity, backend/node identity, audio input + calibration, and
finish + benchmark. Verified items are checked by the OS; everything else is
marked "confirm manually". Settings commit live and cover node ID, backend,
token, thresholds, tier override, input, coordinates, SMS and the developer
mock/collection toggles. Configuration exports/imports as JSON (schema `1`,
counters/logs/device profile excluded, and the backend token is **never
exported**). The first launch shows a consent gate; Settings → Privacy and
retention shows token-encryption status, the retention policy and a local-audio
delete action.

---

## Module map

```
app/src/main/java/org/forestear/node/
  audio/        AudioCapture, AudioDeviceManager, MicProbe, Resampler, WavWriter,
                CollectionRecorder
  config/       AppConfig (asset-driven)
  decision/     DecisionConfig, DecisionEngine
  device/       DeviceProbe/DeviceProfile/DeviceProfileStore, TierSelector,
                LatencyBudget, OemProfiles, OemIntents, RecoveryPaths,
                CompatibilityReport, ClockSync
  diagnostics/  Benchmark, SelfTest, SoakTracker
  features/     Fft, FeatureAssets, LogMel, Handcrafted
  location/     LocationProvider (platform or fixed)
  model/        ModelRunner (TFLite), ModelZoo (9-model pipeline), FusionTrees
  power/        BatteryMonitor, ThermalGovernor, BatteryCsvLogger
  service/      NodeService, NodeState, NotificationHelper, ProtectionChecker,
                BootReceiver, ControlReceiver, WatchdogWorker, WatchdogAlarmReceiver
  settings/     SettingsStore
  transport/    Transport, OfflineQueue, StreamingClient, SignalMonitor,
                SmsSender, SmsDeliveryReceiver
  ui/           AppRoot, Theme, Components, Permissions, SettingControls, screens/
```

---

## adb control and helper scripts

See `android/scripts/`. All scripts accept `FORESTEAR_PACKAGE` (default
`org.forestear.node`); the broadcast scripts also accept
`FORESTEAR_ACTION_PREFIX` because actions are manifest constants, not derived
from the application id.

```bash
android/scripts/install.sh
android/scripts/grant_permissions.sh [--sms]
android/scripts/whitelist_battery.sh
android/scripts/self_test.sh
android/scripts/capture_logs.sh [seconds]
android/scripts/pull_collection.sh [--clear]
android/scripts/soak.sh --start|--status|--watch N|--stop|--report
android/scripts/recovery_test.sh status|kill|swipe|reboot|screen-off|record|report|clear
```

`recovery_test.sh` performs the action, observes whether `NodeService` is alive,
and records PASS / FAIL / NOT RUN through a debug broadcast. `am kill` being
refused is recorded as NOT RUN; `boot_receiver` on Android 14+ may legitimately
record FAIL.

Control actions (also usable from adb):

```bash
adb shell am broadcast -a org.forestear.node.START \
  -n org.forestear.node/.service.ControlReceiver
adb shell am broadcast -a org.forestear.node.STOP \
  -n org.forestear.node/.service.ControlReceiver
# SOAK_ON/OFF, COLLECT_ON/OFF, STREAM_ON/OFF, CONTINUITY_*, REPROBE likewise
```

Raw PCM receiver for the laptop:

```bash
python android/tools/stream_receiver.py --port 8765
# set streaming URL to ws://<laptop-lan-ip>:8765/stream
```

Docs generated from the OEM JSON (do not hand-edit):

```bash
python3 android/tools/generate_oem_docs.py
# -> docs/oem_guides.md, docs/supported_devices.md
```

---

## Honest limitations

- **Instrumented tests ran on one handset only.** The 9 CPH2381 checks passed,
  but the emulator matrix (Android 8–15) and other physical devices are NOT RUN.
  The **5.56 ms/hop** laptop figure is not a phone measurement; use the CPH2381
  benchmark (49.47 ms) for the phone.
- **Compatibility evidence exists only for CPH2381.**
  `../device_evidence/compatibility_report_CPH2381.md` is the canonical
  phone-generated report. Other rows in `docs/supported_devices.md` are OEM
  guidance, not measurements.
- **Recovery is mixed, not all PASS.** Force-kill, watchdog and reboot are FAIL:
  Android 14 blocks background microphone foreground-service restart, and the
  5-minute watchdog did not bring the service back after force-stop. Swipe-away
  and screen-off are PASS; microphone theft is PASS (10 s zero signal, DEGRADED,
  restart after 30 s). See `../../device_evidence/recovery_paths_CPH2381.json`.
- **Final 24 h soak is pending.** The 2026-11-17 run is invalid (power unplugged
  after 1 h 29 m, no final report). A fresh run started 2026-11-18T15:57:24Z.
- **Delivery and field accuracy are not verified.** SMS/Telegram real delivery,
  labelled field audio and real TDOA clock-offset correction remain open.
- The golden synthetic gunshot window produces M2 **0.906** but M9 gunshot
  **0.131** / benign **0.869**. This is out-of-distribution fusion behaviour on
  a synthetic impulse, not a bug in the port; it is reproduced identically by
  the Python pipeline and is documented rather than hidden.
- The phone's microphone frequency response is not characterised; built-in
  processing may differ from the training data when `UNPROCESSED` is missing.
  Resampling is linear interpolation, adequate for 64-band log-mel but not a
  studio-grade converter.
- The thermal governor uses battery temperature, not SoC junction temperature;
  it is conservative and may throttle earlier than the SoC requires.
- The M8 BirdNET model is fed time-tiled 16 kHz audio; its labels/indices are
  used for biodiversity only and are not part of the alert decision.
- SMS delivery depends on carrier, SIM credit and OEM power management; it is
  a fallback, not a guarantee.
- OEM auto-launch/background/Recents-lock switches have no public read API.
  The app records user confirmation, not verification.
- Android 14+ can require a manual app open after reboot; Android 15 forbids a
  boot receiver from starting a microphone FGS. This is a platform rule, and the
  app reports DEGRADED instead of pretending it recovered.
- Detection accuracy was measured on the Python evaluation set, not on any phone
  microphone.

## Docs

- [`docs/phone_setup_generic.md`](docs/phone_setup_generic.md) — setup for any
  Android 8–15 device.
- [`docs/oem_guides.md`](docs/oem_guides.md) — generated per-OEM steps.
- [`docs/supported_devices.md`](docs/supported_devices.md) — generated device
  matrix and qualification procedure.
- `forestear/docs/phone_setup_nord_ce2_lite.md` — the Nord CE 2 Lite as a worked
  example, not a separate code path.
- `forestear/docs/HARDWARE.md` — deployment guidance.
