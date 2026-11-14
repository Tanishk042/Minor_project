# ForestEar Node — Android edge agent

Fully on-phone acoustic node for the ForestEar project. It runs the same
9-model ensemble as `forestear/edge_agent`, makes decisions locally, and sends
only JSON alerts/telemetry (plus optional 5 s non-speech clips on the Python
agent). Built for one retired **OnePlus Nord CE 2 Lite 5G**, but it runs on any
Android 8.0+ device with a microphone.

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

11 tests, all passing on this checkout:

| Test | What it locks down | Measured result |
|---|---|---|
| `LogMelParityTest` | Kotlin log-mel vs Python golden, all 6 windows | max abs diff **1.01e-6** |
| `HandcraftedParityTest` | rms/crest/flux/flatness/onset/ZCR vs Python | max abs diff **2.88e-8** |
| `FusionTreesParityTest` | binary M9 trees + isotonic vs Python `predict_proba` | max abs diff **1.11e-16** |
| `DecisionEngineTest` | golden decision sequences, rain veto, no over-veto | 3 tests pass |
| `ConfigTest` | asset contract (sample rate, shapes, 9 models, 47 features) | 5 tests pass |

### Instrumented (needs a device/emulator)

```bash
./gradlew :app:connectedDebugAndroidTest
```

- `PipelineParityTest` runs all nine models on the golden windows and compares
  scores with the Python vectors.
- `LatencyTest` warms up and times 10 full-pipeline windows, enforcing a
  1500 ms sanity budget.

**These instrumented tests are written but have not been run in this
repository.** No on-phone latency, CPU or RAM number is claimed until you run
them and read the output. The laptop-side Python pipeline measured
**5.56 ms/hop**; the phone is expected to be slower.

---

## What is implemented

### Audio

- `AudioRecord` at 16 kHz mono 16-bit; prefers `UNPROCESSED`, falls back to
  `MIC`; disables AGC/NS/AEC when available and logs availability.
- 1.0 s window / 0.5 s hop, silence gate, clipping detection.
- Automatic capture restart on read errors; restart and dropped-frame counters.
- Built-in vs wired (USB-C → TRRS) input selection with per-input gain.
- Calibration screen: live dBFS, peak, clipping, source/effects, gain.

### Models and features

- Kotlin log-mel 64×98, handcrafted descriptors and FFT with Python parity.
- TFLite runners handle float32 and int8 quantisation exactly like
  `zoo.py`; XNNPACK by default, GPU delegate selectable per model.
- M9 fusion runs as a compact little-endian binary tree ensemble
  (`config/m9_trees.bin`, 1500 trees) plus isotonic calibration.
- M8 BirdNET duty-cycled (1/10 by default) and excluded from alerts.
- Adaptive hop: lengthened when a window exceeds the analysis budget, stepped
  back down after sustained fast windows.

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

### Reliability

- Foreground service with microphone/dataSync types (Android 14 rules even on
  Android 13), partial wake lock, boot receiver.
- WorkManager (15 min) + AlarmManager (5 min) watchdog; `onTaskRemoved`
  restart; explicit Stop disables auto-start and cancels watchdogs.
- Persisted uptime and restart count in heartbeats (first launch excluded).
- Battery exemption prompt using the proper intent plus verification;
  persistent warning while protections are missing.
- Thermal governor with hysteresis and low-battery modes; system events and
  notifications on CRITICAL/PAUSED and low/critical battery.
- Battery CSV once a minute; heartbeats include charging, current, temperature,
  thermal status, signal dBm and network type.

### Diagnostics

- Hardware self-test: mic/clipping, headset, permissions, protections, network,
  backend, models, SMS, thermal — PASS/WARN/FAIL.
- Per-model CPU vs GPU benchmark with mean/p95 latency, CPU %, RAM; writes
  `benchmark.json` and persists the chosen delegate.
- 24 h soak mode with report (`soak_report.json`, `soak_samples.csv`).
- Live spectrogram, per-class score bars, threat score, queue/back-end state.

### UI

Single-activity Jetpack Compose with bottom tabs **Status / Setup / Diagnose /
Settings / Tools**, plus Calibration. The Setup wizard is OxygenOS-aware and
links to the OEM screens adb cannot change.

---

## Module map

```
app/src/main/java/org/forestear/node/
  audio/        AudioCapture, AudioDeviceManager, MicProbe, WavWriter, CollectionRecorder
  config/       AppConfig (asset-driven)
  decision/     DecisionConfig, DecisionEngine
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

See `android/scripts/`:

```bash
android/scripts/install.sh
android/scripts/grant_permissions.sh [--sms]
android/scripts/whitelist_battery.sh
android/scripts/self_test.sh
android/scripts/capture_logs.sh [seconds]
android/scripts/pull_collection.sh [--clear]
android/scripts/soak.sh --start|--status|--watch N|--stop|--report
```

Control actions (also usable from adb):

```bash
adb shell am broadcast -a org.forestear.node.START \
  -n org.forestear.node/.service.ControlReceiver
adb shell am broadcast -a org.forestear.node.STOP \
  -n org.forestear.node/.service.ControlReceiver
# SOAK_ON/OFF, COLLECT_ON/OFF, STREAM_ON/OFF likewise
```

Raw PCM receiver for the laptop:

```bash
python android/tools/stream_receiver.py --port 8765
# set streaming URL to ws://<laptop-lan-ip>:8765/stream
```

---

## Honest limitations

- **Instrumented parity/latency tests have not been run here.** JVM parity is
  measured; on-phone model behaviour is not yet measured in this repository.
- The golden synthetic gunshot window produces M2 **0.906** but M9 gunshot
  **0.131** / benign **0.869**. This is out-of-distribution fusion behaviour on
  a synthetic impulse, not a bug in the port; it is reproduced identically by
  the Python pipeline and is documented rather than hidden.
- The phone's microphone frequency response is not characterised; built-in
  processing may differ from the training data when `UNPROCESSED` is missing.
- The thermal governor uses battery temperature, not SoC junction temperature;
  it is conservative and may throttle earlier than the SoC requires.
- The M8 BirdNET model is fed time-tiled 16 kHz audio; its labels/indices are
  used for biodiversity only and are not part of the alert decision.
- SMS delivery depends on carrier, SIM credit and OEM power management; it is
  a fallback, not a guarantee.
- OxygenOS auto-launch/background controls cannot be set by adb; the operator
  must confirm them in the wizard.

See `forestear/docs/phone_setup_nord_ce2_lite.md` and
`forestear/docs/HARDWARE.md` for deployment guidance.
