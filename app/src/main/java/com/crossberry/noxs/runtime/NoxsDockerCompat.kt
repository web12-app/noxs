/*
 * Noxs — original implementation.
 * Honest Docker capability classification for the Android + proot runtime.
 *
 * Docker inside Noxs is OPTIONAL and EXPERIMENTAL. Android (no device root,
 * proot userspace) may restrict cgroups, iptables/nftables, mounts,
 * namespaces and kernel features. Every such restriction is classified as a
 * Noxs RUNTIME LIMITATION — never as "Docker installation corrupted".
 *
 * Nothing here fakes daemon output: classification always runs over REAL
 * command output captured from `docker info`, `docker run`, dockerd logs or
 * capability probes.
 */
package com.crossberry.noxs.runtime

object NoxsDockerCompat {

    /** Final setup state (spec: DOCKER_READY / COMPATIBILITY / …). */
    enum class State(val key: String, val label: String) {
        NOT_INSTALLED("not_installed", "Docker is not installed"),
        READY("ready", "Docker is fully ready"),
        COMPATIBILITY("compatibility", "Docker runs in Noxs compatibility mode"),
        INSTALLED_DAEMON_UNAVAILABLE("daemon_unavailable", "Docker is installed but the daemon is unavailable"),
        INSTALL_FAILED("install_failed", "Docker installation failed");

        companion object {
            fun fromKey(key: String): State? = entries.firstOrNull { it.key == key }
        }
    }

    /** Why a Docker feature is limited. Never user-facing as an enum name. */
    enum class Limitation {
        NONE,
        /** iptables/nftables unavailable (Android restricts kernel networking controls). */
        NETWORKING,
        /** cgroup controllers unavailable or unreadable. */
        CGROUPS,
        /** overlay/mount operations not permitted. */
        STORAGE,
        /** namespaces / other kernel features restricted. */
        KERNEL,
        /** a real failure that does not match a known runtime limitation. */
        UNKNOWN
    }

    data class Verdict(val limitation: Limitation, val reason: String)

    /** One checklist row rendered in the setup console (spec §7). */
    data class CheckRow(
        val label: String,
        val ok: Boolean?,
        val detail: String = "",
        /** A runtime limitation (expected on Android) — shown as [!] not [✗]. */
        val limited: Boolean = false
    ) {
        fun mark(): String = when {
            ok == true -> "[✓]"
            ok == false && limited -> "[!]"
            ok == false -> "[✗]"
            else -> "[!]"
        }
    }

    // ------------------------------------------------------------ patterns

    private val NETWORKING_FAILURE = Regex(
        "(?i)(" +
            "failed to initialize nft" +
            "|nftables?.*(permission denied|not permitted|not supported)" +
            "|iptables.*(permission denied|operation not permitted|failed to initialize)" +
            "|could not create netinterface|network namespace.*not permitted" +
            ")"
    )

    private val CGROUP_FAILURE = Regex(
        "(?i)(" +
            "cgroup\\.controllers.*permission denied" +
            "|unable to find memory controller" +
            "|unable to find cpu controller" +
            "|unable to find io controller" +
            "|unable to find cpuset controller" +
            "|unable to find pids controller" +
            "|failed to write.*cgroup" +
            "|mkdir.*cgroup.*permission denied" +
            ")"
    )

    private val STORAGE_FAILURE = Regex(
        "(?i)(" +
            "overlay.*(permission denied|operation not permitted|not supported)" +
            "|failed to create overlay" +
            "|backing filesystem.*unsupported" +
            "|mount.*(permission denied|operation not permitted)" +
            ")"
    )

    private val KERNEL_FAILURE = Regex(
        "(?i)(" +
            "operation not permitted" +
            "|permission denied" +
            "|clone.*failed|unshare.*failed" +
            "|cannot create namespace" +
            ")"
    )

    const val DAEMON_NOT_RUNNING_MARKER = "cannot connect to the docker daemon"

    // ------------------------------------------------------- classification

