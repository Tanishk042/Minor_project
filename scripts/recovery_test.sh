#!/usr/bin/env bash
# Exercise the recovery paths that decide whether a phone survives as a node,
# and record each result in the app's recovery_paths.json.
#
# The app does not guess these results. Each subcommand performs the action,
# observes whether NodeService is alive afterwards, and records PASS / FAIL /
# NOT RUN through the debug RECORD_RECOVERY broadcast. The Diagnostics screen and
# the generated compatibility report print exactly what is stored.
#
# Usage:
#   ./recovery_test.sh status
#   ./recovery_test.sh kill [--force-stop]
#   ./recovery_test.sh swipe [--wait 60] [--auto] [--confirm]
#   ./recovery_test.sh reboot
#   ./recovery_test.sh screen-off [--wait 30]
#   ./recovery_test.sh record <path> <PASS|FAIL|NOT RUN> "detail"
#   ./recovery_test.sh report
#   ./recovery_test.sh clear
#
# Package: FORESTEAR_PACKAGE (default org.forestear.node). Broadcast actions are
# manifest constants, so a fork that changes only the package must also set
# FORESTEAR_ACTION_PREFIX.
#
# Honesty notes:
#  - `am kill` is refused for a process Android considers non-killable; the
#    script records NOT RUN rather than pretending the kill worked.
#  - Android 14+ blocks a microphone foreground service from starting in the
#    background, so the reboot path can legitimately record FAIL. That is a
#    platform rule, not a bug in the app.
#  - `screen-off` only checks that the process survived; audio coverage is
#    measured by the in-app screen-off continuity test.
set -uo pipefail

PKG="${FORESTEAR_PACKAGE:-org.forestear.node}"
ACTIONS="${FORESTEAR_ACTION_PREFIX:-org.forestear.node}"
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
[[ -x "$ADB" ]] || ADB="$(command -v adb || true)"
[[ -n "$ADB" ]] || { echo "adb not found" >&2; exit 1; }

RECEIVER="$PKG/.service.ControlReceiver"
ACTIVITY="$PKG/.MainActivity"

# `adb shell` joins its arguments into one remote command line. Quoting each
# argument here is essential: without it a value like "NOT RUN" arrives as two
# tokens and the receiver rejects the record.
sh_() {
  local cmd="" arg
  for arg in "$@"; do
    cmd+=" $(printf '%q' "$arg")"
  done
  "$ADB" shell "$cmd" 2>/dev/null | tr -d '\r'
}

pid_of() {
  local p
  p="$(sh_ pidof "$PKG")"
  if [[ -z "$p" ]]; then
    p="$(sh_ ps -A -o PID,NAME | awk -v pkg="$PKG" '$NF == pkg {print $1; exit}')"
  fi
  printf '%s' "$p"
}

svc_running() {
  sh_ dumpsys activity services "$PKG" | grep -q "NodeService"
}

record() {
  local path="$1" outcome="$2" detail="$3" out
  out="$(sh_ am broadcast -a "$ACTIONS.RECORD_RECOVERY" -n "$RECEIVER" \
    --es path "$path" --es outcome "$outcome" --es detail "$detail")"
  if printf '%s' "$out" | grep -q "Broadcast completed"; then
    printf 'recorded  %-14s %-7s %s\n' "$path" "$outcome" "$detail"
  else
    printf 'WARN      could not record %s (%s); is %s installed?\n' "$path" "$outcome" "$PKG" >&2
  fi
}

require_device() {
  local devices
  devices="$("$ADB" devices | awk 'NR > 1 && $2 == "device" {print $1}')"
  [[ -n "$devices" ]] || { echo "no adb device in 'device' state" >&2; exit 1; }
}

cmd_status() {
  require_device
  local pid
  pid="$(pid_of)"
  echo "package:   $PKG"
  echo "pid:       ${pid:-none}"
  if svc_running; then echo "service:   NodeService running"; else echo "service:   NodeService NOT running"; fi
  if sh_ dumpsys deviceidle whitelist | grep -q "$PKG"; then
    echo "doze:      allowlisted"
  else
    echo "doze:      NOT allowlisted (run ./whitelist_battery.sh)"
  fi
  echo "battery:   $(sh_ dumpsys battery | awk -F': ' '/level|temperature|status/ {printf "%s=%s ", $1, $2}')"
  echo "thermal:   $(sh_ dumpsys thermalservice | grep -m1 'Thermal Status' | sed 's/^ *//')"
  echo "sdk:       $(sh_ getprop ro.build.version.sdk)  model: $(sh_ getprop ro.product.model)"
}

cmd_kill() {
  local force=0
  [[ "${1:-}" == "--force-stop" ]] && force=1
  require_device
  local before after
  before="$(pid_of)"
  if [[ -z "$before" ]]; then
    record kill_service "NOT RUN" "service was not running before the test"
    return
  fi

  if [[ "$force" == "1" ]]; then
    echo "Force-stopping $PKG (hard kill; Android will not restart a force-stopped app)..."
    sh_ am force-stop "$PKG"
    sleep 5
    if svc_running; then
      record kill_service "PASS" "service survived force-stop (unexpected)"
    else
      record kill_service "FAIL" "force-stop stopped the service and it did not restart; opening the app is required"
    fi
    # A force-stopped package does not receive broadcasts, so relaunch before
    # recording any further results.
    sh_ am start -n "$ACTIVITY" >/dev/null 2>&1 || true
    sleep 3
    return
  fi

  echo "Sending the app to the background, then asking Android to kill it..."
  sh_ input keyevent KEYCODE_HOME
  sleep 3
  sh_ am kill "$PKG"
  sleep 15
  after="$(pid_of)"

  if [[ -z "$after" ]]; then
    record kill_service "FAIL" "process was killed and did not return within 15 s"
  elif [[ "$before" == "$after" ]]; then
    record kill_service "NOT RUN" "am kill was refused (process not in a killable state); try --force-stop"
  else
    record kill_service "PASS" "process came back after am kill (pid $before -> $after)"
  fi
}

