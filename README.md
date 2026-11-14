# ForestEar Node — Android app and device tooling

This directory is the phone side of ForestEar: the same 9-model ensemble that
runs on the laptop, ported to a single retired OnePlus Nord CE 2 Lite 5G.

| Path | What it is |
|---|---|
| `ForestEarNode/` | Kotlin/Jetpack Compose app (`org.forestear.node`); see its [README](ForestEarNode/README.md) for build, parity tests and limitations |
| `scripts/` | adb helpers: install, permissions, battery whitelist, log capture, hardware self-test, collection pull, soak run |
| `tools/` | `export_android_assets.py` (TFLite + M9 tree/isotonic assets) and `stream_receiver.py` (raw 16 kHz PCM WebSocket) |

Quick start (SDK at `~/Library/Android/sdk`, JDK 21):

```bash
cd ForestEarNode
JAVA_HOME=/Library/Java/JavaVirtualMachines/openjdk-21.jdk/Contents/Home \
  ./gradlew :app:testDebugUnitTest assembleDebug
../scripts/install.sh
```

The instrumented parity/latency tests are written but have **not** been run
here (no device/emulator attached). No on-phone latency, CPU or RAM figure is
claimed until they are.

The Python backend, simulator, trained models and evaluation live in
[`../forestear/`](../forestear/).
