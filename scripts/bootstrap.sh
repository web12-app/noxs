#!/usr/bin/env bash
# shellcheck disable=SC1090,SC1091,SC2012,SC2034,SC2086,SC2164,SC2295
# scripts/bootstrap.sh — local developer bootstrap for the Noxs project (original).
# Verifies the toolchain and prepares the Gradle wrapper + Android SDK paths.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
echo "[noxs] project root: $ROOT"

fail() { echo "[noxs] ERROR: $*" >&2; exit 1; }

# ---- Java 17 -------------------------------------------------------------
if ! command -v java >/dev/null 2>&1; then
    fail "JDK not found — install JDK 17 (https://adoptium.net)"
fi
JAVA_VER="$(java -version 2>&1 | head -1 || true)"
echo "[noxs] $JAVA_VER"
java -version 2>&1 | grep -q 'version "17' || \
    echo "[noxs] WARNING: JDK 17 recommended (AGP 8.x)"

# ---- Gradle wrapper ------------------------------------------------------
cd "$ROOT"
if [ ! -f gradle/wrapper/gradle-wrapper.jar ]; then
    echo "[noxs] gradle-wrapper.jar missing — generating…"
    if command -v gradle >/dev/null 2>&1; then
        gradle wrapper --gradle-version 8.7
    else
        fail "gradle CLI not found. Install Gradle 8.7 or run: ./gradlew (Android Studio will also provision it)"
    fi
fi

# ---- Android SDK ---------------------------------------------------------
if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ]; then
    for CAND in "$HOME/Android/Sdk" "$HOME/Library/Android/sdk" /opt/android-sdk; do
        if [ -d "$CAND" ]; then
            export ANDROID_HOME="$CAND"
            break
        fi
    done
fi
if [ -z "${ANDROID_HOME:-}" ]; then
    cat >&2 <<'EOF'
[noxs] ANDROID_HOME is not set.
[noxs] Install the Android SDK (Android Studio → SDK Manager) and export:
[noxs]   export ANDROID_HOME=$HOME/Android/Sdk
[noxs] Required components: platform 34, build-tools 34.0.0, NDK 26.3.11579264, CMake 3.22.1
EOF
    exit 1
fi
echo "[noxs] ANDROID_HOME=$ANDROID_HOME"

# ---- NDK + CMake presence (auto-install by AGP is attempted otherwise) ----
SDKM="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
if [ -x "$SDKM" ]; then
    yes | "$SDKM" --licenses >/dev/null 2>&1 || true
    "$SDKM" --install "ndk;26.3.11579264" "cmake;3.22.1" >/dev/null 2>&1 || true
fi

echo "[noxs] bootstrap complete. Next: ./gradlew assembleDebug"
