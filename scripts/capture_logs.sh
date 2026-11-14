#!/usr/bin/env bash
# Capture ForestEar Node logs and on-device reports to ./captures/<timestamp>/.
#
# Usage:
#   ./capture_logs.sh                 # 30 s live logcat + pull reports
#   ./capture_logs.sh 120             # 120 s live logcat
#   ./capture_logs.sh --dump-only     # skip live capture, just pull files
set -euo pipefail

PKG="org.forestear.node"
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
[[ -x "$ADB" ]] || ADB="$(command -v adb || true)"
[[ -n "$ADB" ]] || { echo "adb not found" >&2; exit 1; }

DURATION=30
DUMP_ONLY=0
for arg in "$@"; do
  case "$arg" in
    --dump-only) DUMP_ONLY=1 ;;
    ''|*[!0-9]*) echo "unknown argument: $arg" >&2; exit 2 ;;
    *) DURATION="$arg" ;;
  esac
done

STAMP="$(date +%Y%m%d-%H%M%S)"
OUT="$PWD/captures/$STAMP"
mkdir -p "$OUT"

if [[ "$DUMP_ONLY" == "0" ]]; then
  echo "== logcat (${DURATION}s, tag ForestEar + crashes) =="
  "$ADB" logcat -c || true
  # Bounded capture: no `timeout` on stock macOS, so background + kill.
  "$ADB" logcat -v time ForestEar:V AndroidRuntime:E '*:S' > "$OUT/logcat.txt" &
  LOG_PID=$!
  sleep "$DURATION"
  kill "$LOG_PID" 2>/dev/null || true
  wait "$LOG_PID" 2>/dev/null || true
  echo "wrote $OUT/logcat.txt ($(wc -l < "$OUT/logcat.txt" | tr -d ' ') lines)"
fi

echo "== device info =="
{
  echo "date: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  "$ADB" shell getprop ro.product.model
  "$ADB" shell getprop ro.build.version.release
  "$ADB" shell getprop ro.build.version.sdk
  "$ADB" shell getprop ro.product.cpu.abi
} | tr -d '\r' > "$OUT/device.txt"

echo "== battery / thermal =="
"$ADB" shell dumpsys battery | tr -d '\r' > "$OUT/battery.txt"
"$ADB" shell dumpsys thermalservice | tr -d '\r' > "$OUT/thermal.txt" || true

echo "== package =="
"$ADB" shell dumpsys package "$PKG" | tr -d '\r' > "$OUT/package.txt" || true

REMOTE="/sdcard/Android/data/$PKG/files"
echo "== on-device reports ($REMOTE) =="
for f in logs/battery_log.csv logs/benchmark.json logs/soak_report.json logs/soak_samples.csv; do
  if "$ADB" pull "$REMOTE/$f" "$OUT/$(basename "$f")" >/dev/null 2>&1; then
    echo "pulled $f"
  fi
done

echo
echo "Capture directory: $OUT"
