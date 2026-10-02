#!/usr/bin/env bash
# shellcheck disable=SC1090,SC1091,SC2012,SC2034,SC2086,SC2164,SC2295
# scripts/build-rootfs.sh — build/pin the Noxs Debian 12 rootfs + proot (original).
#
# Produces:
#   linux-runtime/bootstrap/<arch>/bootstrap.manifest   (URL + SHA-256 pinned)
#   linux-runtime/bootstrap/<arch>/rootfs.tar.xz       (optional offline bundle)
#
# The same artifacts are mirrored into app/src/main/assets/bootstrap/<arch>/.
#
# Modes:
#   --pin-only       download + checksum-pin (default; no artifact kept in repo)
#   --bundle         also stage the rootfs tarball for offline app bundles
#   --debootstrap    build a custom rootfs with debootstrap (Debian/Ubuntu host)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARCH="${NOXS_ARCH:-arm64-v8a}"
MODE="${1:---pin-only}"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

case "$ARCH" in
    arm64-v8a)
        ROOTFS_URL="https://github.com/debuerreotype/docker-debian-artifacts/raw/dist-arm64v8/bookworm/rootfs.tar.xz"
        PROOT_URL="https://github.com/proot-me/proot/releases/download/v5.4.0/proot-v5.4.0-aarch64-static"
        ;;
    armeabi-v7a)
        ROOTFS_URL="https://github.com/debuerreotype/docker-debian-artifacts/raw/dist-arm32v7/bookworm/rootfs.tar.xz"
        PROOT_URL="https://github.com/proot-me/proot/releases/download/v5.4.0/proot-v5.4.0-armv7l-static"
        ;;
    x86_64)
        ROOTFS_URL="https://github.com/debuerreotype/docker-debian-artifacts/raw/dist-amd64/bookworm/rootfs.tar.xz"
        PROOT_URL="https://github.com/proot-me/proot/releases/download/v5.4.0/proot-v5.4.0-x86_64-static"
        ;;
    *) echo "unsupported arch: $ARCH" >&2; exit 1 ;;
esac

command -v curl >/dev/null 2>&1 || { echo "curl required" >&2; exit 1; }
command -v sha256sum >/dev/null 2>&1 || { echo "sha256sum required" >&2; exit 1; }

echo "[noxs] arch:     $ARCH"
echo "[noxs] fetching rootfs + proot for checksum pinning…"
curl -fSL --retry 3 -o "$STAGE/rootfs.tar.xz" "$ROOTFS_URL"
curl -fSL --retry 3 -o "$STAGE/proot"          "$PROOT_URL"
chmod +x "$STAGE/proot" 2>/dev/null || true

ROOTFS_SHA="$(sha256sum "$STAGE/rootfs.tar.xz" | awk '{print $1}')"
PROOT_SHA="$(sha256sum "$STAGE/proot" | awk '{print $1}')"
ROOTFS_SIZE="$(stat -c%s "$STAGE/rootfs.tar.xz" 2>/dev/null || stat -f%z "$STAGE/rootfs.tar.xz")"
echo "[noxs] rootfs sha256: $ROOTFS_SHA (${ROOTFS_SIZE} bytes)"
echo "[noxs] proot  sha256: $PROOT_SHA"

MANIFEST="$(cat <<EOF
{
  "schema": 1,
  "arch": "$ARCH",
  "rootfs": {
    "url": "$ROOTFS_URL",
    "sha256": "$ROOTFS_SHA",
    "format": "tar.xz",
    "sizeBytes": $ROOTFS_SIZE
  },
  "proot": {
    "url": "$PROOT_URL",
    "sha256": "$PROOT_SHA"
  },
  "generatedAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "source": "scripts/build-rootfs.sh"
}
EOF
)"

for DEST in \
    "$ROOT/linux-runtime/bootstrap/$ARCH/bootstrap.manifest" \
    "$ROOT/app/src/main/assets/bootstrap/$ARCH/bootstrap.manifest"; do
    mkdir -p "$(dirname "$DEST")"
    printf '%s\n' "$MANIFEST" > "$DEST"
    echo "[noxs] pinned manifest → ${DEST#$ROOT/}"
done

if [ "$MODE" = "--bundle" ]; then
    for DEST in \
        "$ROOT/linux-runtime/bootstrap/$ARCH" \
        "$ROOT/app/src/main/assets/bootstrap/$ARCH"; do
        cp "$STAGE/rootfs.tar.xz" "$DEST/rootfs.tar.xz"
        cp "$STAGE/proot" "$DEST/proot"
        echo "[noxs] bundled artifacts → ${DEST#$ROOT/}"
    done
fi

if [ "$MODE" = "--debootstrap" ]; then
    command -v debootstrap >/dev/null 2>&1 || { echo "debootstrap required (apt install debootstrap)" >&2; exit 1; }
    SUITE="bookworm"
    TARGET="$STAGE/rootfs"
    case "$ARCH" in
        arm64-v8a)  DARCH=arm64;  DFAMILY=arm64v8  ;;
        armeabi-v7a) DARCH=armhf; DFAMILY=arm32v7 ;;
        x86_64)     DARCH=amd64;  DFAMILY=amd64    ;;
    esac
    echo "[noxs] debootstrap $SUITE ($DARCH)…"
    sudo debootstrap --arch="$DARCH" --variant=minbase --include="$(cat <<'PKGS'
bash,coreutils,apt,sudo,procps,util-linux,grep,sed,awk,findutils,tar,gzip,
xz-utils,curl,wget,git,openssh-client,nano,vim-tiny,less,ca-certificates,
passwd,socat
PKGS
)" "$SUITE" "$TARGET" https://deb.debian.org/debian
    sudo tar -C "$TARGET" -cJf "$STAGE/rootfs.tar.xz" .
    ROOTFS_SHA="$(sha256sum "$STAGE/rootfs.tar.xz" | awk '{print $1}')"
    echo "[noxs] custom rootfs sha256: $ROOTFS_SHA — update the manifest above if you re-pin"
fi

echo "[noxs] done."
