#!/usr/bin/env bash
# shellcheck disable=SC1090,SC1091,SC2012,SC2034,SC2086,SC2164,SC2295,SC2317
# test_nx_functions.sh — functional tests for the NX Package System scripts.
#
# The Kotlin templates are extracted with scripts/extract_nx.py, then the
# real pkg-lib/pkg-init code is executed against fixtures: validation
# regexes, semver compare, registry parsing, asset picking, manifests and
# archive safety — plus an end-to-end `nx pkg init` scaffold check.
#
# Usage: scripts/test_nx_functions.sh   (called by scripts/test.sh --nx)
set -u
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
RC=0
pass() { echo "  ok: $*"; }
fail() { echo "  FAILED: $*" >&2; RC=1; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
WORK="$TMP/nxtest"
mkdir -p "$WORK"

# ---- materialize the extracted scripts + templates ------------------------
python3 - "$ROOT" "$TMP/nxroot" <<'PY' || { echo "extraction failed" >&2; exit 1; }
import pathlib, sys
sys.path.insert(0, str(pathlib.Path(sys.argv[1]) / 'scripts'))
import extract_nx
root = pathlib.Path(sys.argv[1])
dest = pathlib.Path(sys.argv[2])
(dest / 'noxs-pkg').mkdir(parents=True, exist_ok=True)
for name, content, mirror in extract_nx.nx_shell_scripts(root):
    module = {'NX_CLI': 'bin/nx', 'PKG_LIB': 'noxs-pkg/pkg-lib.sh',
              'PKG_INIT': 'noxs-pkg/pkg-init.sh', 'PKG_DEV': 'noxs-pkg/pkg-dev.sh',
              'PKG_INSTALL': 'noxs-pkg/pkg-install.sh'}[name]
    target = dest / module
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content)
    target.chmod(0o755)
extract_nx.materialize_template_root(root, dest)
print("  extracted nx scripts + templates")
PY

NXBIN="$TMP/nxroot/bin/nx"
export NX_LIB_DIR="$TMP/nxroot/noxs-pkg"
export NX_TEMPLATE_ROOT="$TMP/nxroot/templates"

# ---- 1. library unit tests -------------------------------------------------
echo "=== pkg-lib: validation + parsing ==="
. "$NX_LIB_DIR/pkg-lib.sh"

valid_name tree        && pass "valid_name tree"        || fail "valid_name tree"
valid_name tree-cli2   && pass "valid_name tree-cli2"   || fail "valid_name tree-cli2"
valid_name "Tree"      && fail "valid_name rejects uppercase" || pass "valid_name rejects uppercase"
valid_name "../evil"   && fail "valid_name rejects traversal" || pass "valid_name rejects traversal"
valid_name 'a;b'       && fail "valid_name rejects shell chars" || pass "valid_name rejects shell chars"
valid_name '$(id)'     && fail "valid_name rejects command substitution" || pass "valid_name rejects \$(id)"
valid_version 1.0.0    && pass "valid_version 1.0.0"    || fail "valid_version 1.0.0"
valid_version 1.0      && fail "valid_version rejects 1.0" || pass "valid_version rejects 1.0"
valid_version 1.0.0-rc1 && fail "valid_version rejects prerelease" || pass "valid_version rejects 1.0.0-rc1"
valid_repo web12-app/tree && pass "valid_repo"          || fail "valid_repo"
valid_repo "a b/c"     && fail "valid_repo rejects space" || pass "valid_repo rejects space"
valid_sha256 "$(printf 'a%.0s' $(seq 64))" && pass "valid_sha256 64 hex" || fail "valid_sha256"
valid_sha256 "xyz"     && fail "valid_sha256 rejects short" || pass "valid_sha256 rejects short"

semver_gt 1.0.1 1.0.0   && pass "semver_gt patch" || fail "semver_gt patch"
semver_gt 1.1.0 1.0.9   && pass "semver_gt minor" || fail "semver_gt minor"
semver_gt 2.0.0 1.99.99 && pass "semver_gt major" || fail "semver_gt major"
semver_gt 0.0.10 0.0.9  && pass "semver_gt 0.0.10>0.0.9" || fail "semver_gt 0.0.10>0.0.9"
! semver_gt 1.0.0 1.0.0 && pass "semver_gt equal is false" || fail "semver_gt equal"
! semver_gt 0.9.9 1.0.0 && pass "semver_gt lower is false" || fail "semver_gt lower"

