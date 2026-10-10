#!/bin/sh
# cert-fix.sh — nx cert-fix: repair the APT package layer of this Noxs
# Linux environment (CA certificates, package lists, curl/wget) without
# reinstalling anything. Part of the Noxs NX Package System.

CF_SOURCES="/etc/apt/sources.list.d/noxs.sources"
CF_LOG_DIR="${HOME:-/root}/.noxs/logs"
CF_BOX_WIDTH=62
CF_BOX_ROWS=7
CF_BOX=""
CF_LOG=""

cf_hline() { printf '%*s' "$1" '' | tr ' ' '-'; }

cf_box_render() {
    [ -t 1 ] || return 0
    printf '\033[s'
    printf '+-%s-+\n' "$(cf_hline "$CF_BOX_WIDTH")"
    printf '| %-*s |\n' "$CF_BOX_WIDTH" "NOXS - CERT-FIX LIVE LOG"
    printf '+-%s-+\n' "$(cf_hline "$CF_BOX_WIDTH")"
    if [ -f "$CF_BOX" ]; then
        tail -n "$CF_BOX_ROWS" "$CF_BOX" | cut -c1-"$CF_BOX_WIDTH" | while IFS= read -r cf_r_line; do
            printf '| %-*s |\n' "$CF_BOX_WIDTH" "$cf_r_line"
        done
        cf_r_count=$(tail -n "$CF_BOX_ROWS" "$CF_BOX" | wc -l)
    else
        cf_r_count=0
    fi
    while [ "$cf_r_count" -lt "$CF_BOX_ROWS" ]; do
        printf '| %-*s |\n' "$CF_BOX_WIDTH" ''
        cf_r_count=$((cf_r_count + 1))
    done
    printf '+-%s-+\n' "$(cf_hline "$CF_BOX_WIDTH")"
    printf '| %-*s |\n' "$CF_BOX_WIDTH" "TXT: $CF_LOG"
    printf '+-%s-+\n' "$(cf_hline "$CF_BOX_WIDTH")"
    printf '\033[u'
}

cf_log() {
    cf_level="$1"
    shift
    cf_line="[$(date '+%H:%M:%S')] [$cf_level] $*"
    printf '%s\n' "$cf_line" >> "$CF_LOG"
    printf '%s\n' "$cf_line" >> "$CF_BOX"
    if [ -t 1 ]; then
        cf_box_render
    else
        printf '%s\n' "$cf_line"
    fi
}

cf_root() {
    if [ "$(id -u)" = "0" ]; then
        "$@"
    elif command -v sudo >/dev/null 2>&1; then
        sudo "$@"
    else
        cf_log ERROR "need root: run from a root shell or install sudo"
        return 127
    fi
}

cf_pkg_busy() {
    [ -f /run/noxs/pkg-tx ] && return 0
    pgrep -x dpkg >/dev/null 2>&1 && return 0
    pgrep -x apt-get >/dev/null 2>&1 && return 0
    pgrep -x apt >/dev/null 2>&1 && return 0
    return 1
}

# cf_try <label> <max-attempts> <command> [args...]
# Bounded, cancellable-by-identical-failure command runner. Full output
# lands in the TXT log; the last error lines reach the live box.
cf_try() {
    cf_t_label="$1"
    cf_t_max="$2"
    shift 2
    cf_t_attempt=1
    cf_t_prev=''
    while [ "$cf_t_attempt" -le "$cf_t_max" ]; do
        cf_t_out="$(mktemp "$CF_LOG_DIR/try-XXXXXX")" || return 1
        cf_log INFO "$cf_t_label (attempt $cf_t_attempt/$cf_t_max)"
        cf_root "$@" > "$cf_t_out" 2>&1
        cf_t_rc=$?
        cat "$cf_t_out" >> "$CF_LOG"
        if [ "$cf_t_rc" -eq 0 ]; then
            rm -f "$cf_t_out"
            cf_log SUCCESS "$cf_t_label ok"
            return 0
        fi
        cf_t_sig="$(grep -E '^(Err:|E:|W:)' "$cf_t_out" | head -n 2 | tr '\n' ' ' | cut -c1-140)"
        if [ -z "$cf_t_sig" ]; then
            cf_t_sig="$(tail -n 2 "$cf_t_out" | tr '\n' ' ' | cut -c1-140)"
        fi
        cf_log ERROR "$cf_t_label failed (exit $cf_t_rc): $cf_t_sig"
        rm -f "$cf_t_out"
        if [ -n "$cf_t_prev" ] && [ "$cf_t_sig" = "$cf_t_prev" ]; then
            cf_log ERROR "$cf_t_label: the same failure repeated — further attempts cannot help"
            return 1
        fi
        cf_t_prev="$cf_t_sig"
        cf_t_attempt=$((cf_t_attempt + 1))
        if [ "$cf_t_attempt" -le "$cf_t_max" ]; then
            sleep 3
        fi
    done
    return 1
}

