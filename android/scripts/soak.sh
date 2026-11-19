#!/usr/bin/env bash
# Drive a 24-hour soak test from the command line.
#
# The service must be installed and running. Soak state survives service
# restarts (it lives in SharedPreferences), so a watchdog restart does not
# invalidate the run.
#
# Usage:
#   ./soak.sh --start              # start the soak
#   ./soak.sh --status             # elapsed time / battery / restarts
#   ./soak.sh --watch 600          # print status every 60 s for 600 s
#   ./soak.sh --stop               # stop and pull the report
#   ./soak.sh --report             # pull the last report only
set -euo pipefail

PKG="${FORESTEAR_PACKAGE:-org.forestear.node}"
# Broadcast actions are part of the app's manifest, not its application id. A
# fork that changes only the package should also set this prefix.
ACTIONS="${FORESTEAR_ACTION_PREFIX:-org.forestear.node}"
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
[[ -x "$ADB" ]] || ADB="$(command -v adb || true)"
[[ -n "$ADB" ]] || { echo "adb not found" >&2; exit 1; }
REMOTE="/sdcard/Android/data/$PKG/files/logs"
RECEIVER="$PKG/.service.ControlReceiver"

broadcast() {
  "$ADB" shell am broadcast -a "$1" -n "$RECEIVER" >/dev/null
  echo "sent $1"
}

status() {
  local battery thermal svc
  battery="$("$ADB" shell dumpsys battery | tr -d '\r' | awk -F': ' '/level|temperature|status/ {printf "%s=%s ", $1, $2}')"
  thermal="$("$ADB" shell dumpsys thermalservice | tr -d '\r' | grep -m1 'Thermal Status' | sed 's/^ *//')"
  svc="$("$ADB" shell dumpsys activity services "$PKG" | tr -d '\r' | grep -c NodeService || true)"
  printf '%s  %s  NodeService=%s\n' "$(date -u +%H:%M:%SZ)" "$battery" "${svc:-0}"
  [[ -n "$thermal" ]] && echo "  $thermal"
  # The service writes the soak CSV once a minute; last line is the newest sample.
  "$ADB" shell tail -n 1 "$REMOTE/soak_samples.csv" 2>/dev/null | tr -d '\r' | sed 's/^/  last sample: /' || true
}

pull_report() {
  STAMP="$(date +%Y%m%d-%H%M%S)"
  OUT="$PWD/soak/$STAMP"
  mkdir -p "$OUT"
  local got=0
  for f in soak_report.json soak_samples.csv battery_log.csv benchmark.json; do
    if "$ADB" pull "$REMOTE/$f" "$OUT/$f" >/dev/null 2>&1; then
      echo "pulled $f"
      got=1
    fi
  done
  [[ "$got" == "1" ]] && echo "Reports in $OUT" || echo "No reports found at $REMOTE"
}

case "${1:-}" in
  --start)
    broadcast "$ACTIONS.SOAK_ON"
    echo "Soak running. Check in with: $0 --status"
    ;;
  --status)
    status
    ;;
  --watch)
    SECONDS_TO_WATCH="${2:-600}"
    END=$((SECONDS + SECONDS_TO_WATCH))
    while [[ "$SECONDS" -lt "$END" ]]; do
      status
      sleep 60
    done
    ;;
  --stop)
    broadcast "$ACTIONS.SOAK_OFF"
    sleep 2
    pull_report
    ;;
  --report)
    pull_report
    ;;
  -h|--help)
    sed -n '2,14p' "$0"
    ;;
  *)
    echo "usage: $0 --start | --status | --watch <seconds> | --stop | --report" >&2
    exit 2
    ;;
esac