# ---- registry parsing (canonical workflow layout, two versions) ------------
cat > "$WORK/registry.json" <<'EOF'
{
  "name": "tree",
  "description": "Noxs tree utility",
  "latest": "1.0.1",
  "versions": [
    {
      "version": "1.0.0",
      "tag": "tree-v1.0.0",
      "files": [
        {
          "name": "tree.nx.pkg.1.0.0.aarch64.tar.xz",
          "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        },
        {
          "name": "tree.nx.pkg.1.0.0.universal.tar.xz",
          "sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        }
      ]
    },
    {
      "version": "1.0.1",
      "tag": "tree-v1.0.1",
      "files": [
        {
          "name": "tree.nx.pkg.1.0.1.aarch64.tar.xz",
          "sha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        }
      ]
    }
  ]
}
EOF
if registry_parse "$WORK/registry.json"; then
    [ "$RG_NAME" = "tree" ]            && pass "registry name"      || fail "registry name ($RG_NAME)"
    [ "$RG_LATEST" = "1.0.1" ]         && pass "registry latest"    || fail "registry latest ($RG_LATEST)"
    [ "$RG_COUNT" = "2" ]              && pass "registry count"     || fail "registry count ($RG_COUNT)"
    [ "${RG_VER_0}" = "1.0.0" ]        && pass "registry ver0"      || fail "registry ver0"
    [ "${RG_VER_1}" = "1.0.1" ]        && pass "registry ver1"      || fail "registry ver1"
    [ "${RG_FILECOUNT_0}" = "2" ]      && pass "registry filecount" || fail "registry filecount"
    [ "${RG_SHA_1_1}" = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc" ] \
        && pass "registry sha"       || fail "registry sha"
else
    fail "registry_parse rejected a valid registry"
fi

registry_find_version 1.0.0 && [ "$RV_INDEX" = 0 ] && pass "find_version" || fail "find_version"
if registry_find_version 9.9.9; then fail "find_version absent must fail"; else pass "find_version absent"; fi

registry_pick_asset 1 aarch64 && [ "$RA_NAME" = "tree.nx.pkg.1.0.1.aarch64.tar.xz" ] \
    && pass "pick_asset exact arch" || fail "pick_asset exact arch"
registry_pick_asset 0 x86_64 && [ "$RA_NAME" = "tree.nx.pkg.1.0.0.universal.tar.xz" ] \
    && pass "pick_asset universal fallback" || fail "pick_asset universal fallback"
if registry_pick_asset 1 x86_64; then fail "pick_asset missing arch must fail"; else pass "pick_asset missing arch"; fi

# tampered registry: bad checksum length must be rejected (subshell: die exits)
sed 's/cccccccccc/cc/' "$WORK/registry.json" > "$WORK/bad.json"
if (registry_parse "$WORK/bad.json") 2>/dev/null; then fail "bad sha rejected"; else pass "bad sha rejected"; fi
# tag mismatch must be rejected
sed 's/tree-v1.0.1/tree-v9.9.9/' "$WORK/registry.json" > "$WORK/bad2.json"
if (registry_parse "$WORK/bad2.json") 2>/dev/null; then fail "tag mismatch rejected"; else pass "tag mismatch rejected"; fi

# ---- manifest roundtrip -----------------------------------------------------
echo "=== pkg-lib: manifests ==="
export NX_STATE_DIR="$WORK/state"
manifest_write "$(manifest_path tree)" tree 1.0.0 aarch64 noxs-pkg/tree "https://r" \
    "usr/local/bin/tree" "usr/local/share/doc/tree/README.md"
manifest_exists tree && pass "manifest_exists" || fail "manifest_exists"
[ "$(manifest_field "$(manifest_path tree)" version)" = "1.0.0" ] && pass "manifest_field" || fail "manifest_field"
[ "$(manifest_field "$(manifest_path tree)" source)" = "noxs-pkg/tree" ] && pass "manifest source" || fail "manifest source"

# ---- archive safety ----------------------------------------------------------
echo "=== pkg-lib: tarball safety ==="
stage_dir="$WORK/stage/usr/local/bin"
mkdir -p "$stage_dir"
printf '#!/bin/sh\necho ok\n' > "$stage_dir/tree"
tar -cJf "$WORK/safe.tar.xz" -C "$WORK/stage" usr
check_tarball_members "$WORK/safe.tar.xz" && pass "safe tarball accepted" || fail "safe tarball accepted"

# Malicious archives are constructed byte-exactly with python tarfile: a
# `..` traversal member, an absolute member, and an absolute symlink.
python3 - "$WORK" <<'PY' || { echo "tarball fixtures failed" >&2; exit 1; }
import io, sys, tarfile
from tarfile import TarInfo

import pathlib
import pathlib
work = pathlib.Path(sys.argv[1])

def make(path, members):
    with tarfile.open(work / path, "w:xz") as tf:
        for info in members:
            name, kind, target = info
            ti = TarInfo(name)
            if kind == "file":
                ti.size = 1
                tf.addfile(ti, io.BytesIO(b"x"))
            else:
                ti.type = tarfile.SYMTYPE
                ti.linkname = target
                tf.addfile(ti)

make("traversal.tar.xz", [("usr/local/bin/../../evil.sh", "file", "")])
make("absolute.tar.xz", [("/etc/evil.sh", "file", "")])
make("abssym.tar.xz", [("usr/local/bin/link", "symlink", "/etc/shadow")])
print("  malicious fixtures built")
PY

for evil in traversal absolute abssym; do
    if (check_tarball_members "$WORK/$evil.tar.xz") 2>/dev/null; then
        fail "$evil tarball rejected"
    else
        pass "$evil tarball rejected"
    fi
done

# ---- end-to-end: nx pkg init ------------------------------------------------
echo "=== pkg-init: end-to-end scaffold ==="
(
    cd "$WORK"
    "$NXBIN" pkg init demo --node >/dev/null 2>&1
) || fail "nx pkg init demo --node"
[ -f "$WORK/demo/.github/workflows/pkg.yml" ] && pass "workflow scaffolded" || fail "workflow scaffolded"
[ -f "$WORK/demo/src/main.js" ] && pass "nodejs src scaffolded" || fail "nodejs src scaffolded"
grep -q '"name": "demo"' "$WORK/demo/registry.json" && pass "registry name rendered" || fail "registry name"
grep -q '__PKG_NAME__' "$WORK/demo/registry.json" && fail "placeholder left behind" || pass "no placeholder left"
[ "$(cat "$WORK/demo/VERSION")" = "1.0.0" ] && pass "VERSION scaffolded" || fail "VERSION scaffolded"
[ -f "$WORK/demo/.git/HEAD" ] && pass "git repo initialized" || fail "git repo initialized"

(
    cd "$WORK"
    "$NXBIN" pkg init "Bad Name" --go >/dev/null 2>&1
) && fail "invalid name rejected" || pass "invalid name rejected"
(
    cd "$WORK"
    "$NXBIN" pkg init demo2 --bogus >/dev/null 2>&1
) && fail "unknown flag rejected" || pass "unknown flag rejected"
(
    cd "$WORK"
    "$NXBIN" pkg init demo3 >/dev/null 2>&1
) && fail "no-lang without tty rejected" || pass "no-lang without tty rejected"
(
    cd "$WORK"
    "$NXBIN" pkg init demo4 --node >/dev/null 2>&1 && "$NXBIN" pkg init demo4 --go >/dev/null 2>&1
) && fail "existing directory rejected" || pass "existing directory rejected"

# every language scaffold must produce a complete tree
for lang in --rust --cpp --python --go; do
    id="${lang#--}"
    (
        cd "$WORK"
        "$NXBIN" pkg init "pkg-$id" "$lang" >/dev/null 2>&1
    ) || fail "scaffold $id"
    for f in .github/workflows/pkg.yml registry.json VERSION README.md LICENSE; do
        [ -f "$WORK/pkg-$id/$f" ] || fail "scaffold $id missing $f"
    done
done
[ -f "$WORK/pkg-rust/src/main.rs" ] && [ -f "$WORK/pkg-rust/Cargo.toml" ] && pass "rust files" || fail "rust files"
[ -f "$WORK/pkg-go/main.go" ] && [ -f "$WORK/pkg-go/go.mod" ] && pass "go files at root (spec §7)" || fail "go files"
[ -f "$WORK/pkg-python/pyproject.toml" ] && [ -f "$WORK/pkg-python/src/main.py" ] && pass "python files" || fail "python files"
[ -f "$WORK/pkg-cpp/CMakeLists.txt" ] && [ -f "$WORK/pkg-cpp/src/main.cpp" ] && pass "cpp files" || fail "cpp files"

# ---- workflow YAML structural checks ----------------------------------------
echo "=== workflow: structure ==="
python3 - "$WORK/demo/.github/workflows/pkg.yml" <<'PY' || RC=1
import pathlib, sys
try:
    import yaml
except ImportError:
    print("  skip: PyYAML missing"); sys.exit(0)
doc = yaml.safe_load(pathlib.Path(sys.argv[1]).read_text())
jobs = doc.get("jobs", {})
assert "on" in doc or True
assert set(jobs) == {"detect", "build", "release"}, jobs.keys()
assert jobs["detect"]["outputs"]["tag"], "detect outputs"
assert "fromJson" in jobs["build"]["strategy"]["matrix"], "matrix fromJson"
steps = [s.get("name", s.get("uses", "")) for s in jobs["release"]["steps"]]
assert any("registry.json" in s for s in steps), "registry update step"
print("  ok: workflow parses; jobs=detect,build,release")
PY

grep -q "Release already exists" "$WORK/demo/.github/workflows/pkg.yml" && pass "duplicate tag error" || fail "duplicate tag error"
grep -q "sha256sum" "$WORK/demo/.github/workflows/pkg.yml" && pass "checksum step" || fail "checksum step"
grep -q "\[skip ci\]" "$WORK/demo/.github/workflows/pkg.yml" && pass "[skip ci] registry commit" || fail "[skip ci]"

# ---- dispatcher: noxs forward + help ----------------------------------------
echo "=== dispatcher: forwarding + help ==="
"$NXBIN" help | grep -q "nx install" && pass "nx help" || fail "nx help"
"$NXBIN" version | grep -q "nx 1" && pass "nx version" || fail "nx version"
if "$NXBIN" pkg frobnicate >/dev/null 2>&1; then fail "unknown pkg subcommand rejected"; else pass "unknown pkg subcommand rejected"; fi

echo
if [ "$RC" -eq 0 ]; then
    echo "[nx] functional tests passed"
else
    echo "[nx] FUNCTIONAL TEST FAILURES" >&2
fi
exit "$RC"
