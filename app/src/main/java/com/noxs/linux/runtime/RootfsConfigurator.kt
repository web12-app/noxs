/*
 * Noxs — original implementation.
 * Configures the extracted Debian rootfs into the Noxs userspace:
 *  - writes overlay files (profile.d, environment, motd, apt sources, DNS)
 *  - installs the `noxs` CLI (code-server, services, sockets, processes)
 *  - installs Android proot-compatible `su` and `sudo` wrappers
 *  - repairs any 0-byte symlink placeholders from older installs
 *  - ensures /run, /var/run, /var/run/noxs, /var/log/noxs exist (FHS)
 * Content mirrors linux-runtime/rootfs/overlay (CI diff-checks both).
 */
package com.noxs.linux.runtime

import android.content.Context
import com.noxs.linux.shared.NoxsConstants
import com.noxs.linux.shared.NoxsGroup
import com.noxs.linux.shared.NoxsLog
import com.noxs.linux.shared.NoxsUser
import com.noxs.linux.shared.PasswdDb
import com.noxs.linux.shared.RootfsExtractor
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

object RootfsConfigurator {

    fun configure(context: Context, paths: NoxsPaths) = configure(paths)

    fun configure(paths: NoxsPaths) {
        val rootfs = paths.rootfs

        // --- Real directories first (usr/bin, usr/sbin, usr/lib BEFORE FHS links) ---
        listOf(
            "usr/bin", "usr/sbin", "usr/lib", "usr/local/bin", "usr/local/lib",
            "usr/local/lib/noxs", "usr/share", "boot", "dev", "etc", "etc/noxs",
            "etc/profile.d", "etc/apt/apt.conf.d", "home", "home/noxs", "media",
            "mnt", "opt", "proc", "root", "run", "srv", "sys", "tmp",
            "var/cache", "var/cache/apt/archives/partial", "var/lib", "var/lib/dpkg",
            "var/log/noxs", "var/run/noxs", "var/run/noxs/host", "var/spool"
        ).forEach { rel ->
            val f = File(rootfs, rel)
            if (!Files.isSymbolicLink(f.toPath())) f.mkdirs()
        }

        // --- FHS top-level symlinks (bin -> usr/bin, sbin -> usr/sbin, lib -> usr/lib) ---
        ensureSymlink(File(rootfs, "bin"), "usr/bin")
        ensureSymlink(File(rootfs, "sbin"), "usr/sbin")
        ensureSymlink(File(rootfs, "lib"), "usr/lib")

        // --- Default resource quotas file (bound by ProotLauncher) ---
        if (!paths.noxsResourcesConf.isFile) {
            writeFile(paths.noxsResourcesConf, ResourceQuotas().serialize())
        }

        // --- profile.d/noxs.sh ---
        writeFile(File(rootfs, "etc/profile.d/noxs.sh"), NOXS_PROFILE)
        File(rootfs, "etc/profile.d/noxs.sh").setExecutable(false)

        // --- /etc/environment ---
        writeFile(File(rootfs, "etc/environment"), ENVIRONMENT)

        // --- /etc/motd ---
        writeFile(File(rootfs, "etc/motd"), motd())

        // --- DNS ---
        if (!File(rootfs, "etc/resolv.conf").isFile || File(rootfs, "etc/resolv.conf").length() == 0L) {
            writeFile(File(rootfs, "etc/resolv.conf"), DNS)
        }
        writeFile(File(rootfs, "etc/hosts"), HOSTS)
        writeFile(File(rootfs, "etc/hostname"), "noxs\n")

        // --- APT configuration (isolated to the Noxs rootfs) ---
        writeFile(File(rootfs, "etc/apt/sources.list"), aptSources())
        writeFile(File(rootfs, "etc/apt/apt.conf.d/70noxs"), APT_CONF)

        // --- `noxs` CLI ---
        val cli = File(rootfs, "usr/local/bin/noxs")
        writeFile(cli, NOXS_CLI)
        cli.setExecutable(true, false)

        // --- `noxs-hello` ---
        File(rootfs, "usr/local/bin/noxs-hello").let {
            writeFile(it, HELLO)
            it.setExecutable(true, false)
        }

        // --- Android proot-compatible `su` and `sudo` ---
        installSuAndSudo(rootfs)

        // --- Ensure `noxs` user and interactive bashrc prompt/banner ---
        ensureNoxsUserAndBashrc(rootfs)

        NoxsLog.i("RootfsConfig", "Debian userspace configured")
    }

