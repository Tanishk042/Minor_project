#!/usr/bin/env bash
# Run the instrumented tests across an Android API matrix.
#
# Requires: Android SDK emulator, cmdline-tools (avdmanager, sdkmanager),
# platform-tools (adb), and downloaded system images. This workspace has the
# SDK but NO emulator or system images, so this script has NOT been run here.
#
# Usage:
#   ./scripts/emulator_matrix.sh                 # APIs 26 29 33 34, DAL test
#   APIS="30 34" ./scripts/emulator_matrix.sh
#   ./scripts/emulator_matrix.sh --full          # all instrumented tests
#   ./scripts/emulator_matrix.sh --install       # download missing images first
#
# Output: docs/emulator_matrix_results.md (overwritten per run) and exit code 0
# only if every API actually ran and passed. Missing tooling = NOT RUN (exit 2).
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE_DIR="$REPO_ROOT/ForestEarNode"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
APIS="${APIS:-26 29 33 34}"
FULL=0
INSTALL=0
OUT="$REPO_ROOT/docs/emulator_matrix_results.md"
ABI="arm64-v8a"
[[ "$(uname -m)" == "x86_64" ]] && ABI="x86_64"

for arg in "$@"; do
  case "$arg" in
    --full) FULL=1 ;;
    --install) INSTALL=1 ;;
    -h|--help) sed -n '2,15p' "$0"; exit 0 ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

ADB="$SDK/platform-tools/adb"
EMULATOR="$SDK/emulator/emulator"
AVDMANAGER="$(find "$SDK/cmdline-tools" -name avdmanager -type f 2>/dev/null | head -1)"
SDKMANAGER="$(find "$SDK/cmdline-tools" -name sdkmanager -type f 2>/dev/null | head -1)"

missing=()
[[ -x "$ADB" ]] || missing+=("adb")
[[ -x "$EMULATOR" ]] || missing+=("emulator")
[[ -n "$AVDMANAGER" ]] || missing+=("avdmanager")
[[ -n "$SDKMANAGER" ]] || missing+=("sdkmanager")
if (( ${#missing[@]} )); then
  echo "NOT RUN: missing Android tooling: ${missing[*]}" >&2
  echo "Install the emulator + cmdline-tools via Android Studio's SDK Manager." >&2
  exit 2
fi

# Refuse to fight an already-running emulator/device.
if "$ADB" devices | awk 'NR>1 && $2=="device" {found=1} END{exit !found}'; then
  echo "NOT RUN: a device/emulator is already attached. Stop it first." >&2
  exit 2
fi

declare -a RESULTS
overall=0
ran=0

write_report() {
  {
    echo "# Emulator matrix results"
    echo
    echo "Generated: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "Host: $(uname -sm) · ABI: $ABI · mode: $([[ $FULL -eq 1 ]] && echo full || echo DeviceAdaptationTest)"
    echo
    echo "| API | Device | Result | Notes |"
    echo "|---|---|---|---|"
    for row in "${RESULTS[@]}"; do echo "| $row |"; done
    echo
    echo "A row is only PASS when the emulator booted and the test task exited 0."
    echo "NOT RUN rows mean the check did not happen."
  } > "$OUT"
}

for api in $APIS; do
  image="system-images;android-$api;google_apis;$ABI"
  if ! "$SDKMANAGER" --list_installed 2>/dev/null | grep -q "$image"; then
    if [[ $INSTALL -eq 1 ]]; then
      echo "Installing $image ..."
      yes | "$SDKMANAGER" "$image" >/dev/null || {
        RESULTS+=("$api | — | NOT RUN | image install failed")
        overall=1; continue
      }
    else
      RESULTS+=("$api | — | NOT RUN | image not installed (use --install)")
      overall=1; continue
    fi
  fi

  avd="forestear_api$api"
  if ! "$AVDMANAGER" list avd 2>/dev/null | grep -q "Name: $avd"; then
    echo "no" | "$AVDMANAGER" create avd -n "$avd" -k "$image" --force >/dev/null 2>&1 || {
      RESULTS+=("$api | — | NOT RUN | AVD creation failed")
      overall=1; continue
    }
  fi

  echo "Booting $avd ..."
  "$EMULATOR" -avd "$avd" -no-window -no-audio -no-boot-anim \
    -gpu swiftshader_indirect -no-snapshot >/tmp/forestear_emu_$api.log 2>&1 &
  emu_pid=$!

  booted=0
  for _ in $(seq 1 180); do
    if [[ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
      booted=1; break
    fi
    sleep 2
  done

  if [[ $booted -ne 1 ]]; then
    kill "$emu_pid" 2>/dev/null; wait "$emu_pid" 2>/dev/null
    RESULTS+=("$api | — | NOT RUN | emulator did not boot in 6 min")
    overall=1; continue
  fi

  model="$("$ADB" shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
  build="$("$ADB" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')"

  args=()
  [[ $FULL -eq 0 ]] && args+=(
    -Pandroid.testInstrumentationRunnerArguments.class=org.forestear.node.DeviceAdaptationTest
  )

  ( cd "$GRADLE_DIR" && \
    JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null)}" \
    ./gradlew :app:connectedDebugAndroidTest "${args[@]}" --console=plain \
      >"/tmp/forestear_matrix_$api.log" 2>&1 )
  rc=$?

  # Count crashes from the run's logcat capture.
  crash="$("$ADB" logcat -d -b crash 2>/dev/null | grep -c "FATAL EXCEPTION" || true)"
  "$ADB" emu kill >/dev/null 2>&1 || kill "$emu_pid" 2>/dev/null
  wait "$emu_pid" 2>/dev/null

  if [[ $rc -eq 0 && "$crash" -eq 0 ]]; then
    RESULTS+=("$api | $model (Android $build) | PASS | connectedDebugAndroidTest exit 0, 0 fatal")
    ran=$((ran + 1))
  else
    RESULTS+=("$api | $model (Android $build) | FAIL | exit $rc, fatal=$crash, log /tmp/forestear_matrix_$api.log")
    overall=1
  fi
  write_report
done

write_report
echo
cat "$OUT"
echo
if [[ $ran -eq 0 ]]; then
  echo "No API actually ran. Report written with NOT RUN rows." >&2
  exit 2
fi
exit "$overall"
