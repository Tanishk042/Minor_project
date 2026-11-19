#!/usr/bin/env bash
# Build a signed release APK (one per ABI + universal) when keystore.properties
# exists, otherwise build an unsigned release APK and say so plainly.
#
# Usage:
#   ./scripts/build_release.sh              # signed if configured
#   ./scripts/build_release.sh --no-splits  # single universal APK
#
# Never prints PASS for signing if no keystore was used.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE_DIR="$REPO_ROOT/ForestEarNode"
PROPS="$REPO_ROOT/keystore.properties"
SPLITS="-PabiSplits"

for arg in "$@"; do
  case "$arg" in
    --no-splits) SPLITS="" ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

if [[ ! -f "$PROPS" ]]; then
  echo "WARNING: $PROPS not found."
  echo "         The release APK will be UNSIGNED and cannot be installed."
  echo "         Copy keystore.properties.example and fill it in first."
  SIGNED=0
else
  SIGNED=1
  echo "Using signing config from $PROPS"
fi

cd "$GRADLE_DIR"
# shellcheck disable=SC2086
./gradlew :app:assembleRelease $SPLITS

OUT="$GRADLE_DIR/app/build/outputs/apk/release"
echo
echo "Release outputs in $OUT:"
find "$OUT" -maxdepth 1 -name '*.apk' -print0 2>/dev/null \
  | xargs -0 -I{} sh -c 'printf "  %-46s %s bytes\n" "$(basename "{}")" "$(stat -f%z "{}" 2>/dev/null || stat -c%s "{}")"'

APKSIGNER="$(find "${ANDROID_HOME:-$HOME/Library/Android/sdk}" \
  "${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}" \
  -type f -name apksigner 2>/dev/null | sort -V | tail -1 || true)"

echo
if [[ "$SIGNED" -eq 1 && -n "$APKSIGNER" ]]; then
  echo "Verifying signatures with $APKSIGNER"
  for apk in "$OUT"/*.apk; do
    [[ -f "$apk" ]] || continue
    if "$APKSIGNER" verify --print-certs "$apk" >/dev/null 2>&1; then
      echo "  SIGNED: $(basename "$apk")"
    else
      echo "  NOT SIGNED: $(basename "$apk")"
    fi
  done
elif [[ "$SIGNED" -eq 1 ]]; then
  echo "apksigner not found; could not verify the signature."
  echo "Signing config was applied but verification is NOT CONFIRMED."
else
  echo "Release build is UNSIGNED (no keystore.properties). Nothing was verified."
fi