    /**
     * Repairs an existing rootfs if an earlier version extracted 0-byte placeholder
     * files instead of real symbolic links, then refreshes configuration.
     */
    fun ensureHealthyRootfs(paths: NoxsPaths) {
        val rootfs = paths.rootfs
        if (!rootfs.isDirectory) return
        val binFile = File(rootfs, "bin")
        val shFile = File(rootfs, "usr/bin/sh")
        val ldAarch64 = File(rootfs, "usr/lib/ld-linux-aarch64.so.1")
        val needsRepair = (!Files.isSymbolicLink(binFile.toPath()) && binFile.isFile) ||
            (shFile.exists() && !Files.isSymbolicLink(shFile.toPath()) && shFile.length() == 0L) ||
            (ldAarch64.exists() && !Files.isSymbolicLink(ldAarch64.toPath()) && ldAarch64.length() == 0L)

        if (needsRepair) {
            NoxsLog.i("RootfsConfig", "Detected broken 0-byte symlink placeholders in rootfs; repairing...")
            // Remove 0-byte top-level files that block directory/symlink creation
            listOf("bin", "sbin", "lib", "lib64").forEach { name ->
                val f = File(rootfs, name)
                if (!Files.isSymbolicLink(f.toPath()) && f.isFile && f.length() == 0L) {
                    f.delete()
                }
            }
            val cachedArchive = paths.cache.listFiles()?.firstOrNull {
                it.isFile && it.name.startsWith("rootfs-") && it.length() > 1_000_000L
            }
            if (cachedArchive != null) {
                runCatching {
                    val stats = RootfsExtractor(rootfs).extract(cachedArchive)
                    NoxsLog.i("RootfsConfig", "Re-extracted rootfs from cache (links=${stats.links})")
                }.onFailure { e ->
                    NoxsLog.w("RootfsConfig", "Cache re-extract failed: ${e.message}; running in-place symlink repair")
                }
            }
            repairZeroByteSymlinks(rootfs)
        }
        configure(paths)
    }

    private fun ensureSymlink(link: File, target: String) {
        val p = link.toPath()
        if (Files.isSymbolicLink(p)) return
        if (link.isFile && link.length() == 0L) link.delete()
        if (!link.exists()) {
            runCatching { Files.createSymbolicLink(p, Paths.get(target)) }
        }
    }

    private fun repairZeroByteSymlinks(rootfs: File) {
        ensureSymlink(File(rootfs, "bin"), "usr/bin")
        ensureSymlink(File(rootfs, "sbin"), "usr/sbin")
        ensureSymlink(File(rootfs, "lib"), "usr/lib")
        ensureSymlink(File(rootfs, "usr/bin/sh"), "dash")
        ensureSymlink(File(rootfs, "usr/bin/awk"), "mawk")
        if (File(rootfs, "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1").isFile) {
            ensureSymlink(File(rootfs, "usr/lib/ld-linux-aarch64.so.1"), "aarch64-linux-gnu/ld-linux-aarch64.so.1")
        }
        // Repair 0-byte shared library SONAME placeholders (e.g. libtinfo.so.6 -> libtinfo.so.6.4)
        val usrLib = File(rootfs, "usr/lib")
        if (usrLib.isDirectory) {
            usrLib.walkTopDown().filter {
                !Files.isSymbolicLink(it.toPath()) && it.isFile && it.length() == 0L && it.name.contains(".so")
            }.forEach { zeroSo ->
                val parent = zeroSo.parentFile ?: return@forEach
                val candidate = parent.listFiles()?.firstOrNull {
                    it.name != zeroSo.name &&
                        it.name.startsWith(zeroSo.name + ".") &&
                        it.isFile &&
                        it.length() > 0L
                }
                if (candidate != null) {
                    zeroSo.delete()
                    runCatching { Files.createSymbolicLink(zeroSo.toPath(), Paths.get(candidate.name)) }
                }
            }
        }
    }

