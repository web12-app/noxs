/*
 * Noxs — original implementation.
 * Canonical env-lib.sh for `nx env` (Multi-Env Manager CLI).
 * Mirrored at linux-runtime/nx/env-lib.sh — CI sync-checks both copies
 * (scripts/sync_nx.py via extract_nx.py).
 *
 * Transport (same proven pattern as web-lib.sh / NoxsEnvBridge):
 *   /var/run/noxs/host/env/requests/<id>    guest writes, host consumes
 *   /var/run/noxs/host/env/responses/<id>   host writes "OK"/"ERR" + message
 *   /var/run/noxs/host/env/registry.txt     host-written environment snapshot
 *   /var/run/noxs/host/env/providers.txt    host-written provider snapshot
 *
 * Encoding convention: inside the raw string a literal bash `$` is written
 * as `§` and expanded by .replace('§', '$').
 */
package com.crossberry.noxs.runtime

object NoxsNxEnvLib {

    val ENV_LIB = """# env-lib.sh — nx env support (Noxs Multi-Env Manager CLI)
# shellcheck shell=bash
# Sourced by the nx dispatcher. Needs only coreutils (awk/cut/sed/grep).

NX_ENV_HOST="§{NX_ENV_HOST:-/var/run/noxs/host/env}"

# Self-contained messaging (the dispatcher usually defines these already).
type nx_err >/dev/null 2>&1 || nx_err() { printf 'nx: %s\n' "§*" >&2; }

nx_env_usage() {
    cat <<'EOF'
usage: nx env
       nx env list
       nx env install <provider> [variant]
       nx env remove <id>
       nx env use <id>

Manage multiple Linux environments from the terminal (Multi-Env Manager).

  nx env list                 Installed environments + installable providers
  nx env install ubuntu       Install a provider (default variant, asks y/N)
  nx env install kali minimal Install a specific variant
  nx env remove <id>          Remove an installed environment (asks y/N)
  nx env use <id>             Set the active environment (applies on restart)

Installs download the official image and keep running in the background —
stay attached to watch progress, or press Ctrl+C to detach and check
'nx env list' later. The Android notification also shows progress.
EOF
}

# ------------------------------------------------------------------ listing

nx_env_list_providers() {
    local prov="§NX_ENV_HOST/providers.txt"
    [ -f "§prov" ] || return 0
    printf '\nAvailable providers (nx env install <id>):\n'
    local line id name desc variants
    while IFS= read -r line || [ -n "§line" ]; do
        [ -n "§line" ] || continue
        id="§(printf '%s' "§line" | cut -f1)"
        name="§(printf '%s' "§line" | cut -f2)"
        desc="§(printf '%s' "§line" | cut -f3)"
        variants="§(printf '%s' "§line" | cut -f4)"
        if [ -z "§variants" ]; then
            printf '  %-8s %-26s %s (not installable here)\n' "§id" "§name" "§desc"
        else
            printf '  %-8s %-26s %s [variants: %s]\n' "§id" "§name" "§desc" "§variants"
        fi
    done < "§prov"
}

nx_env_list() {
    if [ ! -d "§NX_ENV_HOST" ]; then
        nx_err "Noxs environment bridge is unavailable; start Noxs and open a terminal"
        return 1
    fi
    local reg="§NX_ENV_HOST/registry.txt"
    if [ ! -f "§reg" ]; then
        printf 'No environments installed yet.\n'
        nx_env_list_providers
        return 0
    fi
    printf 'Environments:\n'
    local line id name status active state prog op marker detail
    while IFS= read -r line || [ -n "§line" ]; do
        [ -n "§line" ] || continue
        id="§(printf '%s' "§line" | cut -f1)"
        name="§(printf '%s' "§line" | cut -f2)"
        status="§(printf '%s' "§line" | cut -f3)"
        active="§(printf '%s' "§line" | cut -f4)"
        state="§(printf '%s' "§line" | cut -f5)"
        prog="§(printf '%s' "§line" | cut -f6)"
        op="§(printf '%s' "§line" | cut -f7)"
        marker=" "
        [ "§active" = "active" ] && marker="*"
        detail="§status"
        case "§state" in
            downloading|extracting|preparing|checking|verifying|configuring|creating_user|configuring_shell|installing_optional_components)
                if [ "§prog" -ge 0 ] 2>/dev/null; then
                    detail="installing §prog% — §op"
                else
                    detail="installing — §op"
                fi
                ;;
        esac
        printf ' %s %-8s %-26s %s\n' "§marker" "§id" "§name" "§detail"
    done < "§reg"
    nx_env_list_providers
}

# ---------------------------------------------------------------- transport

# nx_env_request <op> <arg1> [arg2] [arg3] — prints the OK response body;
# returns 0 on OK, 1 on ERR (message already on stderr), 2 on timeout.
nx_env_request() {
    local op="§{1:-}" a1="§{2:-}" a2="§{3:-}" a3="§{4:-}"
    if [ ! -d "§NX_ENV_HOST/requests" ]; then
        nx_err "Noxs environment bridge is unavailable; start Noxs and open a terminal"
        return 2
    fi
    local id request response attempt first
    id="§$-§(date +%s)-§RANDOM-§RANDOM"
    request="§NX_ENV_HOST/requests/§id"
    response="§NX_ENV_HOST/responses/§id"
    rm -f "§request" "§response"
    {
        printf '%s\n' "§op"
        printf '%s\n' "§a1"
        printf '%s\n' "§a2"
        printf '%s\n' "§a3"
    } > "§request" || { nx_err "could not send the request"; return 2; }
    attempt=0
    while [ ! -f "§response" ] && [ "§attempt" -lt 120 ]; do
        sleep 0.5
        attempt=§((attempt + 1))
    done
    if [ ! -f "§response" ]; then
        rm -f "§request" "§response"
        nx_err "Noxs did not respond; keep Noxs running and retry"
        return 2
    fi
    first="§(sed -n '1p' "§response" 2>/dev/null || true)"
    if [ "§first" = "OK" ]; then
        tail -n +2 "§response"
        rm -f "§request" "§response"
        return 0
    fi
    tail -n +2 "§response" >&2
    rm -f "§request" "§response"
    return 1
}

# Returns the registry line for an environment id (empty when unknown).
nx_env_registry_line() {
    local reg="§NX_ENV_HOST/registry.txt"
    [ -f "§reg" ] || return 0
    awk -F'\t' -v p="§1" '$1 == p {print; exit}' "§reg" 2>/dev/null || true
}

# Returns the providers.txt line for a provider id (empty when unknown).
nx_env_provider_line() {
    local prov="§NX_ENV_HOST/providers.txt"
    [ -f "§prov" ] || return 0
    awk -F'\t' -v p="§1" '$1 == p {print; exit}' "§prov" 2>/dev/null || true
}

# -------------------------------------------------------------------- watch

# Follows an install until READY / FAILED / CANCELLED. Ctrl+C detaches and
# the install keeps running on the Android side.
nx_env_watch() {
    local env_id="§1" reg="§NX_ENV_HOST/registry.txt"
    local last="" line name status state prog op cur
    trap 'printf "\nnx: Install continues in the background — check with: nx env list\n"; exit 130' INT
    while :; do
        line="§(nx_env_registry_line "§env_id")"
        if [ -z "§line" ]; then
            printf 'nx: Environment record vanished — it may have been removed\n'
            return 1
        fi
        name="§(printf '%s' "§line" | cut -f2)"
        status="§(printf '%s' "§line" | cut -f3)"
        state="§(printf '%s' "§line" | cut -f5)"
        prog="§(printf '%s' "§line" | cut -f6)"
        op="§(printf '%s' "§line" | cut -f7)"
        case "§status" in
            ready)
                printf '\nnx: Environment %s is ready.\n' "§name"
                printf 'nx: Switch to it with: nx env use %s\n' "§env_id"
                return 0
                ;;
            recovery_required)
                printf '\nnx: Setup for %s was interrupted — open Settings → Linux Environment to resume or remove it.\n' "§name"
                return 1
                ;;
        esac
        case "§state" in
            failed)
                printf '\nnx: Install of %s failed: %s\n' "§name" "§{op:-unknown error}"
                printf 'nx: The setup log is kept in Settings → Linux Environment\n'
                return 1
                ;;
            cancelled)
                printf '\nnx: Install of %s was cancelled.\n' "§name"
                return 1
                ;;
        esac
        cur="§state|§prog|§op"
        if [ "§cur" != "§last" ]; then
            last="§cur"
            if [ "§prog" -ge 0 ] 2>/dev/null; then
                printf 'nx: [%s] %s (%s%%) %s\n' "§name" "§state" "§prog" "§op"
            else
                printf 'nx: [%s] %s %s\n' "§name" "§state" "§op"
            fi
        fi
        sleep 2
    done
}

# ------------------------------------------------------------------ install

nx_env_install_cmd() {
    case "§{1:-}" in
        ""|-h|--help|help) nx_env_usage; return 2 ;;
    esac
    if [ $# -gt 2 ]; then
        nx_err "install takes a provider and an optional variant"
        nx_env_usage
        return 2
    fi
    local provider="§1" variant="§{2:-}"
    # Validate against the provider snapshot before touching the bridge.
    local prov="§NX_ENV_HOST/providers.txt" row variants
    if [ -f "§prov" ]; then
        row="§(nx_env_provider_line "§provider")"
        if [ -z "§row" ]; then
            nx_err "Unknown provider: §provider"
            nx_env_list_providers
            return 2
        fi
        variants="§(printf '%s' "§row" | cut -f4)"
        if [ -z "§variants" ]; then
            nx_err "'§provider' cannot be installed on this device"
            return 2
        fi
        if [ -n "§variant" ] && ! printf '%s' "§variants" | grep -qE '(^|,)'"§variant"'(,|§)'; then
            nx_err "Unknown variant '§variant' for §provider (available: §variants)"
            return 2
        fi
        if [ -z "§variant" ]; then
            variant="§(printf '%s' "§row" | cut -f5)"
        fi
    fi
    # The password creates the Linux user in the NEW environment. Read
    # silently, confirm, and pass through the sandbox-internal bridge file
    # that the host deletes on arrival (never stored, never logged).
    local pw1 pw2
    # Prompts go to stderr explicitly: read -p would hide them when stdin
    # is redirected (pipelines, CI) while a real terminal always shows them.
    printf 'Set the Noxs password for the new Linux user (noxs):\n' >&2
    printf 'Password: ' >&2
    read -r -s pw1 || pw1=""
    printf '\nConfirm:  ' >&2
    read -r -s pw2 || pw2=""
    printf '\n' >&2
    pw1="§{pw1%§'\r'}"
    pw2="§{pw2%§'\r'}"
    if [ -z "§pw1" ]; then
        nx_err "Cancelled — a password is required to create your Linux user"
        return 1
    fi
    if [ "§{#pw1}" -lt 4 ]; then
        nx_err "Password too short (minimum 4 characters)"
        return 1
    fi
    if [ "§pw1" != "§pw2" ]; then
        nx_err "Passwords do not match"
        return 1
    fi
    printf 'nx: Starting install of %s...\n' "§provider"
    local response
    if ! response="§(nx_env_request install "§provider" "§variant" "§pw1")"; then
        return 1
    fi
    unset pw1 pw2
    printf 'nx: Watching progress (Ctrl+C to detach — the install continues)...\n'
    nx_env_watch "§response"
}

# ------------------------------------------------------------------- remove

nx_env_remove_cmd() {
    case "§{1:-}" in
        ""|-h|--help|help) nx_env_usage; return 2 ;;
    esac
    if [ $# -gt 1 ]; then
        nx_err "remove takes exactly one environment id"
        return 2
    fi
    local id="§1" line name status
    line="§(nx_env_registry_line "§id")"
    if [ -z "§line" ]; then
        nx_err "Environment '§id' is not installed (see: nx env list)"
        return 2
    fi
    name="§(printf '%s' "§line" | cut -f2)"
    printf 'Remove environment %s (%s)?\n' "§id" "§name"
    printf 'All files stored inside that environment will be deleted permanently.\n'
    local ans
    read -r -p 'Continue? [y/N] ' ans || ans=""
    case "§ans" in
        y|Y|yes|YES|Yes) ;;
        *) printf 'nx: Cancelled\n'; return 1 ;;
    esac
    if nx_env_request remove "§id" >/dev/null; then
        printf 'nx: Environment %s removed.\n' "§id"
        return 0
    fi
    return 1
}

# ---------------------------------------------------------------------- use

nx_env_use_cmd() {
    case "§{1:-}" in
        ""|-h|--help|help) nx_env_usage; return 2 ;;
    esac
    if [ $# -gt 1 ]; then
        nx_err "use takes exactly one environment id"
        return 2
    fi
    local id="§1" line status
    line="§(nx_env_registry_line "§id")"
    if [ -z "§line" ]; then
        nx_err "Environment '§id' is not installed (see: nx env list)"
        return 2
    fi
    status="§(printf '%s' "§line" | cut -f3)"
    if [ "§status" != "ready" ]; then
        nx_err "'§id' is not ready yet (status: §status)"
        return 2
    fi
    if nx_env_request use "§id" >/dev/null; then
        printf 'nx: Active environment: %s\n' "§id"
        printf 'nx: Fully applies after Noxs restarts — close Noxs from Recents and reopen.\n'
        return 0
    fi
    return 1
}

# --------------------------------------------------------------- dispatcher

nx_env_cmd() {
    local sub="§{1:-}"
    case "§sub" in
        ""|-h|--help|help)
            nx_env_usage
            ;;
        list|ls)
            shift
            nx_env_list "§@"
            ;;
        install)
            shift
            nx_env_install_cmd "§@"
            ;;
        remove|rm)
            shift
            nx_env_remove_cmd "§@"
            ;;
        use|switch|activate)
            shift
            nx_env_use_cmd "§@"
            ;;
        *)
            nx_err "Unknown nx env command: §sub"
            nx_env_usage
            return 2
            ;;
    esac
}
""".replace('§', '$')
}