cf_transport() {
    [ -f "$CF_SOURCES" ] && sed -n 's/^URIs:[[:space:]]*//p' "$CF_SOURCES" | head -n 1
}

# Debian metadata is GPG-authenticated regardless of transport, so the
# temporary HTTP fallback is safe; TLS is restored at the end.
cf_use_http() { cf_root sed -i 's|https://|http://|g' "$CF_SOURCES"; }
cf_use_https() { cf_root sed -i 's|http://|https://|g' "$CF_SOURCES"; }

# EXIT trap: drop the box scratch file and the transaction flag WE armed
# (never a flag owned by the app side), restore the cursor.
cf_on_exit() {
    cf_e_rc=$?
    rm -f "$CF_BOX"
    if [ "${cf_owns_tx:-0}" = "1" ]; then
        rm -f /run/noxs/pkg-tx 2>/dev/null
    fi
    if [ -t 1 ]; then
        printf '\033[u\033[J'
    fi
    return "$cf_e_rc"
}

cf_apt_update() {
    cf_try "$1" "${2:-2}" /usr/bin/apt-get update \
        -o Acquire::Retries=1 \
        -o Acquire::http::Timeout=20 \
        -o Acquire::https::Timeout=20
}

cert_fix_help() {
    cat <<'EOF'
usage: nx cert-fix

Repairs the APT package layer of this Noxs environment:
  1. refreshes the installed CA certificate bundle
  2. apt-get update — falls back to signed HTTP when TLS fails
  3. installs ca-certificates, debian-archive-keyring, curl and wget
  4. regenerates the CA bundle and re-verifies HTTPS
  5. verifies curl/wget and the package metadata

Use it when apt cannot find packages ("Unable to locate package ..."),
when downloads fail with certificate errors, or after a setup that kept
failing. A full TXT log is written to ~/.noxs/logs/.
EOF
}

