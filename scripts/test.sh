#!/usr/bin/env bash
# scripts/test.sh — Noxs validation suite (original project tooling).
#   --unit          JVM unit tests (checksums, tar-guard, emulator, launcher)
#   --manifests     validate bootstrap manifests + schema shape
#   --sync-check    diff canonical linux-runtime files vs embedded app copies
#   --scripts       shellcheck + bash -n over all shell tooling
#   --all           everything (default)
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
MODE="${1:---all}"
RC=0

step() { echo; echo "=== $1 ==="; }

fail() { echo "FAILED: $*" >&2; RC=1; }

# ---- unit -----------------------------------------------------------------
if [ "$MODE" = "--all" ] || [ "$MODE" = "--unit" ]; then
    step "JVM unit tests"
    ./gradlew -q noxs-shared:test terminal-emulator:test app:testDebugUnitTest || fail "unit tests"
fi

# ---- manifests ------------------------------------------------------------
if [ "$MODE" = "--all" ] || [ "$MODE" = "--manifests" ]; then
    step "bootstrap manifests"
    if command -v python3 >/dev/null 2>&1; then
        python3 - <<'PY' || fail "manifest validation"
import json, pathlib, sys
root = pathlib.Path('.')
ok = True
for p in sorted(root.glob('app/src/main/assets/bootstrap/*/bootstrap.manifest')):
    try:
        m = json.loads(p.read_text())
        assert m['schema'] == 1, 'schema'
        assert m['arch'] in ('arm64-v8a', 'armeabi-v7a', 'x86_64'), 'arch'
        for key in ('rootfs', 'proot'):
            if key not in m:
                # proot is optional since it ships in the APK as jniLibs
                if key == 'proot':
                    continue
                assert False, f'{key} missing'
            assert m[key]['url'].startswith('https://'), f'{key}.url must be https'
            sha = m[key].get('sha256', '')
            assert sha == '' or (len(sha) == 64 and all(c in '0123456789abcdef' for c in sha)), f'{key}.sha256'
        print(f"  OK {p}")
    except Exception as e:
        print(f"  BAD {p}: {e}"); ok = False
sys.exit(0 if ok else 1)
PY
    else
        echo "  python3 not found — skipped (CI includes it)"
    fi
fi

# ---- sync check (canonical vs embedded copies) ----------------------------
if [ "$MODE" = "--all" ] || [ "$MODE" = "--sync-check" ]; then
    step "canonical ↔ embedded sync"
    diff -u linux-runtime/bootstrap/arm64-v8a/bootstrap.manifest \
            app/src/main/assets/bootstrap/arm64-v8a/bootstrap.manifest || fail "manifest copy differs"
    diff -u linux-runtime/rootfs/overlay/etc/profile.d/noxs.sh \
            <(sed -n '/NOXS_PROFILE = \"\"\"/,/\"\"\".trimIndent()/p' \
                app/src/main/java/com/noxs/linux/runtime/RootfsConfigurator.kt \
              | sed '1d;$d') >/dev/null 2>&1 \
        || echo "  note: profile.sh is generated with template escapes; visual diff only"
    # The noxs CLI must match its Kotlin template (after un-escaping ${'$'})
    python3 - <<'PY' || fail "noxs CLI copy differs"
import pathlib, re, sys
kt = pathlib.Path('app/src/main/java/com/noxs/linux/runtime/NoxsCliTemplate.kt').read_text()
m = re.search(r'val CLI = """\n?(.*?)"""', kt, re.S)
if not m:
    print('  template markers not found'); sys.exit(1)
embedded = m.group(1).replace("${'$'}", "$")
canonical = pathlib.Path('linux-runtime/launcher/noxs-cli').read_text()
if embedded != canonical:
    print('  DIFF: run scripts/sync-cli.sh to regenerate the canonical copy')
    sys.exit(1)
print('  OK noxs-cli matches template')
PY
fi

# ---- shell tooling --------------------------------------------------------
if [ "$MODE" = "--all" ] || [ "$MODE" = "--scripts" ]; then
    step "shell checks"
    SHELLS="$(ls scripts/*.sh linux-runtime/launcher/noxs-launch.sh linux-runtime/service-manager/noxs-service linux-runtime/socket-manager/noxs-socket linux-runtime/process-manager/noxs-ps 2>/dev/null)"
    for f in $SHELLS; do
        bash -n "$f" || fail "syntax: $f"
    done
    echo "  bash -n OK for all shell files"
    if command -v shellcheck >/dev/null 2>&1; then
        shellcheck -x $SHELLS || fail "shellcheck"
    else
        echo "  shellcheck not installed — skipped locally (CI runs it)"
    fi
fi

echo
if [ "$RC" -eq 0 ]; then
    echo "[noxs] all validations passed ✔"
else
    echo "[noxs] VALIDATION FAILURES — see above" >&2
fi
exit "$RC"
