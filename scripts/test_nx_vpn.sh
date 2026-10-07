#!/usr/bin/env bash
# shellcheck disable=SC1090,SC1091,SC2015,SC2016,SC2034,SC2086,SC2164
# test_nx_vpn.sh — offline tests for `nx vpn` (Tor over PRoot, VPN mode).
#
# No real Tor, apt, root or network needed: the tor binary, its config, the
# SOCKS5 listener, the profile.d path and every timeout are stubbed or
# overridden. The REAL dispatcher (linux-runtime/launcher/nx) and the REAL
# vpn-lib.sh mirror are driven end to end. Covers usage, unknown commands,
# the PRoot ConnLimit math, activate (idempotent torrc rewrite), status in
# all states, the global-proxy on/off lifecycle, stop, and both failure
# paths of the connectivity check.
#
# Usage: scripts/test_nx_vpn.sh
set -u
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
RC=0
pass() { echo "  ok: $*"; }
fail() { echo "  FAILED: $*" >&2; RC=1; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

NXBIN="$ROOT/linux-runtime/launcher/nx"
export NX_LIB_DIR="$ROOT/linux-runtime/nx"

# Stub tor binary: accepts --verify-config, otherwise "runs" as a daemon.
FAKE_TOR="$TMP/tor"
cat > "$FAKE_TOR" <<'STUB'
#!/usr/bin/env bash
if [ "${1:-}" = "--verify-config" ]; then exit 0; fi
exec sleep 15
STUB
chmod +x "$FAKE_TOR"

# A live TCP listener stands in for the Tor SOCKS5 port.
OPEN_PORT=19731
python3 - "$OPEN_PORT" <<'PY' &
import socket, sys, time
s = socket.socket()
s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
s.bind(("127.0.0.1", int(sys.argv[1])))
s.listen(8)
time.sleep(120)
PY
LISTENER=$!
# Wait for the listener to accept connections (slow CI runners).
i=0
until (exec 3<>"/dev/tcp/127.0.0.1/$OPEN_PORT") 2>/dev/null; do
    i=$((i + 1))
    if [ "$i" -ge 50 ]; then
        echo "  FAILED: test listener on port $OPEN_PORT never came up" >&2
        exit 1
    fi
    sleep 0.1
done

export NX_VPN_TORRC="$TMP/torrc"
export NX_VPN_PROFILE="$TMP/profile/noxs-tor-proxy.sh"
export NX_VPN_PORT="$OPEN_PORT"
export NX_VPN_TOR_BIN="$FAKE_TOR"
export NX_VPN_FD_LIMIT="512"
export NX_VPN_WAIT="3"
export NX_VPN_TEST_TIMEOUT="2"
export NX_VPN_LOG="$TMP/tor-start.log"

vpn() { "$NXBIN" vpn "$@" 2>&1; }

# ---- 1. usage + dispatcher -------------------------------------------------
echo "=== nx vpn: usage + dispatcher ==="
out="$(vpn help)"; rc=$?
[ "$rc" -eq 0 ] && pass "'nx vpn help' exits 0" || fail "'nx vpn help' exit=$rc"
printf '%s' "$out" | grep -q "usage: nx vpn" && pass "usage text shown" || fail "usage text missing"
out="$(vpn frobnicate)"; rc=$?
[ "$rc" -eq 2 ] && pass "unknown subcommand exits 2" || fail "unknown subcommand exit=$rc"
printf '%s' "$out" | grep -q "Unknown nx vpn command: frobnicate" && pass "unknown subcommand message" || fail "unknown subcommand message missing"

# ---- 2. ConnLimit math (PRoot file-descriptor safety) -----------------------
echo "=== nx vpn: ConnLimit math ==="
limit_of() {
    ( export NX_VPN_FD_LIMIT="$1"; . "$NX_LIB_DIR/vpn-lib.sh"; nx_vpn_conn_limit )
}
[ "$(limit_of 512)" = "400" ] && pass "512 fd -> ConnLimit 400" || fail "512 fd limit math"
[ "$(limit_of 1024)" = "960" ] && pass "1024 fd -> ConnLimit 960" || fail "1024 fd limit math"
[ "$(limit_of 100)" = "128" ] && pass "100 fd -> clamped to 128" || fail "100 fd clamp"
[ "$(limit_of garbage)" = "400" ] && pass "garbage fd falls back to 512->400" || fail "garbage fd fallback"

# ---- 3. activate (bare 'nx vpn' == start) ----------------------------------
echo "=== nx vpn: activate ==="
printf 'SocksPort 9050\nConnLimit 9999\n' > "$NX_VPN_TORRC"
out="$(vpn)"; rc=$?
[ "$rc" -eq 0 ] && pass "bare 'nx vpn' activates" || { fail "activate exit=$rc"; printf '%s\n' "$out" | tail -5; }
printf '%s' "$out" | grep -q "Tor is READY" && pass "READY summary shown" || fail "READY missing"
printf '%s' "$out" | grep -q "SOCKS5 : 127.0.0.1:$OPEN_PORT" && pass "SOCKS5 endpoint shown" || fail "SOCKS5 endpoint missing"
grep -q "^ConnLimit 9999$" "$NX_VPN_TORRC" && fail "old active ConnLimit survived" || pass "old active ConnLimit neutralized"
grep -q "^ConnLimit 400$" "$NX_VPN_TORRC" && pass "managed ConnLimit 400 written" || fail "managed ConnLimit missing"
grep -q "Noxs PRoot file-descriptor compatibility" "$NX_VPN_TORRC" && pass "managed block marker present" || fail "marker missing"

# Idempotent re-run must not duplicate the managed block.
out="$(vpn start)"; rc=$?
[ "$rc" -eq 0 ] && pass "'nx vpn start' re-run exits 0" || fail "re-run exit=$rc"
[ "$(grep -c "Noxs PRoot file-descriptor compatibility" "$NX_VPN_TORRC")" = "1" ] \
    && pass "re-run does not duplicate the managed block" || fail "duplicated managed block"

# ---- 4. status --------------------------------------------------------------
echo "=== nx vpn: status ==="
out="$(vpn status)"; rc=$?
[ "$rc" -eq 0 ] && pass "status exits 0 when running" || fail "status running exit=$rc"
printf '%s' "$out" | grep -q "Tor: RUNNING" && pass "status reports RUNNING" || fail "RUNNING missing"
printf '%s' "$out" | grep -q "Global proxy: off" && pass "status shows proxy off initially" || fail "proxy-off missing"

# ---- 5. global proxy lifecycle ---------------------------------------------
echo "=== nx vpn: on / off ==="
out="$(vpn on)"; rc=$?
[ "$rc" -eq 0 ] && pass "'nx vpn on' exits 0" || fail "on exit=$rc"
[ -f "$NX_VPN_PROFILE" ] && pass "profile file written" || fail "profile file missing"
grep -q "export ALL_PROXY=socks5h://127.0.0.1:$OPEN_PORT" "$NX_VPN_PROFILE" \
    && pass "ALL_PROXY routed to SOCKS5" || fail "ALL_PROXY missing"
grep -q "export HTTPS_PROXY=socks5h://127.0.0.1:$OPEN_PORT" "$NX_VPN_PROFILE" \
    && pass "HTTPS_PROXY routed to SOCKS5" || fail "HTTPS_PROXY missing"
out="$(vpn status)"
printf '%s' "$out" | grep -q "Global proxy: ON" && pass "status shows proxy ON" || fail "proxy ON missing"
out="$(vpn stop)"
printf '%s' "$out" | grep -q "global proxy is still ON" && pass "stop warns about live proxy" || fail "stop warning missing"
out="$(vpn off)"; rc=$?
[ "$rc" -eq 0 ] && pass "'nx vpn off' exits 0" || fail "off exit=$rc"
[ ! -f "$NX_VPN_PROFILE" ] && pass "profile file removed" || fail "profile file still present"
out="$(vpn status)"
printf '%s' "$out" | grep -q "Global proxy: off" && pass "status shows proxy off again" || fail "proxy-off-after missing"

# ---- 6. not-ready + stopped failure paths -----------------------------------
echo "=== nx vpn: failure paths ==="
CLOSED_PORT=$((OPEN_PORT + 1))
out="$(NX_VPN_PORT="$CLOSED_PORT" vpn status)"; rc=$?
[ "$rc" -eq 1 ] && pass "status exits 1 when stopped" || fail "status stopped exit=$rc"
printf '%s' "$out" | grep -q "Tor: STOPPED" && pass "status reports STOPPED" || fail "STOPPED missing"
out="$(NX_VPN_PORT="$CLOSED_PORT" vpn test)"; rc=$?
[ "$rc" -eq 1 ] && pass "test exits 1 without listener" || fail "test no-listener exit=$rc"
printf '%s' "$out" | grep -q "not listening" && pass "test explains missing listener" || fail "no-listener message missing"
out="$(NX_VPN_PORT="$CLOSED_PORT" NX_VPN_WAIT="1" vpn start)"; rc=$?
[ "$rc" -eq 1 ] && pass "start exits 1 when SOCKS never opens" || fail "start timeout exit=$rc"
printf '%s' "$out" | grep -q "did not become ready" && pass "start timeout message" || fail "timeout message missing"
out="$(vpn test)"; rc=$?
[ "$rc" -eq 1 ] && pass "test exits 1 when the circuit check fails" || fail "test circuit exit=$rc"
printf '%s' "$out" | grep -q "did not succeed" && pass "test explains circuit failure" || fail "circuit message missing"

cleanup() { kill "$LISTENER" 2>/dev/null || true; pkill -x tor 2>/dev/null || true; }
cleanup

echo
if [ "$RC" -eq 0 ]; then
    echo "ALL nx vpn TESTS PASSED"
else
    echo "nx vpn TEST FAILURES — see above" >&2
fi
exit "$RC"
