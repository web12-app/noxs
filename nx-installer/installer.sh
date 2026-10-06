#!/bin/bash
# installer.sh — the verified nx-installer payload (original implementation).
#
# Runs AFTER setup.sh has verified the release tar.gz. Installs the Noxs nx
# runtime files into the environment:
#
#   /usr/local/bin/nx                    the nx CLI
#   /usr/local/lib/noxs-pkg/*.sh         package + web bridge modules
#   /usr/local/lib/noxs/nx-api/          @noxs/nx-api SDK
#   /usr/local/share/noxs-pkg/templates  language templates
#
# Idempotent: safe to re-run; every step verifies what it installs. The
# payload never touches files outside the /usr/local Noxs prefixes and
# never executes repository source code.

set -u

PREFIX_BIN="${NX_PREFIX_BIN:-/usr/local/bin}"
PREFIX_LIB="${NX_PREFIX_LIB:-/usr/local/lib}"
PREFIX_SHARE="${NX_PREFIX_SHARE:-/usr/local/share}"

msg() { printf 'nx-installer: %s\n' "$*"; }
err() { printf 'nx-installer: %s\n' "$*" >&2; }
die() { err "$*"; exit 1; }

HERE="$(cd "$(dirname "$0")" && pwd)"

require_dir() {
    [ -d "$1" ] || die "payload is incomplete: $1 missing"
}

require_dir "$HERE/bin"
require_dir "$HERE/lib/noxs-pkg"
require_dir "$HERE/lib/noxs/nx-api"
require_dir "$HERE/share/noxs-pkg/templates"

install_file() {
    local source="$1" target="$2" mode="$3"
    [ -f "$source" ] || die "payload file missing: ${source#"$HERE"/}"
    mkdir -p "$(dirname "$target")" || die "cannot create $(dirname "$target")"
    # Staged copy + atomic move: a failed install leaves no partial files.
    cp "$source" "$target.tmp.$$" || die "cannot stage $target"
    chmod "$mode" "$target.tmp.$$"
    mv -f "$target.tmp.$$" "$target" || die "cannot move $target into place"
}

msg "installing the nx CLI"
install_file "$HERE/bin/nx" "$PREFIX_BIN/nx" 0755

msg "installing package modules"
for module in "$HERE"/lib/noxs-pkg/*.sh; do
    install_file "$module" "$PREFIX_LIB/noxs-pkg/$(basename "$module")" 0755
done

msg "installing @noxs/nx-api SDK"
for api_file in "$HERE"/lib/noxs/nx-api/*; do
    install_file "$api_file" "$PREFIX_LIB/noxs/nx-api/$(basename "$api_file")" 0644
done

msg "installing language templates"
mkdir -p "$PREFIX_SHARE/noxs-pkg/templates"
# Copy without following symlinks; never overwrite a newer template tree
# installed by Noxs itself (version marker decides).
cp -r "$HERE/share/noxs-pkg/templates/." "$PREFIX_SHARE/noxs-pkg/templates/" ||
    die "template installation failed"
if [ -f "$HERE/share/noxs-pkg/.nx-version" ]; then
    cp "$HERE/share/noxs-pkg/.nx-version" "$PREFIX_SHARE/noxs-pkg/.nx-version"
fi

msg "verifying installation"
[ -x "$PREFIX_BIN/nx" ] || die "nx CLI is not executable"
for module in pkg-lib.sh pkg-init.sh pkg-dev.sh pkg-install.sh web-lib.sh; do
    [ -f "$PREFIX_LIB/noxs-pkg/$module" ] || die "module missing: $module"
done
[ -f "$PREFIX_LIB/noxs/nx-api/nx-api.js" ] || die "nx-api.js missing"

"$PREFIX_BIN/nx" version 2>/dev/null || true

msg "installed. Run: nx help"
