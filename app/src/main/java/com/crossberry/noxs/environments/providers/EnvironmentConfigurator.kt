/*
 * Noxs — original implementation.
 * EnvironmentConfigurator: userspace configuration shared by every rootfs
 * environment, parameterized per distro (spec §22-§27, §62).
 *
 * Reuses the battle-tested Noxs primitives (su/sudo wrappers, user creation,
 * FHS layout, noxs CLI) from RootfsConfigurator / NoxsCliTemplate and adds the
 * distro-specific parts: apt sources (Debian bookworm / Ubuntu ports / Kali
 * rolling), profile prompt (noxs@debian, noxs@ubuntu, noxs@kali, noxs@arch)
 * and the welcome banner. Arch never receives apt configuration.
 */
package com.crossberry.noxs.environments.providers

import com.crossberry.noxs.environments.model.EnvironmentFamily
import com.crossberry.noxs.environments.model.PackageManagerKind
import com.crossberry.noxs.runtime.NoxsCliTemplate
import com.crossberry.noxs.runtime.NoxsPaths
import com.crossberry.noxs.runtime.NoxsResources
import com.crossberry.noxs.runtime.ResourceQuotas
import com.crossberry.noxs.runtime.RootfsConfigurator
import com.crossberry.noxs.shared.NoxsConstants
import java.io.File

data class DistroSpec(
    val id: String,
    val promptHost: String,
    val banner: String,
    val family: EnvironmentFamily,
    val packageManager: PackageManagerKind,
    /** APT repository lines written to /etc/apt/sources.list.d/noxs.sources; null for non-apt. */
    val aptSources: List<String>? = null,
    /** HTTPS variants of the sources used after CA certificates are verified. */
    val aptSourcesHttps: List<String>? = null,
    val signedByKeyring: String = ""
)

object DistroSpecs {
    val DEBIAN = DistroSpec(
        id = "debian",
        promptHost = "debian",
        banner = "Noxs Debian 12 (bookworm)",
        family = EnvironmentFamily.DEBIAN,
        packageManager = PackageManagerKind.APT,
        aptSources = debianSources("http"),
        aptSourcesHttps = debianSources("https"),
        signedByKeyring = "/usr/share/keyrings/debian-archive-keyring.gpg"
    )

    val UBUNTU = DistroSpec(
        id = "ubuntu",
        promptHost = "ubuntu",
        banner = "Noxs Ubuntu 24.04 LTS (noble)",
        family = EnvironmentFamily.DEBIAN,
        packageManager = PackageManagerKind.APT,
        aptSources = ubuntuSources("http"),
        aptSourcesHttps = ubuntuSources("https"),
        signedByKeyring = "/usr/share/keyrings/ubuntu-archive-keyring.gpg"
    )

    val KALI = DistroSpec(
        id = "kali",
        promptHost = "kali",
        banner = "Noxs Kali NetHunter Rootless (kali-rolling)",
        family = EnvironmentFamily.DEBIAN,
        packageManager = PackageManagerKind.APT,
        aptSources = kaliSources("http"),
        aptSourcesHttps = kaliSources("https"),
        signedByKeyring = "/usr/share/keyrings/kali-archive-keyring.gpg"
    )

    val ARCH = DistroSpec(
        id = "arch",
        promptHost = "arch",
        banner = "Noxs Arch Linux ARM (rolling)",
        family = EnvironmentFamily.ARCH,
        packageManager = PackageManagerKind.PACMAN
    )

    private fun debianSources(scheme: String): List<String> = listOf(
        "Types: deb",
        "URIs: $scheme://deb.debian.org/debian",
        "Suites: bookworm bookworm-updates",
        "Components: main contrib non-free non-free-firmware",
        "Signed-By: /usr/share/keyrings/debian-archive-keyring.gpg",
        "",
        "Types: deb",
        "URIs: $scheme://security.debian.org/debian-security",
        "Suites: bookworm-security",
        "Components: main contrib non-free non-free-firmware",
        "Signed-By: /usr/share/keyrings/debian-archive-keyring.gpg"
    )

    /** Ubuntu arm64 lives on the ports archive (both amd64-free and arm64-correct). */
    private fun ubuntuSources(scheme: String): List<String> = listOf(
        "Types: deb",
        "URIs: $scheme://ports.ubuntu.com/ubuntu-ports",
        "Suites: noble noble-updates noble-backports",
        "Components: main restricted universe multiverse",
        "Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg",
        "",
        "Types: deb",
        "URIs: $scheme://ports.ubuntu.com/ubuntu-ports",
        "Suites: noble-security",
        "Components: main restricted universe multiverse",
        "Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg"
    )

