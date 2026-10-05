#!/usr/bin/env bash
# shellcheck disable=SC1090,SC1091,SC2012,SC2034,SC2086,SC2164,SC2295
# scripts/package.sh — build Noxs APKs (original project tooling).
# Usage:
#   scripts/package.sh debug
#   scripts/package.sh release   (set NOXS_KEYSTORE_* env vars for signing)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VARIANT="${1:-debug}"
cd "$ROOT"

# Provision the wrapper jar if it is missing (fresh clones without git LFS etc.)
if [ ! -f gradle/wrapper/gradle-wrapper.jar ]; then
    echo "[noxs] generating Gradle wrapper…"
    ./gradlew wrapper --gradle-version 8.7 || gradle wrapper --gradle-version 8.7
fi

case "$VARIANT" in
    debug)
        ./gradlew assembleDebug testDebugUnitTest
        APK="app/build/outputs/apk/debug/app-debug.apk"
        ;;
    release)
        ./gradlew assembleRelease testReleaseUnitTest
        APK="app/build/outputs/apk/release/app-release.apk"
        ;;
    *) echo "usage: $0 debug|release" >&2; exit 1 ;;
esac

if [ -f "$APK" ]; then
    echo "[noxs] built: $APK ($(du -h "$APK" | cut -f1))"
else
    echo "[noxs] WARNING: expected APK not found at $APK" >&2
    exit 1
fi
