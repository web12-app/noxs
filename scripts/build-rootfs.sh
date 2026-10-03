#!/usr/bin/env bash
# scripts/build-rootfs.sh — pin the Noxs Debian 12 rootfs (original).
#
# Produces:
#   linux-runtime/bootstrap/<arch>/bootstrap.manifest   (URL + SHA-256 pinned)
#   linux-runtime/bootstrap/<arch>/rootfs.tar.gz       (optional offline bundle)
#
# The same artifacts are mirrored into app/src/main/assets/bootstrap/<arch>/.
#
# Rootfs source: the official Debian-built Docker rootfs (debuerreotype
# docker-debian-artifacts, the content behind library/debian images).
# NOTE: the rootfs now lives under <suite>/oci/blobs/rootfs.tar.gz in that
# repo, and the URL is pinned to an immutable COMMIT SHA so the SHA-256 pin
# stays valid forever (branch tips are rewritten on every rebuild).
#
# proot is NO LONGER downloaded: it ships inside the APK as jniLibs
# (app/src/main/jniLibs/<abi>/libproot.so) because Android 10+ (W^X / SELinux)
# denies execve() from app data storage. See scripts/stage-proot-jnilibs.py.
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

# Commit SHAs of the debuerreotype branch tips used to pin v0.1.0 (2026-10-03).
# Re-pin by resolving the branch head and updating these values.
case "$ARCH" in
    arm64-v8a)
        DEBUERREO_COMMIT="ca011a8b1c3b259e4cbbf83bf6841f1fd5f497c1"
        ;;
    armeabi-v7a)
        DEBUERREO_COMMIT="9131b5bb4d0b0ea11c1339cabfbd09ce42769ebf"
        ;;
    x86_64)
        DEBUERREO_COMMIT="8f962b15d7884a90e17876a9303cbac909d119aa"
        ;;
    *) echo "unsupported arch: $ARCH" >&2; exit 1 ;;
esac
ROOTFS_URL="https://raw.githubusercontent.com/debuerreotype/docker-debian-artifacts/${DEBUERREO_COMMIT}/bookworm/oci/blobs/rootfs.tar.gz"

command -v curl >/dev/null 2>&1 || { echo "curl required" >&2; exit 1; }
command -v sha256sum >/dev/null 2>&1 || { echo "sha256sum required" >&2; exit 1; }

echo "[noxs] arch:     $ARCH"
echo "[noxs] fetching rootfs for checksum pinning…"
curl -fSL --retry 3 -o "$STAGE/rootfs.tar.gz" "$ROOTFS_URL"

ROOTFS_SHA="$(sha256sum "$STAGE/rootfs.tar.gz" | awk '{print $1}')"
ROOTFS_SIZE="$(stat -c%s "$STAGE/rootfs.tar.gz" 2>/dev/null || stat -f%z "$STAGE/rootfs.tar.gz")"
echo "[noxs] rootfs sha256: $ROOTFS_SHA (${ROOTFS_SIZE} bytes)"

MANIFEST="$(cat <<EOF
{
  "schema": 1,
  "arch": "$ARCH",
  "rootfs": {
    "url": "$ROOTFS_URL",
    "sha256": "$ROOTFS_SHA",
    "format": "tar.gz",
    "sizeBytes": $ROOTFS_SIZE
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
        cp "$STAGE/rootfs.tar.gz" "$DEST/rootfs.tar.gz"
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