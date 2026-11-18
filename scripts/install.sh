#!/usr/bin/env bash
# Build and install ForestEar Node on the attached phone.
#
# Usage:
#   ./install.sh              # debug build, install, grant runtime permissions
#   ./install.sh --no-grant   # skip permission grants
#   ./install.sh --grant-all  # install with -g (grants ALL permissions incl. SMS)
#   ./install.sh --release    # assembleRelease (unsigned; needs signing config)
#
# Requires: JDK 17+ (JDK 21 recommended), Android SDK at ~/Library/Android/sdk
# (or ANDROID_HOME), and one device visible in `adb devices`.
set -euo pipefail

PKG="${FORESTEAR_PACKAGE:-org.forestear.node}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT="$(cd "$HERE/../ForestEarNode" && pwd)"

GRANT=1
GRANT_ALL=0
VARIANT="debug"
for arg in "$@"; do
  case "$arg" in
    --no-grant) GRANT=0 ;;
    --grant-all) GRANT_ALL=1 ;;
    --release) VARIANT="release" ;;
    -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

# AGP 8.5 is not compatible with the default JDK 24 on macOS; prefer 21.
if [[ -z "${JAVA_HOME:-}" && -d /Library/Java/JavaVirtualMachines/openjdk-21.jdk/Contents/Home ]]; then
  export JAVA_HOME=/Library/Java/JavaVirtualMachines/openjdk-21.jdk/Contents/Home
fi
if [[ -z "${ANDROID_HOME:-}" && -d "$HOME/Library/Android/sdk" ]]; then
  export ANDROID_HOME="$HOME/Library/Android/sdk"
fi
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
[[ -x "$ADB" ]] || ADB="$(command -v adb || true)"
[[ -n "$ADB" ]] || { echo "adb not found. Install platform-tools or set ANDROID_HOME." >&2; exit 1; }

echo "== devices =="
"$ADB" devices -l

cd "$PROJECT"
if [[ "$VARIANT" == "release" ]]; then
  ./gradlew :app:assembleRelease --console=plain
  APK="app/build/outputs/apk/release/app-release-unsigned.apk"
else
  ./gradlew :app:assembleDebug --console=plain
  APK="app/build/outputs/apk/debug/app-debug.apk"
fi
[[ -f "$APK" ]] || { echo "APK not found: $APK" >&2; exit 1; }

echo "== installing $APK =="
if [[ "$GRANT_ALL" == "1" ]]; then
  echo "WARNING: -g grants ALL requested runtime permissions, including SEND_SMS."
  "$ADB" install -g -r "$APK"
  GRANT=0
else
  "$ADB" install -r "$APK"
fi

if [[ "$GRANT" == "1" ]]; then
  "$HERE/grant_permissions.sh" || true
fi

echo
echo "Installed. Open the app once so the foreground service can acquire the"
echo "microphone type on Android 14, then finish the Setup wizard."
