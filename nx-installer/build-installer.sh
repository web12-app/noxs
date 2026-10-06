#!/bin/bash
# build-installer.sh — pack the nx-installer payload into a release tar.gz
# (original implementation).
#
# Assembles the verified layout that installer.sh expects:
#
#   installer.sh
#   bin/nx
#   lib/noxs-pkg/*.sh
#   lib/noxs/nx-api/{nx-api.js,package.json,README.md}
#   share/noxs-pkg/templates/...
#   share/noxs-pkg/.nx-version
#
# and produces  noxs-installer-<VERSION>.tar.gz  +  .sha256  sidecar.
#
# Usage:
#   ./build-installer.sh [--version X.Y.Z] [--out DIR]
#
# The payload content always comes from the canonical Kotlin templates via
# scripts/extract_nx.py — the repository never carries a second hand-edited
# copy of the nx runtime.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION=""
OUT="$ROOT/nx-installer/dist"

while [ $# -gt 0 ]; do
    case "$1" in
        --version) VERSION="${2:?}"; shift 2 ;;
        --out) OUT="${2:?}"; shift 2 ;;
        *) echo "usage: $0 [--version X.Y.Z] [--out DIR]" >&2; exit 2 ;;
    esac
done

if [ -z "$VERSION" ]; then
    # Default: the app version from the root build file (single source of truth).
    VERSION="$(sed -n 's/.*versionName[[:space:]]*"v\?\([0-9.]*\)".*/\1/p' "$ROOT/app/build.gradle.kts" | head -n 1)"
    VERSION="${VERSION:-0.0.0}"
fi

STAGING="$(mktemp -d)"
trap 'rm -rf "$STAGING"' EXIT
PAYLOAD="$STAGING/payload"
mkdir -p "$PAYLOAD/bin" "$PAYLOAD/lib/noxs-pkg" "$PAYLOAD/lib/noxs/nx-api" "$PAYLOAD/share/noxs-pkg"

# --- canonical runtime content (Kotlin templates are the source of truth) ---
python3 "$ROOT/scripts/extract_payload.py" "$ROOT" "$PAYLOAD"

cp "$ROOT/nx-installer/installer.sh" "$PAYLOAD/installer.sh"
chmod 0755 "$PAYLOAD/installer.sh" "$PAYLOAD/bin/nx" "$PAYLOAD"/lib/noxs-pkg/*.sh

# --- pack ---
NAME="noxs-installer-$VERSION"
mkdir -p "$OUT"
tar -czf "$OUT/$NAME.tar.gz" -C "$STAGING" payload
(cd "$OUT" && sha256sum "$NAME.tar.gz" > "$NAME.tar.gz.sha256")

echo "[nx-installer] packed $OUT/$NAME.tar.gz"
sha256sum "$OUT/$NAME.tar.gz"
