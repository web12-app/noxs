#!/usr/bin/env bash
# shellcheck disable=SC1090,SC1091,SC2015,SC2016,SC2034,SC2086,SC2164
# test_nx_env.sh — end-to-end tests for `nx env` (Multi-Env Manager CLI).
#
# Drives the REAL dispatcher + env-lib.sh against a fake Noxs host:
# a temp directory that plays /var/run/noxs/host/env (registry.txt,
# providers.txt, requests/, responses/) plus a background responder that
# mimics NoxsEnvBridge. Covers usage, listing, validation, the full
# install handshake with password + live progress, remove (confirm y/N)
# and use.
#
# Usage: scripts/test_nx_env.sh
set -u
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
RC=0
pass() { echo "  ok: $*"; }
fail() { echo "  FAILED: $*" >&2; RC=1; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
HOST="$TMP/host/env"
mkdir -p "$HOST/requests" "$HOST/responses"

# Real nx dispatcher + real env-lib.sh (extracted mirrors).
NXBIN="$ROOT/linux-runtime/launcher/nx"
export NX_LIB_DIR="$ROOT/linux-runtime/nx"
export NX_ENV_HOST="$HOST"

# Fake host state ------------------------------------------------------------
write_registry() {
    printf '%s\n' "$@" > "$HOST/registry.txt"
}
write_providers() {
    printf '%s\n' "$@" > "$HOST/providers.txt"
}
write_providers \
    "$(printf 'debian\tDebian 12\tStable general-purpose Linux\tbookworm\tbookworm')" \
    "$(printf 'ubuntu\tUbuntu\tDeveloper-friendly Linux\tnoble\tnoble')" \
    "$(printf 'kali\tKali NetHunter Rootless\tSecurity-focused Linux userspace\tminimal,full,nano\tminimal')" \
    "$(printf 'parrot\tParrot OS\tSecurity/privacy-focused Linux\t\t')"
write_registry \
    "$(printf 'debian\tDebian 12\tready\tactive\t\t-1\t')"

respond() {
    # Background responder: answers each request file like NoxsEnvBridge and
    # can simulate install progress via registry rewrites.
    local req r id op a1 a2 a3
    for req in "$HOST/requests"/*; do
        [ -f "$req" ] || continue
        id="$(basename "$req")"
        op="$(sed -n '1p' "$req")"
        a1="$(sed -n '2p' "$req")"
        a2="$(sed -n '3p' "$req")"
        a3="$(sed -n '4p' "$req")"
        rm -f "$req"
        case "$op" in
            install)
                # Response first (CLI unblocks), then walk the registry
                # through installing → ready exactly like a real setup.
                # Windows stay > 2s so the CLI's 2s poll catches each state.
                printf 'OK\n%s\n' "$a1" > "$HOST/responses/$id"
                ( sleep 0.3
                  write_registry \
                      "$(printf 'debian\tDebian 12\tready\tactive\t\t-1\t')" \
                      "$(printf '%s\t%s\tinstalling\t-\tchecking\t-1\tPreparing' "$a1" 'Ubuntu')"
                  sleep 0.4
                  write_registry \
                      "$(printf 'debian\tDebian 12\tready\tactive\t\t-1\t')" \
                      "$(printf '%s\t%s\tinstalling\t-\tdownloading\t42\tDownloading — 12.6 MB / 29.9 MB' "$a1" 'Ubuntu')"
                  sleep 2.5
                  write_registry \
                      "$(printf 'debian\tDebian 12\tready\tactive\t\t-1\t')" \
                      "$(printf '%s\t%s\tready\t-\tready\t100\tReady' "$a1" 'Ubuntu')"
                ) &
                ;;
            remove)
                printf 'OK\nremoved\n' > "$HOST/responses/$id"
                ( sleep 0.2; write_registry "$(printf 'debian\tDebian 12\tready\tactive\t\t-1\t')" ) &
                ;;
            use)
                printf 'OK\nactive\n' > "$HOST/responses/$id"
                ;;
            *)
                printf 'ERR\nUnsupported environment request\n' > "$HOST/responses/$id"
                ;;
        esac
    done
}

run_cli() {
    # Runs the CLI for up to 20s, pumping the responder in the background.
    local out rc=0 input="$1"; shift
    out="$(timeout 20 "$NXBIN" env "$@" < "$input" 2>&1)" || rc=$?
    OUT="$out"
    RC_C="$rc"
}

# ---- 1. usage --------------------------------------------------------------
echo "=== nx env: usage + dispatcher ==="
run_cli /dev/null
[ "$RC_C" -eq 0 ] && pass "bare 'nx env' exits 0" || fail "bare 'nx env' exit=$RC_C"
printf '%s' "$OUT" | grep -q "usage: nx env" && pass "bare 'nx env' prints usage" || fail "bare 'nx env' usage missing"
run_cli /dev/null frobnicate
[ "$RC_C" -eq 2 ] && pass "unknown subcommand exits 2" || fail "unknown subcommand exit=$RC_C"
printf '%s' "$OUT" | grep -q "Unknown nx env command: frobnicate" && pass "unknown subcommand message" || fail "unknown subcommand message missing"

# ---- 2. list ---------------------------------------------------------------
echo "=== nx env: list ==="
run_cli /dev/null list
[ "$RC_C" -eq 0 ] && pass "list exits 0" || fail "list exit=$RC_C"
printf '%s' "$OUT" | grep -q '\* debian' && pass "list marks active env" || fail "active marker missing"
printf '%s' "$OUT" | grep -q "Debian 12" && pass "list shows display name" || fail "display name missing"
printf '%s' "$OUT" | grep -q "Available providers" && pass "list shows providers" || fail "providers missing"
printf '%s' "$OUT" | grep -q "not installable here" && pass "list flags empty-variant provider" || fail "parrot flag missing"
printf '%s' "$OUT" | grep -q "variants: minimal,full,nano" && pass "list shows variants" || fail "variants missing"

# ---- 3. install validation --------------------------------------------------
echo "=== nx env: install validation ==="
run_cli /dev/null install nosuch
[ "$RC_C" -eq 2 ] && pass "unknown provider exits 2" || fail "unknown provider exit=$RC_C"
printf '%s' "$OUT" | grep -q "Unknown provider: nosuch" && pass "unknown provider message" || fail "unknown provider message missing"
run_cli /dev/null install parrot
[ "$RC_C" -eq 2 ] && pass "non-installable provider exits 2" || fail "parrot exit=$RC_C"
run_cli /dev/null install ubuntu badvariant
[ "$RC_C" -eq 2 ] && pass "unknown variant exits 2" || fail "bad variant exit=$RC_C"
printf '%s' "$OUT" | grep -q "Unknown variant 'badvariant'" && pass "unknown variant message" || fail "variant message missing"

# ---- 4. full install handshake ----------------------------------------------
echo "=== nx env: install e2e ==="
printf 'secret1\nsecret1\n' > "$TMP/pwstdin"
( while :; do respond; sleep 0.05; done ) &
RESPONDER_PID=$!
run_cli "$TMP/pwstdin" install ubuntu
kill "$RESPONDER_PID" 2>/dev/null; wait "$RESPONDER_PID" 2>/dev/null
[ "$RC_C" -eq 0 ] && pass "install completes" || { fail "install exit=$RC_C"; printf '%s\n' "$OUT" | tail -5; }
printf '%s' "$OUT" | grep -q "Password: " && pass "password prompt shown" || fail "password prompt missing"
printf '%s' "$OUT" | grep -q "downloading (42%) Downloading" && pass "progress line with percent" || fail "progress line missing: $(printf '%s' "$OUT" | grep nx: | tail -3)"
printf '%s' "$OUT" | grep -q "Environment Ubuntu is ready." && pass "ready banner" || fail "ready banner missing"
printf '%s' "$OUT" | grep -q "nx env use ubuntu" && pass "switch hint" || fail "switch hint missing"
# The request carried the password on line 4 and was consumed.
[ -z "$(ls -A "$HOST/requests" 2>/dev/null)" ] && pass "request files consumed" || fail "request files left behind"

# password mismatch
write_registry "$(printf 'debian\tDebian 12\tready\tactive\t\t-1\t')"
printf 'abc1\nabc2\n' > "$TMP/pwstdin"
run_cli "$TMP/pwstdin" install kali minimal
[ "$RC_C" -eq 1 ] && pass "password mismatch cancels" || fail "mismatch exit=$RC_C"
printf '%s' "$OUT" | grep -q "Passwords do not match" && pass "mismatch message" || fail "mismatch message missing"

# short password
printf 'abc\nabc\n' > "$TMP/pwstdin"
run_cli "$TMP/pwstdin" install kali minimal
printf '%s' "$OUT" | grep -q "Password too short" && pass "short password rejected" || fail "short password missing"

# ---- 5. remove ----------------------------------------------------------------
echo "=== nx env: remove ==="
write_registry \
    "$(printf 'debian\tDebian 12\tready\tactive\t\t-1\t')" \
    "$(printf 'ubuntu\tUbuntu\tready\t-\tready\t100\tReady')"
printf 'n\n' > "$TMP/ans"
run_cli "$TMP/ans" remove ubuntu
[ "$RC_C" -eq 1 ] && pass "declined remove exits 1" || fail "declined remove exit=$RC_C"
printf '%s' "$OUT" | grep -q "Cancelled" && pass "declined remove message" || fail "declined message missing"
printf '%s' "$OUT" | grep -q "deleted permanently" && pass "remove warning shown" || fail "remove warning missing"

printf 'y\n' > "$TMP/ans"
( while :; do respond; sleep 0.05; done ) &
RESPONDER_PID=$!
run_cli "$TMP/ans" remove ubuntu
kill "$RESPONDER_PID" 2>/dev/null; wait "$RESPONDER_PID" 2>/dev/null
[ "$RC_C" -eq 0 ] && pass "confirmed remove succeeds" || { fail "remove exit=$RC_C"; printf '%s\n' "$OUT" | tail -3; }
printf '%s' "$OUT" | grep -q "Environment ubuntu removed." && pass "removed message" || fail "removed message missing"

run_cli /dev/null remove ghost
[ "$RC_C" -eq 2 ] && pass "removing unknown id exits 2" || fail "ghost remove exit=$RC_C"
printf '%s' "$OUT" | grep -q "not installed" && pass "unknown id message" || fail "ghost message missing"

# ---- 6. use --------------------------------------------------------------------
echo "=== nx env: use ==="
write_registry \
    "$(printf 'debian\tDebian 12\tready\tactive\t\t-1\t')" \
    "$(printf 'ubuntu\tUbuntu\tready\t-\tready\t100\tReady')"
( while :; do respond; sleep 0.05; done ) &
RESPONDER_PID=$!
run_cli /dev/null use ubuntu
kill "$RESPONDER_PID" 2>/dev/null; wait "$RESPONDER_PID" 2>/dev/null
[ "$RC_C" -eq 0 ] && pass "use succeeds" || fail "use exit=$RC_C"
printf '%s' "$OUT" | grep -q "Active environment: ubuntu" && pass "use message" || fail "use message missing"
printf '%s' "$OUT" | grep -q "after Noxs restarts" && pass "restart note" || fail "restart note missing"

run_cli /dev/null use ghost
printf '%s' "$OUT" | grep -q "not installed" && pass "use unknown id message" || fail "use ghost message missing"
write_registry \
    "$(printf 'debian\tDebian 12\tready\tactive\t\t-1\t')" \
    "$(printf 'kali\tKali\tinstalling\t-\tdownloading\t10\tDownloading')"
run_cli /dev/null use kali
[ "$RC_C" -eq 2 ] && pass "use non-ready env exits 2" || fail "use installing exit=$RC_C"
printf '%s' "$OUT" | grep -q "not ready yet" && pass "non-ready message" || fail "non-ready message missing"

# ---- 7. bridge unavailable ------------------------------------------------------
echo "=== nx env: bridge unavailable ==="
export NX_ENV_HOST="$TMP/missing"
run_cli /dev/null list
[ "$RC_C" -eq 1 ] && pass "list without bridge exits 1" || fail "no-bridge list exit=$RC_C"
printf '%s' "$OUT" | grep -q "bridge is unavailable" && pass "no-bridge message" || fail "no-bridge message missing"

echo
if [ "$RC" -eq 0 ]; then
    echo "ALL nx env TESTS PASSED"
else
    echo "nx env TESTS FAILED"
fi
exit "$RC"