    private fun installSuAndSudo(rootfs: File) {
        val suFile = File(rootfs, "usr/bin/su")
        val suOrig = File(rootfs, "usr/bin/su.orig")
        if (suFile.isFile && !suOrig.exists() && suFile.length() > 4096L) {
            runCatching { suFile.renameTo(suOrig) }
        }
        writeFile(suFile, NOXS_SU_WRAPPER)
        suFile.setExecutable(true, false)

        val sudoFile = File(rootfs, "usr/local/bin/sudo")
        writeFile(sudoFile, NOXS_SUDO_WRAPPER)
        sudoFile.setExecutable(true, false)
    }

    private fun ensureNoxsUserAndBashrc(rootfs: File) {
        val etc = File(rootfs, "etc")
        val passwdFile = File(etc, "passwd")
        val groupFile = File(etc, "group")
        val shadowFile = File(etc, "shadow")
        if (passwdFile.isFile) {
            val users = PasswdDb.parsePasswd(passwdFile.readText())
            if (users.none { it.name == NoxsConstants.DEFAULT_USER }) {
                val uid = PasswdDb.nextFreeUid(users)
                val user = NoxsUser(
                    name = NoxsConstants.DEFAULT_USER,
                    uid = uid,
                    gid = uid,
                    gecos = "Noxs User,,,",
                    home = NoxsConstants.DEFAULT_USER_HOME,
                    shell = NoxsConstants.DEFAULT_SHELL
                )
                passwdFile.appendText(PasswdDb.serializeUser(user) + "\n")
                if (shadowFile.isFile) {
                    shadowFile.appendText(PasswdDb.defaultShadowLineFor(user.name) + "\n")
                }
                if (groupFile.isFile) {
                    val groups = PasswdDb.parseGroup(groupFile.readText())
                    if (groups.none { it.name == "sudo" }) {
                        groupFile.appendText(PasswdDb.serializeGroup(NoxsGroup("sudo", 27, emptyList())) + "\n")
                    }
                    val updated = groupFile.readLines().joinToString("\n") { line ->
                        if (line.startsWith("sudo:") && !line.contains(NoxsConstants.DEFAULT_USER)) {
                            "$line,${NoxsConstants.DEFAULT_USER}"
                        } else line
                    }
                    groupFile.writeText(updated + "\n")
                }
            }
        }

        val homeNoxs = File(rootfs, "home/noxs")
        val homeRoot = File(rootfs, "root")
        homeNoxs.mkdirs()
        homeRoot.mkdirs()

        listOf(".bashrc", ".profile").forEach { name ->
            val skel = File(rootfs, "etc/skel/$name")
            listOf(homeNoxs, homeRoot).forEach { dir ->
                val target = File(dir, name)
                if (skel.isFile && !target.isFile) {
                    runCatching { skel.copyTo(target, overwrite = false) }
                }
            }
        }

        val marker = "# --- Noxs interactive shell setup ---"
        listOf(
            File(rootfs, "etc/bash.bashrc"),
            File(homeNoxs, ".bashrc"),
            File(homeRoot, ".bashrc")
        ).forEach { rc ->
            val existing = if (rc.isFile) rc.readText() else ""
            if (!existing.contains(marker)) {
                writeFile(rc, existing.trimEnd() + "\n\n" + NOXS_BASHRC_SNIPPET + "\n")
            }
        }
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

    private val NOXS_BASHRC_SNIPPET = """
        # --- Noxs interactive shell setup ---
        unset LD_LIBRARY_PATH
        export NOXS=1
        export PATH="/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
        if [ "${'$'}{NOXS_ROOT_LOGIN:-0}" = "1" ]; then
            export USER="root"
            export LOGNAME="root"
            export PS1='\[\033[01;31m\]root@noxs\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]# '
        else
            export USER="${'$'}{USER:-noxs}"
            export LOGNAME="${'$'}{LOGNAME:-noxs}"
            export PS1='\[\033[01;32m\]noxs@android\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]$ '
        fi
        if [ -z "${'$'}{NOXS_BANNER_SHOWN:-}" ] && [ -t 1 ]; then
            export NOXS_BANNER_SHOWN=1
            printf '\033[1;32mNoxs Debian 12 (bookworm)\033[0m — %s (%s)\n' "${'$'}(uname -sr 2>/dev/null || echo Linux)" "${'$'}(uname -m 2>/dev/null || echo arm64)"
            printf 'Type \033[1;36mnoxs help\033[0m, \033[1;36mls -la\033[0m, or \033[1;36msudo apt update\033[0m.\n\n'
        fi
    """.trimIndent()

    private val NOXS_SU_WRAPPER = """
        #!/bin/sh
        # Noxs proot-compatible su implementation.
        # Debian's PAM su calls libaudit NETLINK_AUDIT which Android SELinux blocks with EACCES.
        SHELL_BIN="/bin/bash"
        LOGIN_SHELL=0
        CMD=""
        HAS_CMD=0
        TARGET_USER="root"

        while [ ${'$'}# -gt 0 ]; do
            case "${'$'}1" in
                -l|-|--login)
                    LOGIN_SHELL=1
                    shift
                    ;;
                -s|--shell)
                    SHELL_BIN="${'$'}{2:-/bin/bash}"
                    shift 2
                    ;;
                -c|--command)
                    CMD="${'$'}{2:-}"
                    HAS_CMD=1
                    shift 2
                    ;;
                -p|-m|--preserve-environment)
                    shift
                    ;;
                --)
                    shift
                    break
                    ;;
                -*)
                    shift
                    ;;
                *)
                    TARGET_USER="${'$'}1"
                    shift
                    ;;
            esac
        done

        if [ ${'$'}# -gt 0 ] && [ "${'$'}TARGET_USER" = "root" ]; then
            TARGET_USER="${'$'}1"
        fi

        unset LD_LIBRARY_PATH
        export PATH="/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
        export SHELL="${'$'}SHELL_BIN"

        if [ "${'$'}TARGET_USER" = "root" ]; then
            export USER="root"
            export LOGNAME="root"
            export HOME="/root"
            export NOXS_ROOT_LOGIN="1"
        else
            export USER="${'$'}TARGET_USER"
            export LOGNAME="${'$'}TARGET_USER"
            export HOME="/home/${'$'}TARGET_USER"
            unset NOXS_ROOT_LOGIN
        fi

        if [ "${'$'}LOGIN_SHELL" = "1" ] && [ -d "${'$'}HOME" ]; then
            cd "${'$'}HOME" 2>/dev/null || true
        fi

        if [ "${'$'}HAS_CMD" = "1" ]; then
            exec "${'$'}SHELL_BIN" -c "${'$'}CMD"
        fi

        exec "${'$'}SHELL_BIN" --login
    """.trimIndent() + "\n"

    private val NOXS_SUDO_WRAPPER = """
        #!/bin/sh
        # Noxs sandbox sudo wrapper (executes inside proot -0 fake-root userspace).
        unset LD_LIBRARY_PATH
        export PATH="/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
        SHELL_MODE=0
        while [ ${'$'}# -gt 0 ]; do
            case "${'$'}1" in
                -i|--login|-s|--shell)
                    SHELL_MODE=1
                    shift
                    ;;
                -u|--user)
                    shift 2
                    ;;
                -E|-H|-n|-S|-v|-k|-K)
                    shift
                    ;;
                --)
                    shift
                    break
                    ;;
                -*)
                    shift
                    ;;
                *)
                    break
                    ;;
            esac
        done
        export USER="root"
        export LOGNAME="root"
        export NOXS_ROOT_LOGIN="1"
        if [ "${'$'}SHELL_MODE" = "1" ] && [ ${'$'}# -eq 0 ]; then
            export HOME="/root"
            cd /root 2>/dev/null || true
            exec /bin/bash --login
        fi
        if [ ${'$'}# -eq 0 ]; then
            echo "usage: sudo [command ...]" >&2
            exit 1
        fi
        exec "${'$'}@"
    """.trimIndent() + "\n"

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
