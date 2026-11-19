#!/usr/bin/env bash
# Grant the runtime permissions ForestEar Node needs via adb.
#
# `adb install -g` also works, but it grants *all* requested permissions,
# including SMS, which should be an explicit operator decision. This script
# grants the listening/telemetry set and only grants SMS when --sms is passed.
#
# Some ROMs (observed on OxygenOS / CPH2381) refuse `pm grant` from the adb
# shell with a SecurityException. That is reported as FAILED here, never as a
# silent "skip": the caller must use `adb install -g -r` or the Settings UI.
#
# Usage:
#   ./grant_permissions.sh          # mic, notifications, location, phone state
#   ./grant_permissions.sh --sms    # also grant SEND_SMS
set -uo pipefail

PKG="${FORESTEAR_PACKAGE:-org.forestear.node}"
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
[[ -x "$ADB" ]] || ADB="$(command -v adb || true)"
[[ -n "$ADB" ]] || { echo "adb not found" >&2; exit 1; }

SDK="$("$ADB" shell getprop ro.build.version.sdk | tr -d '\r')"
echo "device SDK=$SDK package=$PKG"

DUMP=""
refresh_dump() { DUMP="$("$ADB" shell dumpsys package "$PKG" 2>/dev/null)"; }
refresh_dump

is_granted() {
  printf '%s\n' "$DUMP" | grep -F "$1: granted=true" >/dev/null 2>&1
}

SHELL_GRANT_BLOCKED=0
ANY_FAILED=0

grant() {
  local perm="$1" out
  if is_granted "$perm"; then
    printf 'already  %s\n' "$perm"
    return 0
  fi
  out="$("$ADB" shell pm grant "$PKG" "$perm" 2>&1)"
  refresh_dump
  if is_granted "$perm"; then
    printf 'granted  %s\n' "$perm"
    return 0
  fi
  ANY_FAILED=1
  if printf '%s' "$out" | grep -q 'SecurityException'; then
    SHELL_GRANT_BLOCKED=1
    printf 'FAILED   %s (shell is not allowed to grant runtime permissions on this ROM)\n' "$perm"
    printf '%s\n' "$out" | grep -m1 'SecurityException' >&2
  else
    printf 'FAILED   %s (not declared, not grantable, or grant was rejected)\n' "$perm"
    [[ -z "$out" ]] || printf '%s\n' "$out" | head -2 >&2
  fi
  return 1
}

grant android.permission.RECORD_AUDIO || true
grant android.permission.ACCESS_FINE_LOCATION || true
grant android.permission.ACCESS_COARSE_LOCATION || true
grant android.permission.READ_PHONE_STATE || true
if [[ "$SDK" -ge 33 ]]; then
  grant android.permission.POST_NOTIFICATIONS || true
fi
if [[ "${1:-}" == "--sms" ]]; then
  grant android.permission.SEND_SMS || true
else
  echo "SEND_SMS not requested (pass --sms to enable the SMS fallback)"
fi

echo
echo "Current grants:"
for perm in \
  android.permission.RECORD_AUDIO \
  android.permission.POST_NOTIFICATIONS \
  android.permission.ACCESS_FINE_LOCATION \
  android.permission.READ_PHONE_STATE \
  android.permission.SEND_SMS; do
  state="$(printf '%s\n' "$DUMP" | grep -F "$perm: granted=" | head -1 | tr -d '\r')"
  if [[ -z "$state" ]]; then
    printf '  %-48s not declared\n' "$perm"
  else
    printf '  %-48s %s\n' "$perm" "${state#*: }"
  fi
done

if [[ "$SHELL_GRANT_BLOCKED" -eq 1 ]]; then
  cat <<'EOF'

WARNING: this ROM refused the adb shell permission grants (SecurityException).
The FAILED lines above are NOT successful grants. Choose one explicit option:
  1) Reinstall with: adb install -g -r <apk>
     WARNING: -g grants ALL requested runtime permissions, including SEND_SMS.
     In this repo you can use: ./scripts/install.sh --grant-all
  2) Grant manually on the phone:
     Settings -> Apps -> ForestEar Node -> Permissions
Verify with: adb shell dumpsys package org.forestear.node | grep 'granted=true'
EOF
  exit 2
fi

if [[ "$ANY_FAILED" -eq 1 ]]; then
  echo
  echo "WARNING: at least one permission was not granted. Check the lines above"
  echo "and verify in Settings before claiming the device is ready."
  exit 3
fi
