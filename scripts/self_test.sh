#!/usr/bin/env bash
# Headless hardware/configuration self-test over adb.
#
# This checks everything that can be verified without touching the phone UI:
# install, microphone permission, notification permission, Doze allowlist,
# telephony/SMS, network, backend reachability, model assets and whether the
# foreground service is actually running.
#
# The in-app Diagnose > Hardware self-test additionally probes the microphone
# (dBFS/clipping), headset detection, TFLite delegate load and thermal sensors.
#
# Usage:
#   ./self_test.sh
#   BACKEND_URL=http://192.168.1.20:8000 ./self_test.sh
set -uo pipefail

PKG="${FORESTEAR_PACKAGE:-org.forestear.node}"
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
[[ -x "$ADB" ]] || ADB="$(command -v adb || true)"
[[ -n "$ADB" ]] || { echo "adb not found" >&2; exit 1; }
BACKEND_URL="${BACKEND_URL:-http://127.0.0.1:8000}"

FAIL=0
WARN=0
pass() { printf 'PASS  %s\n' "$1"; }
fail() { printf 'FAIL  %s\n' "$1"; FAIL=$((FAIL + 1)); }
warn() { printf 'WARN  %s\n' "$1"; WARN=$((WARN + 1)); }
skip() { printf 'SKIP  %s\n' "$1"; }

sh() { "$ADB" shell "$@" 2>/dev/null | tr -d '\r'; }

echo "ForestEar Node self-test"
echo "========================"

# 1. device -------------------------------------------------------------
DEVICES="$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')"
if [[ -n "$DEVICES" ]]; then
  pass "device connected ($(echo "$DEVICES" | head -1))"
  MODEL="$(sh getprop ro.product.model)"
  SDK="$(sh getprop ro.build.version.sdk)"
  echo "      model=$MODEL sdk=$SDK"
else
  fail "no adb device in 'device' state"
  echo
  echo "Cannot continue without a device."
  exit 1
fi

# 2. install ------------------------------------------------------------
VERSION="$(sh dumpsys package "$PKG" | grep -m1 versionName | awk -F= '{print $2}')"
if [[ -n "$VERSION" ]]; then
  pass "package installed (version $VERSION)"
else
  fail "package $PKG not installed"
fi

APK_PATH="$(sh pm path "$PKG" | sed 's/^package://')"
if [[ -n "$APK_PATH" ]]; then
  SIZE="$(sh stat -c %s "$APK_PATH" 2>/dev/null || echo 0)"
  if [[ "${SIZE:-0}" -gt 50000000 ]]; then
    pass "APK present and contains model assets (${SIZE} bytes)"
  else
    warn "APK is only ${SIZE} bytes; model assets may be missing"
  fi
else
  fail "APK path not reported by package manager"
fi

# 3. permissions --------------------------------------------------------
grants="$(sh dumpsys package "$PKG")"
check_grant() {
  local perm="$1" label="$2" required="$3"
  if echo "$grants" | grep -q "android.permission.$perm: granted=true"; then
    pass "$label granted"
  elif [[ "$required" == "required" ]]; then
    fail "$label NOT granted ($perm)"
  else
    warn "$label not granted ($perm) - optional"
  fi
}
check_grant RECORD_AUDIO "microphone" required
check_grant ACCESS_FINE_LOCATION "fine location" optional
check_grant READ_PHONE_STATE "phone state" optional
check_grant SEND_SMS "SMS fallback" optional
if [[ "${SDK:-0}" -ge 33 ]]; then
  check_grant POST_NOTIFICATIONS "notifications" required
fi

# 4. background protections --------------------------------------------
if sh dumpsys deviceidle whitelist | grep -q "$PKG"; then
  pass "app is on the Doze / battery-optimisation allowlist"
else
  fail "app is NOT on the Doze allowlist (run ./whitelist_battery.sh)"
fi

# 5. service state ------------------------------------------------------
SERVICES="$(sh dumpsys activity services "$PKG")"
if echo "$SERVICES" | grep -q "NodeService"; then
  pass "NodeService is running"
  echo "$SERVICES" | grep -E "isForeground|foregroundServiceType" | head -2 | sed 's/^/      /'
else
  warn "NodeService is not running (start it from the app or ./soak.sh --start)"
fi

# 6. telephony / SMS ----------------------------------------------------
if sh pm list features | grep -q "android.hardware.telephony"; then
  pass "telephony hardware present"
  SIMS="$(sh dumpsys telephony.registry | grep -c 'mSubscriptionId' || true)"
  echo "      subscription records seen: ${SIMS:-0}"
else
  warn "no telephony feature; SMS fallback unavailable"
fi

# 7. network ------------------------------------------------------------
CONN="$(sh dumpsys connectivity | grep -m1 -E 'NetworkAgentInfo.*(WIFI|MOBILE|ETHERNET)')"
if [[ -n "$CONN" ]]; then
  pass "network connected"
  echo "      $(echo "$CONN" | cut -c1-140)"
else
  warn "no active network reported by connectivity service"
fi

# 8. backend ------------------------------------------------------------
if command -v curl >/dev/null 2>&1; then
  HEALTH="$(curl -sS --max-time 5 "$BACKEND_URL/health" 2>/dev/null || true)"
  if [[ "$HEALTH" == *'"status"'* || "$HEALTH" == *'"ok"'* ]]; then
    pass "backend reachable at $BACKEND_URL"
  else
    warn "backend not reachable at $BACKEND_URL (expected if it is not running)"
  fi
else
  skip "curl not installed on host; cannot check backend"
fi

# 9. thermal ------------------------------------------------------------
THERMAL="$(sh dumpsys thermalservice | grep -m1 'Thermal Status' || true)"
if [[ -n "$THERMAL" ]]; then
  pass "thermal service reports: $(echo "$THERMAL" | sed 's/^ *//')"
else
  warn "thermal status not available over adb"
fi

# 10. crash check -------------------------------------------------------
CRASHES="$(sh logcat -d -b crash -t 200 2>/dev/null | grep -c "$PKG" || true)"
if [[ "${CRASHES:-0}" -eq 0 ]]; then
  pass "no recent crash-buffer entries for $PKG"
else
  warn "$CRASHES recent crash-buffer entries mention $PKG (run ./capture_logs.sh)"
fi

echo
echo "========================"
echo "failures=$FAIL warnings=$WARN"
[[ "$FAIL" -eq 0 ]] || exit 1
