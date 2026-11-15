# ForestEar Node — Android app and device tooling

This directory is the phone side of ForestEar: the same 9-model ensemble that
runs on the laptop, ported to Android. It runs on **any Android 8.0+ (API 26)
device with a microphone**, with no Play Services, Firebase or network
dependency for inference. The OnePlus Nord CE 2 Lite 5G is one entry in the OEM
profile table, not a separate build.

A **Device Adaptation Layer** (DAL) probes the handset, benchmarks the bundled
models, selects a performance tier, and degrades honestly when a capability is
missing. Capability facts come from the OS; anything unavailable is recorded as
missing rather than guessed.

| Path | What it is |
|---|---|
| `ForestEarNode/` | Kotlin/Jetpack Compose app (`org.forestear.node`); see its [README](ForestEarNode/README.md) for build, parity tests and limitations |
| `ForestEarNode/docs/` | [`phone_setup_generic.md`](ForestEarNode/docs/phone_setup_generic.md), generated [`oem_guides.md`](ForestEarNode/docs/oem_guides.md) and [`supported_devices.md`](ForestEarNode/docs/supported_devices.md) |
| `scripts/` | adb helpers: install, permissions, battery whitelist, log capture, hardware self-test, collection pull, soak run, recovery-path test |
| `tools/` | `export_android_assets.py` (TFLite + M9 tree/isotonic assets), `stream_receiver.py` (raw 16 kHz PCM WebSocket), `generate_oem_docs.py` |

Quick start (SDK at `~/Library/Android/sdk`, JDK 21):

```bash
cd ForestEarNode
JAVA_HOME=/Library/Java/JavaVirtualMachines/openjdk-21.jdk/Contents/Home \
  ./gradlew :app:testDebugUnitTest assembleDebug
../scripts/install.sh
```

Then open the app once and complete the seven-step Setup wizard. Read
[`ForestEarNode/docs/phone_setup_generic.md`](ForestEarNode/docs/phone_setup_generic.md)
before deploying.

All adb scripts accept `FORESTEAR_PACKAGE` (default `org.forestear.node`).
Broadcast-driven scripts also accept `FORESTEAR_ACTION_PREFIX`, because the
actions are manifest constants rather than values derived from the application
id:

```bash
FORESTEAR_PACKAGE=com.example.forestear \
FORESTEAR_ACTION_PREFIX=org.forestear.node \
  ./scripts/install.sh
```

## Measured vs unmeasured

Measured on this checkout:

- JVM tests: **79 tests across 12 suites, 0 failures**.
- Kotlin vs Python parity: log-mel max abs diff **1.01e-6**, handcrafted
  **2.88e-8**, M9 trees **1.11e-16**.
- Debug APK builds; models are packaged uncompressed.

Not measured here (no device or emulator attached):

- on-phone latency, CPU, RAM, thermal behaviour, SMS delivery;
- the instrumented `connectedDebugAndroidTest` suites;
- recovery paths (kill, swipe, reboot, screen-off, microphone theft);
- 24 h soak and the generated `compatibility_report_<model>.md`.

No number is claimed until its tool has actually run on a handset. The recovery
script records unexercised paths as **NOT RUN**, never PASS.

## Generated docs

`ForestEarNode/app/src/main/assets/config/oem_profiles.json` is the runtime
source of truth for the OEM table. The Markdown guides are generated:

```bash
python3 tools/generate_oem_docs.py
```

Do not hand-edit `oem_guides.md` or `supported_devices.md`; edit the JSON and
regenerate.

The Python backend, simulator, trained models and evaluation live in
[`../forestear/`](../forestear/).
