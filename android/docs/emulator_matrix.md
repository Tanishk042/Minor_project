# Emulator compatibility matrix

`scripts/emulator_matrix.sh` runs the instrumented tests on several Android API
levels and writes `docs/emulator_matrix_results.md`. It is a convenience wrapper
around `connectedDebugAndroidTest`, not a substitute for real phones.

## Prerequisites

- Android SDK **emulator** package and at least one system image
  (`system-images;android-<api>;google_apis;arm64-v8a`, or `x86_64` on Intel).
- `cmdline-tools` (for `avdmanager` and `sdkmanager`) and `platform-tools`.
- No other device/emulator attached; the script refuses to run if one is.

Install images from Android Studio's SDK Manager, or:

```bash
./scripts/emulator_matrix.sh --install
```

`--install` downloads images for the selected APIs. It accepts the SDK licences
on your behalf only for those packages; review them first if that matters.

## Run

```bash
cd android
./scripts/emulator_matrix.sh                 # APIs 26 29 33 34, DAL test only
APIS="30 34" ./scripts/emulator_matrix.sh    # chosen APIs
./scripts/emulator_matrix.sh --full          # all instrumented tests
```

For each API the script:

1. verifies the system image, creates a headless AVD if needed;
2. boots it with `-no-window -no-audio -gpu swiftshader_indirect`;
3. waits up to 6 minutes for `sys.boot_completed=1`;
4. runs the instrumented tests and counts `FATAL EXCEPTION` lines;
5. kills the emulator and appends a PASS/FAIL/NOT RUN row.

Exit codes: `0` all APIs ran and passed, `1` at least one failed, `2` nothing
ran (missing tooling/images or no boot). A row says **NOT RUN** when the check
did not happen — it is never reported as a pass.

## Status in this workspace (2026-11-16)

**NOT RUN.** The SDK here has `platform-tools`, `build-tools;34.0.0` and
`platforms;android-34`, but **no emulator package and no system images**. Running
the script prints:

```
NOT RUN: missing Android tooling: emulator
exit=2
```

The script itself was syntax-checked (`bash -n`). No emulator was booted, no
instrumented test was executed, and no matrix row exists yet.

## Why the matrix matters

The app targets minSdk 26 (Android 8) through 34 (Android 14). Emulator runs
catch API-level API misuse (permissions, foreground-service rules, Keystore
behaviour, audio APIs). They do **not** prove:

- real microphone behaviour or input auto-detection;
- thermal throttling, battery drain or 24 h stability;
- OEM background-kill behaviour (see `docs/oem_guides.md`);
- GPU delegate stability (emulator GPU is software).

Those still require physical phones. Record emulator results as
"emulator matrix" and keep them separate from device measurements.