    private fun kaliSources(scheme: String): List<String> = listOf(
        "Types: deb",
        "URIs: $scheme://http.kali.org/kali",
        "Suites: kali-rolling",
        "Components: main contrib non-free non-free-firmware",
        "Signed-By: /usr/share/keyrings/kali-archive-keyring.gpg"
    )
}

class EnvironmentConfigurator(private val paths: NoxsPaths, private val distro: DistroSpec) {

    /** Configures the extracted rootfs into a working Noxs environment. */
    fun configure() {
        val rootfs = paths.rootfs

        // Real directories before FHS links.
        listOf(
            "usr/bin", "usr/sbin", "usr/lib", "usr/local/bin", "usr/local/lib",
            "usr/local/lib/noxs", "usr/share", "boot", "dev", "etc", "etc/noxs",
            "etc/profile.d", "home", "home/noxs", "media", "mnt", "opt", "proc",
            "root", "run", "srv", "sys", "tmp",
            "var/cache", "var/lib", "var/lib/dpkg", "var/lib/dpkg/alternatives",
            "var/lib/dpkg/info", "var/lib/dpkg/updates", "var/lib/dpkg/triggers",
            "var/log", "var/log/noxs", "var/run", "var/run/noxs", "var/run/noxs/host",
            "var/spool"
        ).plus(if (distro.packageManager == PackageManagerKind.APT) listOf(
            "etc/apt", "etc/apt/apt.conf.d", "etc/apt/sources.list.d",
            "var/cache/apt/archives/partial", "var/lib/apt/lists/partial", "var/log/apt"
        ) else emptyList()).forEach { rel ->
            val f = File(rootfs, rel)
            if (!java.nio.file.Files.isSymbolicLink(f.toPath())) f.mkdirs()
        }

        // FHS top-level links.
        RootfsConfigurator.ensureFhsLink(rootfs, "bin", "usr/bin")
        RootfsConfigurator.ensureFhsLink(rootfs, "sbin", "usr/sbin")
        RootfsConfigurator.ensureFhsLink(rootfs, "lib", "usr/lib")

        // Resource quotas (bound by ProotLauncher).
        if (!paths.noxsResourcesConf.isFile) {
            writeFile(paths.noxsResourcesConf, ResourceQuotas().serialize())
        }

        // profile.d/noxs.sh — prompt reflects the actual environment (spec §61).
        writeFile(File(rootfs, "etc/profile.d/noxs.sh"), profileScript())

        // /etc/environment, motd, DNS, hosts.
        writeFile(File(rootfs, "etc/environment"), "NOXS=1\nNOXS_USER=${NoxsConstants.DEFAULT_USER}\n")
        writeFile(File(rootfs, "etc/motd"), motd())
        if (!File(rootfs, "etc/resolv.conf").isFile || File(rootfs, "etc/resolv.conf").length() == 0L) {
            writeFile(File(rootfs, "etc/resolv.conf"), "nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        }
        writeFile(File(rootfs, "etc/hosts"), "127.0.0.1 localhost\n::1 localhost ip6-localhost\n")
        writeFile(File(rootfs, "etc/hostname"), "${distro.promptHost}\n")

        // Package-manager configuration.
        when (distro.packageManager) {
            PackageManagerKind.APT -> {
                configureAptSources(useHttps = false)
                writeFile(File(rootfs, "etc/apt/apt.conf.d/70noxs"), APT_CONF)
                RootfsConfigurator.repairDpkgPermissions(paths)
            }
            PackageManagerKind.PACMAN -> {
                // Arch ships its own pacman.conf; keep the stock configuration.
                val pacman = File(rootfs, "etc/pacman.conf")
                if (!pacman.isFile) {
                    throw IllegalStateException("Arch rootfs is missing etc/pacman.conf — wrong archive?")
                }
            }
            else -> Unit
        }

        // `noxs` CLI + storage setup + hello (distro-agnostic bash tools).
        val cli = File(rootfs, "usr/local/bin/noxs")
        writeFile(cli, RootfsConfigurator.NOXS_CLI)
        cli.setExecutable(true, false)
        val storageSetup = File(rootfs, "usr/local/bin/noxs-setup-storage")
        writeFile(storageSetup, NoxsCliTemplate.STORAGE_SETUP)
        storageSetup.setExecutable(true, false)
        File(rootfs, "usr/local/bin/noxs-hello").let {
            writeFile(it, "#!/bin/sh\necho 'Noxs ${distro.banner} — environment ready'\n")
            it.setExecutable(true, false)
        }

        // FHS links recreation script (run inside the sandbox at shell start).
        val linkScript = File(rootfs, "usr/local/lib/noxs-links.sh")
        writeFile(linkScript, FHS_LINKS_SCRIPT)
        linkScript.setExecutable(true, false)

        // Android proot-compatible su/sudo (password-aware, sandbox-scoped).
        RootfsConfigurator.installNoxsSuSudo(rootfs)

        // noxs user + bashrc (prompt host label parameterized).
        RootfsConfigurator.ensureNoxsUser(rootfs, distro.promptHost, distro.banner)

        // Runtime state dirs (host side too: proot binds paths.run).
        paths.rootfsRun.mkdirs()
        paths.rootfsVarRun.mkdirs()
        paths.rootfsNoxsRun.mkdirs()
        paths.rootfsHostRun.mkdirs()
        paths.run.mkdirs()
        File(paths.run, "keep").writeText("host run dir\n")

        // Environment tag consumed by the runtime (family-aware gating, spec §62).
        writeFile(File(paths.base, "environment.json"),
            com.crossberry.noxs.environments.model.EnvJson.write(
                linkedMapOf(
                    "id" to distro.id,
                    "family" to distro.family.name,
                    "packageManager" to distro.packageManager.name,
                    "promptHost" to distro.promptHost
                )
            )
        )
    }

    /** Switches apt sources to HTTPS after the CA bundle is verified (spec: never downgrade). */
    fun configureAptSources(useHttps: Boolean) {
        val lines = if (useHttps) distro.aptSourcesHttps else distro.aptSources
        if (lines == null) return
        val sourceDir = File(paths.rootfs, "etc/apt/sources.list.d")
        sourceDir.mkdirs()
        // Remove every other .list/.sources definition (single source of truth).
        sourceDir.listFiles()?.filter {
            it.name.endsWith(".list") || (it.name.endsWith(".sources") && it.name != "noxs.sources")
        }?.forEach { it.delete() }
        File(sourceDir, "noxs.sources").writeText(lines.joinToString("\n") + "\n")
        // Stock Debian/Ubuntu/Kali definitions would shadow the Noxs sources.
        File(paths.rootfs, "etc/apt/sources.list").takeIf { it.isFile }?.delete()
    }

    private fun profileScript(): String = """
        # /etc/profile.d/noxs.sh — Noxs environment integration
        export NOXS=1
        export NOXS_USER=${NoxsConstants.DEFAULT_USER}
        export NOXS_RUN_DIR=${NoxsConstants.NOXS_RUN_DIR}
        export NOXS_HOME=${NoxsConstants.DEFAULT_USER_HOME}
        export NOXS_ENV=${distro.id}
        export PATH="/usr/local/bin:${'$'}PATH"
        export PS1='noxs@${distro.promptHost}:\w${'$'} '
        export PS0=${'$'}'\033]133;C\a'
        PROMPT_COMMAND='printf "\033]133;A\007"'
        if [ -x /usr/local/lib/noxs-links.sh ]; then
            /usr/local/lib/noxs-links.sh >/dev/null 2>&1 || true
        fi
        if [ -r /etc/noxs/resources.conf ]; then
            . /etc/noxs/resources.conf 2>/dev/null || true
            [ -n "${'$'}MAX_PROCESSES" ] && ulimit -u "${'$'}MAX_PROCESSES" 2>/dev/null || true
            [ -n "${'$'}MAX_OPEN_FILES" ] && ulimit -n "${'$'}MAX_OPEN_FILES" 2>/dev/null || true
        fi
    """.trimIndent()

    private fun motd(): String = """
        ${distro.banner}

        * All operations run inside the Android app sandbox — you have full
          control of this Noxs Linux environment, not of the Android device.
        * `sudo` asks for your noxs password and manages THIS environment.
        * `noxs help` lists the Noxs tools.
        * `noxs code` manages the integrated code-server (where available).

        Thank you for using Noxs.
    """.trimIndent() + "\n"

    private fun writeFile(f: File, content: String) {
        f.parentFile?.mkdirs()
        f.writeText(content)
    }

    private val FHS_LINKS_SCRIPT = """
        #!/bin/sh
        # Recreate FHS compatibility symlinks inside the Noxs sandbox.
        [ -e /bin ] || ln -s usr/bin /bin
        [ -e /sbin ] || ln -s usr/sbin /sbin
        [ -e /lib ] || ln -s usr/lib /lib
        [ -e /lib64 ] || ln -s usr/lib64 /lib64 2>/dev/null
        [ -e /var/run ] || ln -s /run /var/run
        exit 0
    """.trimIndent()

    private val APT_CONF = """
        APT::Install-Recommends "false";
        APT::Install-Suggests "false";
        Acquire::Languages "none";
        Dir::Cache::archives "/var/cache/apt/archives";
    """.trimIndent()
}