    /**
     * Classifies a REAL daemon/CLI failure. Known Android/proot restrictions
     * become runtime limitations; anything else stays an honest UNKNOWN with
     * the original error text.
     */
    fun classifyFailure(output: String): Verdict {
        val text = output.lowercase()
        return when {
            NETWORKING_FAILURE.containsMatchIn(text) -> Verdict(
                Limitation.NETWORKING,
                "Docker networking (iptables/nftables) is restricted by Android. " +
                    "Noxs is running inside proot, so kernel networking controls are limited."
            )
            CGROUP_FAILURE.containsMatchIn(text) -> Verdict(
                Limitation.CGROUPS,
                "Linux cgroup controllers are restricted by Android, so container " +
                    "resource isolation is unavailable."
            )
            STORAGE_FAILURE.containsMatchIn(text) -> Verdict(
                Limitation.STORAGE,
                "Docker storage (overlay/mount) operations are restricted by Android."
            )
            KERNEL_FAILURE.containsMatchIn(text) -> Verdict(
                Limitation.KERNEL,
                "Kernel features required by containers are restricted by Android."
            )
            else -> Verdict(
                Limitation.UNKNOWN,
                output.lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.isNotBlank() }
                    ?.take(220)
                    ?: "unknown error"
            )
        }
    }

    /** True when the captured `docker info` output says the daemon is down. */
    fun isDaemonNotRunning(output: String): Boolean =
        output.lowercase().contains(DAEMON_NOT_RUNNING_MARKER)

    /** dockerd flags for the Noxs compatibility mode (no privileged ops). */
    fun compatDaemonFlags(): List<String> = listOf("--iptables=false", "--bridge=none")

    // ------------------------------------------------------- storage driver

    /** Preferred storage drivers in order (spec §5): overlay2 > fuse-overlayfs > vfs. */
    val PREFERRED_DRIVERS = listOf("overlay2", "fuse-overlayfs", "vfs")

    /** Parses the REAL "Storage Driver:" line out of `docker info` output. */
    fun parseStorageDriver(infoOutput: String): String? =
        infoOutput.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("Storage Driver:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    fun driverRank(driver: String?): Int =
        PREFERRED_DRIVERS.indexOf(driver?.lowercase()).let { if (it >= 0) it else Int.MAX_VALUE }

    /**
     * Chooses an extra daemon flag when the preferred driver cannot operate:
     * falls back to vfs (always works on a plain filesystem) without ever
     * loading kernel modules (no modprobe, no Android kernel changes).
     */
    fun storageFallbackFlag(failedDriver: String?): String? =
        if (failedDriver != null && driverRank(failedDriver) < driverRank("vfs")) {
            "--storage-driver=vfs"
        } else null

    // ------------------------------------------------------------ state file

    /**
     * The state file stores ONLY safe runtime facts (running/stopped/failed/
     * compatibility) — never credentials of any kind. Live status is always
     * re-verified with `docker info`; this file is a hint, not the truth.
     */
    fun serializeState(state: State, mode: String = "", daemonRunning: Boolean = false): String =
        buildString {
            append("state=").append(state.key).append('\n')
            append("mode=").append(mode).append('\n')
            append("daemon_running=").append(if (daemonRunning) "yes" else "no").append('\n')
            append("updated=").append(System.currentTimeMillis()).append('\n')
        }

    data class PersistedState(val state: State?, val mode: String, val daemonRunning: Boolean)

    fun parseState(text: String): PersistedState {
        var state: State? = null
        var mode = ""
        var running = false
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            val idx = line.indexOf('=')
            if (idx <= 0) return@forEach
            val key = line.substring(0, idx)
            val value = line.substring(idx + 1).trim()
            when (key) {
                "state" -> state = State.fromKey(value)
                "mode" -> mode = value
                "daemon_running" -> running = value == "yes"
            }
        }
        return PersistedState(state, mode, running)
    }

    // ---------------------------------------------------------- user wording

    /** "Docker daemon: available / Docker bridge networking: unavailable" block. */
    fun summaryLines(
        daemonAvailable: Boolean,
        networkingAvailable: Boolean,
        storageDriver: String?,
        containersSupported: Boolean?
    ): List<String> = buildList {
        add("Docker daemon: " + if (daemonAvailable) "available" else "unavailable")
        add("Docker bridge networking: " + if (networkingAvailable) "available" else "unavailable")
        add("Docker storage: " + when {
            storageDriver == null -> "unavailable"
            else -> "available ($storageDriver)"
        })
        when (containersSupported) {
            true -> add("Container execution: supported")
            false -> add("Container execution: unsupported on this Android kernel")
            null -> add("Container execution: untested")
        }
    }
}