cert_fix_cmd() {
    case "${1:-}" in
        -h|--help|help)
            cert_fix_help
            return 0
            ;;
        *)
            if [ "$#" -gt 0 ]; then
                printf 'nx cert-fix: unknown argument: %s\n' "$1" >&2
                cert_fix_help
                return 2
            fi
            ;;
    esac

    if ! mkdir -p "$CF_LOG_DIR" 2>/dev/null; then
        CF_LOG_DIR=/tmp
    fi
    CF_LOG="$CF_LOG_DIR/cert-fix-$(date +%Y%m%d-%H%M%S).txt"
    CF_BOX="$CF_LOG_DIR/cert-fix-$(date +%Y%m%d-%H%M%S).box"
    : > "$CF_LOG"
    : > "$CF_BOX"
    [ -t 1 ] && printf '\n'
    # shellcheck disable=SC2064  # expansion at trap-set time is fine: CF_BOX is final
    trap 'cf_on_exit' EXIT

    cf_log INFO "Noxs APT + certificate repair started"
    cf_log INFO "full log: $CF_LOG"

    if ! command -v apt-get >/dev/null 2>&1; then
        cf_log ERROR "apt-get is not available in this environment"
        return 2
    fi
    if [ ! -f "$CF_SOURCES" ]; then
        cf_log ERROR "$CF_SOURCES is missing — run the Noxs setup from the app once first"
        return 2
    fi
    if cf_pkg_busy; then
        cf_log ERROR "the Noxs package manager is busy — wait for the other apt/dpkg run and retry"
        return 1
    fi
    cf_owns_tx=0
    if cf_root sh -c 'mkdir -p /run/noxs && echo cert-fix > /run/noxs/pkg-tx' >/dev/null 2>&1; then
        cf_owns_tx=1
    fi

    # 1 — refresh whatever bundle already exists (best effort)
    if [ -x /usr/sbin/update-ca-certificates ]; then
        cf_log INFO "refreshing the existing CA bundle"
        cf_root /usr/sbin/update-ca-certificates >> "$CF_LOG" 2>&1 \
            || cf_log WARN "existing bundle refresh failed — continuing"
    else
        cf_log INFO "update-ca-certificates is not installed yet — skipping bundle refresh"
    fi

    # 2 — first update with the current transport
    cf_t0="$(cf_transport)"
    cf_log INFO "repository transport: ${cf_t0:-unknown}"
    cf_apt_ok=1
    if ! cf_apt_update "apt-get update" 2; then
        cf_apt_ok=0
    fi

    # 3 — signed-HTTP fallback when the HTTPS transport failed
    cf_switched=0
    if [ "$cf_apt_ok" != 1 ] && printf '%s' "$cf_t0" | grep -q '^https://'; then
        cf_log WARN "HTTPS failed — switching to signed HTTP for the repair"
        if ! cf_use_http; then
            cf_log ERROR "cannot rewrite $CF_SOURCES"
            return 3
        fi
        cf_switched=1
        if cf_apt_update "apt-get update over signed HTTP" 2; then
            cf_apt_ok=1
        else
            cf_use_https >/dev/null 2>&1 || true
            cf_log ERROR "apt-get update failed even over HTTP — check the device network"
            return 4
        fi
    elif [ "$cf_apt_ok" != 1 ]; then
        cf_log WARN "apt-get update failed — continuing with the package repair anyway"
    fi

    # 4 — certificate stack + the standard fetch tools (curl, wget)
    cf_pkg_ok=1
    if ! cf_try "install ca-certificates, keyring, curl, wget" 2 \
        env DEBIAN_FRONTEND=noninteractive /usr/bin/apt-get install -y \
            --no-install-recommends ca-certificates debian-archive-keyring curl wget; then
        cf_pkg_ok=0
    fi

    # 5 — regenerate the CA bundle
    cf_bundle_ok=0
    if [ -x /usr/sbin/update-ca-certificates ]; then
        if cf_try "regenerate the CA bundle" 1 /usr/sbin/update-ca-certificates --fresh; then
            cf_bundle_ok=1
        fi
    fi
    if [ -s /etc/ssl/certs/ca-certificates.crt ]; then
        cf_log INFO "CA bundle present: /etc/ssl/certs/ca-certificates.crt"
    else
        cf_log WARN "CA bundle file is missing or empty"
    fi

    # 6 — restore HTTPS and re-verify
    if [ "$cf_switched" = 1 ]; then
        cf_use_http_restore=0
        cf_use_https || cf_use_http_restore=1
        if [ "$cf_use_http_restore" = 0 ] && cf_apt_update "apt-get update over HTTPS" 2; then
            cf_log SUCCESS "HTTPS repositories verified"
        else
            cf_log WARN "HTTPS still failing — keeping signed HTTP (GPG authentication unchanged)"
            cf_use_http || true
            cf_apt_update "apt-get update over signed HTTP" 1 || true
        fi
    fi

    # 7 — final verification
    cf_fail=0
    if command -v curl >/dev/null 2>&1; then
        cf_log INFO "curl: $(curl --version 2>/dev/null | head -n 1 | cut -c1-46)"
    else
        cf_log ERROR "curl is still missing"
        cf_fail=1
    fi
    if command -v wget >/dev/null 2>&1; then
        cf_log INFO "wget: $(wget --version 2>/dev/null | head -n 1 | cut -c1-46)"
    else
        cf_log ERROR "wget is still missing"
        cf_fail=1
    fi
    if [ "$cf_pkg_ok" != 1 ]; then
        cf_fail=1
    fi
    if [ "$cf_bundle_ok" != 1 ]; then
        cf_fail=1
    fi
    cf_policy="$(/usr/bin/apt-cache policy ca-certificates 2>/dev/null | grep 'Candidate:' | head -n 1)"
    case "$cf_policy" in
        *'(none)'*|'')
            cf_log ERROR "APT metadata verification failed (no candidate for ca-certificates)"
            cf_fail=1
            ;;
        *)
            cf_log INFO "APT metadata: $cf_policy"
            ;;
    esac
    if ls /var/lib/apt/lists/*Packages* >/dev/null 2>&1; then
        cf_log INFO "package lists are populated"
    else
        cf_log ERROR "package lists are still empty — apt-get update did not persist"
        cf_fail=1
    fi

    if [ "$cf_fail" = 0 ]; then
        cf_log SUCCESS "repair completed — apt, certificates and fetch tools verified"
    else
        cf_log ERROR "repair finished with problems — read $CF_LOG"
    fi
    return "$cf_fail"
}
