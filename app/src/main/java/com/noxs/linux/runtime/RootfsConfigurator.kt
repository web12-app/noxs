/*
 * Noxs — original implementation.
 * Configures the extracted Debian rootfs into the Noxs userspace:
 *  - writes overlay files (profile.d, environment, motd, apt sources, DNS)
 *  - installs the `noxs` CLI (code-server, services, sockets, processes)
 *  - ensures /run, /var/run, /var/run/noxs, /var/log/noxs exist (FHS)
 * Content mirrors linux-runtime/rootfs/overlay (CI diff-checks both).
 */
package com.noxs.linux.runtime

import android.content.Context
import com.noxs.linux.R
import com.noxs.linux.shared.NoxsConstants
import com.noxs.linux.shared.NoxsLog
import java.io.File

object RootfsConfigurator {

    fun configure(context: Context, paths: NoxsPaths) {
        val rootfs = paths.rootfs

        // --- FHS directories that must exist in the Noxs userspace ---
        listOf(
            "bin", "boot", "dev", "etc", "home", "lib", "media", "mnt", "opt",
            "proc", "root", "run", "sbin", "srv", "sys", "tmp", "usr/bin",
            "usr/sbin", "usr/lib", "usr/local/bin", "usr/local/lib",
            "usr/local/lib/noxs", "usr/share", "var/cache", "var/lib",
            "var/log/noxs", "var/run/noxs", "var/spool"
        ).forEach { File(rootfs, it).mkdirs() }
        File(rootfs, "home/noxs").mkdirs()

        // --- profile.d/noxs.sh ---
        writeFile(File(rootfs, "etc/profile.d/noxs.sh"), NOXS_PROFILE)
        File(rootfs, "etc/profile.d/noxs.sh").setExecutable(false)

        // --- /etc/environment ---
        writeFile(File(rootfs, "etc/environment"), ENVIRONMENT)

        // --- /etc/motd ---
        writeFile(File(rootfs, "etc/motd"), motd())

        // --- DNS ---
        writeFile(File(rootfs, "etc/resolv.conf"), DNS)
        writeFile(File(rootfs, "etc/hosts"), HOSTS)
        writeFile(File(rootfs, "etc/hostname"), "noxs\n")

        // --- APT configuration (isolated to the Noxs rootfs) ---
        writeFile(File(rootfs, "etc/apt/sources.list"), aptSources())
        File(rootfs, "etc/apt/apt.conf.d").mkdirs()
        writeFile(File(rootfs, "etc/apt/apt.conf.d/70noxs"), APT_CONF)

        // --- `noxs` CLI ---
        val cli = File(rootfs, "usr/local/bin/noxs")
        writeFile(cli, NOXS_CLI)
        cli.setExecutable(true, false)

        // --- sudo guard alias (informational MOTD; sudo remains Debian's real sudo) ---
        File(rootfs, "usr/local/bin/noxs-hello").let {
            writeFile(it, HELLO)
            it.setExecutable(true, false)
        }

        NoxsLog.i("RootfsConfig", "Debian userspace configured")
    }

    private fun writeFile(f: File, content: String) {
        f.parentFile?.mkdirs()
        f.writeText(content)
    }

    private fun motd(): String = """
        Welcome to Noxs (Debian 12 ${NoxsConstants.DEFAULT_DEBIAN_SUITE.replaceFirstChar { it.uppercase() }} userspace)

        * All operations run inside the Android app sandbox — you have full
          control of the Noxs Linux environment, not of the Android device.
        * `sudo` asks for your noxs password and manages THIS environment.
        * `noxs code` manages the integrated code-server (VS Code in browser).
        * `noxs-service start|stop|status` manages sandbox services.
        * Unix sockets live under /run and /var/run (e.g. /var/run/noxs/).
        * `noxs help` lists all Noxs commands.
    """.trimIndent() + "\n"

    private fun aptSources(): String = """
        deb https://deb.debian.org/debian bookworm main contrib non-free non-free-firmware
        deb https://deb.debian.org/debian bookworm-updates main contrib non-free non-free-firmware
        deb https://security.debian.org/debian-security bookworm-security main contrib non-free non-free-firmware
    """.trimIndent() + "\n"

    // ------------------------------------------------------------ templates

    private val NOXS_PROFILE = """
        # /etc/profile.d/noxs.sh — Noxs environment integration
        export NOXS=1
        export NOXS_USER=${NoxsConstants.DEFAULT_USER}
        export NOXS_RUN_DIR=${NoxsConstants.NOXS_RUN_DIR}
        export NOXS_HOME=${NoxsConstants.DEFAULT_USER_HOME}
        export PATH="/usr/local/bin:${'$'}PATH"
        export PS1='noxs@android:\w\$ '
        # recreate FHS links once (app storage cannot create symlinks directly)
        if [ -x /usr/local/lib/noxs-links.sh ]; then
            /usr/local/lib/noxs-links.sh >/dev/null 2>&1 || true
        fi
        # apply resource quotas (children of this shell only)
        if [ -r /etc/noxs/resources.conf ]; then
            . /etc/noxs/resources.conf 2>/dev/null || true
            [ -n "${'$'}MAX_PROCESSES" ] && ulimit -u "${'$'}MAX_PROCESSES" 2>/dev/null || true
            [ -n "${'$'}MAX_OPEN_FILES" ] && ulimit -n "${'$'}MAX_OPEN_FILES" 2>/dev/null || true
        fi
    """.trimIndent()

    private val ENVIRONMENT = """
        NOXS=1
        LANG=C.UTF-8
        EDITOR=nano
    """.trimIndent()

    private val DNS = """
        # Noxs sandbox DNS (editable in Settings)
        nameserver 1.1.1.1
        nameserver 8.8.8.8
    """.trimIndent()

    private val HOSTS = """
        127.0.0.1 localhost
        127.0.1.1 noxs
        ::1 localhost ip6-localhost
    """.trimIndent()

    private val APT_CONF = """
        // Noxs sandbox apt tuning (original file, isolated to the Noxs rootfs)
        APT::Install-Recommends "false";
        APT::Get::AllowUnauthenticated "false";
        Acquire::Languages "none";
        Dir::Cache::archives "/var/cache/apt/archives";
    """.trimIndent()

    private val HELLO = """
        #!/bin/sh
        echo "Noxs ${NoxsConstants.VERSION_NAME} — Debian 12 userspace inside the Android app sandbox."
        echo "Security notice: 'sudo' here manages the Noxs environment ONLY."
        echo "It does not provide Android device root access."
    """.trimIndent()

    /**
     * The `noxs` CLI — service manager, socket manager, process view and
     * code-server integration. Canonical source: linux-runtime/launcher/noxs-cli
     * (CI diff-checks the asset copy against it).
     */
    val NOXS_CLI = NoxsCliTemplate.CLI
}
