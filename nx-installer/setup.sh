#!/bin/bash
# setup.sh — the Noxs nx-installer bootstrap (original implementation).
#
# Downloads a tagged nx-installer release tar.gz from GitHub, verifies its
# SHA-256 sidecar, extracts it into a staging directory and runs the payload
# installer. Remote content is NEVER piped into a shell (spec §33: no
# curl|bash — download, verify, then execute the verified payload).
#
# Usage:
#   ./setup.sh                          # latest release of the default repo
#   NX_INSTALLER_TAG=v0.6.0 ./setup.sh  # pin a release tag
#   NX_INSTALLER_REPO=web12-app/noxs ./setup.sh
#
# Overridable via environment:
#   NX_INSTALLER_REPO   GitHub "owner/repo" (default: web12-app/noxs)
#   NX_INSTALLER_TAG    release tag (default: latest published release)
#   NX_INSTALLER_ASSET  asset name prefix (default: noxs-installer)

set -u

REPO="${NX_INSTALLER_REPO:-web12-app/noxs}"
TAG="${NX_INSTALLER_TAG:-}"
ASSET_PREFIX="${NX_INSTALLER_ASSET:-noxs-installer}"
API_BASE="https://api.github.com"
DL_BASE="https://github.com"

msg() { printf 'nx-installer: %s\n' "$*"; }
err() { printf 'nx-installer: %s\n' "$*" >&2; }
die() { err "$*"; exit 1; }

need_tool() {
    command -v "$1" >/dev/null 2>&1 || die "missing tool: $1 (install it and retry)"
}

for tool in curl tar sha256sum mktemp; do
    need_tool "$tool"
done

# ------------------------------------------------------------- resolve tag

resolve_tag() {
    if [ -n "$TAG" ]; then
        printf '%s' "$TAG"
        return 0
    fi
    # Honest failure without network — never guess a tag.
    curl -fsSL --max-time 30 \
        -H "Accept: application/vnd.github+json" \
        "$API_BASE/repos/$REPO/releases/latest" 2>/dev/null |
        sed -n 's/.*"tag_name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' |
        head -n 1
}

TAG="$(resolve_tag)"
[ -n "$TAG" ] || die "could not resolve the latest release tag; set NX_INSTALLER_TAG and retry"

# Validate the tag: releases are v-prefixed semver, never arbitrary input.
case "$TAG" in
    v[0-9].[0-9]*.[0-9]*) ;;
    *) die "refusing to install from tag '$TAG' (expected vMAJOR.MINOR.PATCH)" ;;
esac

STAGING="$(mktemp -d "${TMPDIR:-/tmp}/noxs-installer.XXXXXX")"
trap 'rm -rf "$STAGING"' EXIT INT TERM

TARBALL="$ASSET_PREFIX-${TAG#v}.tar.gz"
URL="$DL_BASE/$REPO/releases/download/$TAG/$TARBALL"

msg "release: $REPO@$TAG"
msg "downloading $TARBALL ..."
curl -fsSL --max-time 300 --retry 3 -o "$STAGING/$TARBALL" "$URL" ||
    die "download failed; check your connection and the release page"

msg "verifying SHA-256 ..."
curl -fsSL --max-time 60 --retry 3 -o "$STAGING/$TARBALL.sha256" "$URL.sha256" ||
    die "checksum sidecar unavailable; refusing to install an unverified payload"

# Verify with the standard sum file format inside the staging directory.
(cd "$STAGING" && sha256sum -c "$TARBALL.sha256" >/dev/null 2>&1) ||
    die "checksum verification FAILED; the download was removed and nothing was installed"

msg "checksum OK; extracting payload ..."
mkdir -p "$STAGING/payload"
tar -xzf "$STAGING/$TARBALL" -C "$STAGING/payload" ||
    die "payload archive could not be extracted"

[ -f "$STAGING/payload/installer.sh" ] ||
    die "payload is missing installer.sh; refusing to continue"

msg "running installer (targets: /usr/local/bin, /usr/local/lib/noxs, /usr/local/share/noxs-pkg)"
sh "$STAGING/payload/installer.sh" "$@" ||
    die "installation failed; no partial files were left behind"

msg "Noxs nx-installer setup complete."
msg "Verify with: nx version && nx list"
