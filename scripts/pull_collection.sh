#!/usr/bin/env bash
# Pull opt-in ambient collection chunks from the phone, then optionally clear
# them from the device.
#
# Collection is OFF by default and must be enabled in Settings (or with
# `adb shell am broadcast -a org.forestear.node.COLLECT_ON`). Chunks that
# contained detected human presence are deleted on-device before they are ever
# written, so they cannot appear here.
#
# Usage:
#   ./pull_collection.sh                # pull to ./collection/<timestamp>/
#   ./pull_collection.sh --clear        # pull, then delete from the device
#   ./pull_collection.sh --clear-only   # delete from the device, no pull
set -euo pipefail

PKG="${FORESTEAR_PACKAGE:-org.forestear.node}"
ACTIONS="${FORESTEAR_ACTION_PREFIX:-org.forestear.node}"
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
[[ -x "$ADB" ]] || ADB="$(command -v adb || true)"
[[ -n "$ADB" ]] || { echo "adb not found" >&2; exit 1; }

REMOTE="/sdcard/Android/data/$PKG/files/collection"
CLEAR=0
CLEAR_ONLY=0
for arg in "$@"; do
  case "$arg" in
    --clear) CLEAR=1 ;;
    --clear-only) CLEAR=1; CLEAR_ONLY=1 ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

list_remote() {
  "$ADB" shell ls -1 "$REMOTE" 2>/dev/null | tr -d '\r' || true
}

FILES="$(list_remote)"
COUNT="$(echo "$FILES" | grep -c . || true)"

if [[ "$CLEAR_ONLY" == "0" ]]; then
  STAMP="$(date +%Y%m%d-%H%M%S)"
  OUT="$PWD/collection/$STAMP"
  mkdir -p "$OUT"
  if [[ "${COUNT:-0}" -eq 0 ]]; then
    echo "No collection chunks on device ($REMOTE)."
    echo "Enable collection in Settings, or:"
    echo "  $ADB shell am broadcast -a $ACTIONS.COLLECT_ON -n $PKG/.service.ControlReceiver"
  else
    echo "Pulling $COUNT file(s) from $REMOTE ..."
    "$ADB" pull "$REMOTE/." "$OUT/" >/dev/null
    echo "Saved to $OUT"
    du -sh "$OUT" 2>/dev/null || true
    echo
    echo "Reminder: these are ambient recordings from an opted-in deployment."
    echo "Treat them as sensitive field data; they are not part of the app bundle."
  fi
fi

if [[ "$CLEAR" == "1" ]]; then
  if [[ "${COUNT:-0}" -eq 0 ]]; then
    echo "Nothing to clear."
  else
    # rm each file individually; some OEM file managers refuse directory rm.
    echo "$FILES" | while IFS= read -r f; do
      [[ -n "$f" ]] || continue
      "$ADB" shell rm -f "$REMOTE/$f" || true
    done
    echo "Cleared $COUNT chunk(s) from the device."
  fi
fi
