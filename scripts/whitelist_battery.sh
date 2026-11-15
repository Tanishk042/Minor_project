#!/usr/bin/env bash
# Put ForestEar Node on the device-idle allowlist (the adb equivalent of
# Settings > Battery > Unrestricted) and verify it.
#
# OxygenOS still has its own app-freezer/auto-launch controls that adb cannot
# change. Open Setup in the app and follow the deep links for those.
#
# Usage:
#   ./whitelist_battery.sh            # add to allowlist and verify
#   ./whitelist_battery.sh --check    # verify only
set -euo pipefail

PKG="${FORESTEAR_PACKAGE:-org.forestear.node}"
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
[[ -x "$ADB" ]] || ADB="$(command -v adb || true)"
[[ -n "$ADB" ]] || { echo "adb not found" >&2; exit 1; }

CHECK_ONLY=0
[[ "${1:-}" == "--check" ]] && CHECK_ONLY=1

if [[ "$CHECK_ONLY" == "0" ]]; then
  # deviceidle allowlist is the same list Settings > Battery optimisation uses.
  "$ADB" shell cmd deviceidle whitelist "+$PKG" 2>/dev/null \
    || "$ADB" shell dumpsys deviceidle whitelist "+$PKG" >/dev/null
  echo "requested allowlist entry for $PKG"
fi

echo
echo "== device idle whitelist =="
"$ADB" shell dumpsys deviceidle whitelist | tr -d '\r' | grep -F "$PKG" \
  && echo "PASS: $PKG is exempt from Doze app standby" \
  || echo "FAIL: $PKG is NOT on the device-idle allowlist"

echo
echo "== battery optimisation state =="
"$ADB" shell dumpsys power | tr -d '\r' | grep -i -m3 "mPowerSaveMode\|mIsPowered" || true

echo
echo "Remaining (cannot be set by adb on OxygenOS):"
echo "  - App auto-launch / startup manager: allow ForestEar Node"
echo "  - Background activity: allow"
echo "  - Lock the app in Recents (three-dot menu)"
echo "  - Battery > App battery management: Unrestricted"
