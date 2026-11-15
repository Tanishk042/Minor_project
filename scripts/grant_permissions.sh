#!/usr/bin/env bash
# Grant the runtime permissions ForestEar Node needs via adb.
#
# `adb install -g` also works, but it grants *all* requested permissions,
# including SMS, which should be an explicit operator decision. This script
# grants the listening/telemetry set and only grants SMS when --sms is passed.
#
# Usage:
#   ./grant_permissions.sh          # mic, notifications, location, phone state
#   ./grant_permissions.sh --sms    # also grant SEND_SMS
set -euo pipefail

PKG="${FORESTEAR_PACKAGE:-org.forestear.node}"
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
[[ -x "$ADB" ]] || ADB="$(command -v adb || true)"
[[ -n "$ADB" ]] || { echo "adb not found" >&2; exit 1; }

SDK="$("$ADB" shell getprop ro.build.version.sdk | tr -d '\r')"
echo "device SDK=$SDK package=$PKG"

grant() {
  local perm="$1"
  if "$ADB" shell pm grant "$PKG" "$perm" 2>/dev/null; then
    echo "granted  $perm"
  else
    echo "skipped  $perm (not declared, already granted, or fixed by policy)"
  fi
}

grant android.permission.RECORD_AUDIO
grant android.permission.ACCESS_FINE_LOCATION
grant android.permission.ACCESS_COARSE_LOCATION
grant android.permission.READ_PHONE_STATE
if [[ "$SDK" -ge 33 ]]; then
  grant android.permission.POST_NOTIFICATIONS
fi
if [[ "${1:-}" == "--sms" ]]; then
  grant android.permission.SEND_SMS
else
  echo "SEND_SMS not granted (pass --sms to enable the SMS fallback)"
fi

echo
echo "Current grants:"
"$ADB" shell dumpsys package "$PKG" | grep -E "android.permission.(RECORD_AUDIO|POST_NOTIFICATIONS|ACCESS_FINE_LOCATION|READ_PHONE_STATE|SEND_SMS): granted" || true
