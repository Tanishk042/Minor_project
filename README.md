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
| `docs/` | [`release_build.md`](docs/release_build.md) (signing + ABI splits), [`privacy_and_token.md`](docs/privacy_and_token.md), [`emulator_matrix.md`](docs/emulator_matrix.md) |
| `scripts/` | adb helpers: install, permissions, battery whitelist, log capture, hardware self-test, collection pull, soak run, recovery-path test, release build, emulator matrix |
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

Measured on this checkout / on the CPH2381 handset:

- JVM tests: **88 tests across 13 suites, 0 failures** (includes 8 token-storage
  tests).
- Instrumented tests: **9 tests across 4 suites on OnePlus CPH2381 (Android 14),
  0 failures, 0 errors**. Raw XML:
  `ForestEarNode/app/build/outputs/androidTest-results/connected/debug/TEST-CPH2381 - 14-_app-.xml`.
- Kotlin vs Python parity: log-mel max abs diff **1.01e-6**, handcrafted
  **2.88e-8**, M9 trees **1.11e-16**.
- Debug APK builds: **100,471,495 bytes**; models are packaged uncompressed.
- On-phone benchmark: **49.47 ms mean** full pipeline, tier A, CPU/XNNPACK only,
  **160.87% CPU**, **321.09 MB RAM**; GPU delegate unavailable.
- In-app self-test: **14 checks, 0 failed, 6 warnings**; headless self-test:
  **0 failures, 2 warnings**.
- Recovery: swipe **PASS**, screen-off **PASS** (100% coverage over 303 s),
  microphone theft **PASS** (10 s zero signal, DEGRADED, 30 s restart),
  force-kill **FAIL**, watchdog **FAIL** (no restart after 420 s), reboot
  **FAIL**, boot receiver **FAIL**.
- Compatibility report generated on the phone and pulled into
  `device_evidence/compatibility_report_CPH2381.md`.

Not measured / not verified here:

- Signed release APK (no keystore supplied); emulator API matrix (no emulator or
  system images installed).
- Real Telegram and SMS delivery; labelled real field audio; real TDOA
  clock-offset correction. (`SEND_SMS` was granted by the final `-g` install,
  but no live SIM/carrier test ran.)
- Final 24 h soak report. The 2026-11-17 run is invalid (power unplugged after
  1 h 29 m); a fresh run started 2026-11-18T15:57:24Z and is still in progress.
- SoC junction temperature (not exposed) and per-device microphone response.

No number is claimed until its tool actually ran. The recovery script records
unexercised paths as **NOT RUN**, never PASS. The full list with blockers and
commands is in [`docs/verification_status.md`](docs/verification_status.md).

## Hardening and release

- **CI**: [`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs JVM tests,
  compiles the instrumented tests, and builds the debug APK. Not yet executed on
  GitHub.
- **Release signing**: copy `keystore.properties.example` to
  `keystore.properties`, then `./scripts/build_release.sh`. ABI splits are
  opt-in (`-PabiSplits`). Details and the `apksigner verify` step:
  [`docs/release_build.md`](docs/release_build.md).
- **Privacy**: first-launch consent gate, privacy/retention screen, local-audio
  delete, and Android Keystore encryption for the backend token. The token is
  excluded from settings exports. Details and honest fallback behaviour:
  [`docs/privacy_and_token.md`](docs/privacy_and_token.md).
- **Emulator matrix**: `./scripts/emulator_matrix.sh` (NOT RUN here).
- **Backend security/deployment**: see `../forestear/docs/security.md` for
  CARTO key rotation, the TLS/auth checklist and the `make security-check`
  preflight.
- **License**: MIT for the source; bundled models and datasets keep their own
  terms. See [`LICENSE`](LICENSE) and [`NOTICE.md`](NOTICE.md).

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