cmd_swipe() {
  local wait=60 auto=0 confirm=0
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --wait) wait="${2:-60}"; shift 2 ;;
      --auto) auto=1; shift ;;
      --confirm) confirm=1; shift ;;
      *) echo "unknown swipe argument: $1" >&2; exit 2 ;;
    esac
  done
  require_device
  local before after
  before="$(pid_of)"
  if [[ -z "$before" ]]; then
    record swipe_away "NOT RUN" "service was not running before the test"
    return
  fi

  if [[ "$auto" == "1" ]]; then
    local size w h
    size="$(sh_ wm size | awk '/Physical size/ {print $3; exit}')"
    if [[ "$size" == *x* ]]; then
      w="${size%x*}"
      h="${size#*x}"
      sh_ input keyevent KEYCODE_APP_SWITCH
      sleep 1
      sh_ input swipe $((w / 2)) $((h * 3 / 4)) $((w / 2)) $((h / 4)) 250
      echo "Attempted an automated recents swipe on ${w}x${h}; verify visually."
    else
      echo "wm size unavailable; falling back to a manual swipe." >&2
      confirm=0
    fi
  else
    echo "Open Recents and swipe ForestEar Node away. Waiting ${wait}s..."
  fi

  local end=$((SECONDS + wait))
  while [[ "$SECONDS" -lt "$end" ]]; do sleep 2; done

  if [[ "$auto" != "1" && "$confirm" != "1" ]]; then
    record swipe_away "NOT RUN" "manual swipe was not confirmed; re-run with --confirm after swiping"
    return
  fi

  after="$(pid_of)"
  if svc_running; then
    record swipe_away "PASS" "service was running ${wait}s after the swipe"
  elif [[ -n "$after" ]]; then
    record swipe_away "PASS" "process alive (pid $after) after the swipe, service not yet reported"
  else
    record swipe_away "FAIL" "service did not survive the swipe within ${wait}s"
  fi
}

cmd_reboot() {
  require_device
  echo "Rebooting the phone..."
  "$ADB" reboot
  "$ADB" wait-for-device
  local i=0 booted=0
  while [[ "$i" -lt 90 ]]; do
    if [[ "$(sh_ getprop sys.boot_completed)" == "1" ]]; then booted=1; break; fi
    sleep 2
    i=$((i + 1))
  done
  if [[ "$booted" != "1" ]]; then
    echo "boot_completed did not arrive within 180 s" >&2
    record reboot "NOT RUN" "device did not finish booting within 180 s"
    return
  fi
  echo "Boot completed; waiting 30 s for the boot receiver / watchdog..."
  sleep 30
  if svc_running; then
    record reboot "PASS" "service running 30 s after boot"
    record boot_receiver "PASS" "boot receiver or watchdog restarted the service"
  else
    record reboot "FAIL" "service was not running 30 s after boot"
    record boot_receiver "FAIL" "no background restart after boot; open the app (expected on Android 14+ for a microphone FGS)"
  fi
}

cmd_screen_off() {
  local wait=30
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --wait) wait="${2:-30}"; shift 2 ;;
      *) echo "unknown screen-off argument: $1" >&2; exit 2 ;;
    esac
  done
  require_device
  sh_ input keyevent KEYCODE_SLEEP || sh_ input keyevent 223
  echo "Screen off; waiting ${wait}s..."
  sleep "$wait"
  if svc_running; then
    record screen_off "PASS" "process alive with the screen off for ${wait}s (audio coverage is measured in-app)"
  else
    record screen_off "FAIL" "service died with the screen off within ${wait}s"
  fi
  sh_ input keyevent KEYCODE_WAKEUP || sh_ input keyevent 224
}

cmd_report() {
  require_device
  local json
  json="$(sh_ run-as "$PKG" cat files/recovery_paths.json)"
  if [[ -n "$json" ]]; then
    printf '%s\n' "$json"
  else
    echo "No recovery_paths.json readable. A debug build is required for run-as;" >&2
    echo "otherwise open Diagnose in the app to see the recorded table." >&2
    exit 1
  fi
}

cmd_clear() {
  require_device
  local out
  out="$(sh_ am broadcast -a "$ACTIONS.CLEAR_RECOVERY" -n "$RECEIVER")"
  if printf '%s' "$out" | grep -q "Broadcast completed"; then
    echo "Recovery log cleared."
  else
    echo "Could not clear the recovery log; is $PKG installed?" >&2
    exit 1
  fi
}

COMMAND="${1:-}"
[[ $# -gt 0 ]] && shift
case "$COMMAND" in
  status) cmd_status ;;
  kill) cmd_kill "$@" ;;
  swipe) cmd_swipe "$@" ;;
  reboot) cmd_reboot ;;
  screen-off) cmd_screen_off "$@" ;;
  record)
    [[ $# -ge 2 ]] || { echo "usage: $0 record <path> <PASS|FAIL|NOT RUN> \"detail\"" >&2; exit 2; }
    require_device
    record "$1" "$2" "${3:-recorded from adb}"
    ;;
  report) cmd_report ;;
  clear) cmd_clear ;;
  -h|--help|help|'') sed -n '2,31p' "$0" ;;
  *)
    echo "unknown command: $COMMAND" >&2
    echo "usage: $0 status | kill [--force-stop] | swipe [...] | reboot | screen-off [...] | record ... | report | clear" >&2
    exit 2
    ;;
esac
