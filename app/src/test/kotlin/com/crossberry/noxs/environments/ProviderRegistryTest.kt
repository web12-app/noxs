package com.crossberry.noxs.environments

import com.crossberry.noxs.environments.model.CompatibilityLevel
import com.crossberry.noxs.environments.model.Environment
import com.crossberry.noxs.environments.model.EnvironmentStatus
import com.crossberry.noxs.environments.model.PackageManagerKind
import com.crossberry.noxs.environments.providers.ArchProvider
import com.crossberry.noxs.environments.providers.DistroSpecs
import com.crossberry.noxs.environments.providers.DebianProvider
import com.crossberry.noxs.environments.providers.EnvironmentConfigurator
import com.crossberry.noxs.environments.providers.KaliNetHunterProvider
import com.crossberry.noxs.environments.providers.ParrotProvider
import com.crossberry.noxs.environments.providers.ProviderRegistry
import com.crossberry.noxs.environments.providers.TermuxProvider
import com.crossberry.noxs.environments.providers.UbuntuProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Provider discovery, honest compatibility and package-manager translation
 * (spec §57: provider discovery, compatibility, package manager adapters,
 * terminal profile generation).
 */
class ProviderRegistryTest {

    private fun device(abi: String = "arm64-v8a", storage: Long = 16L * 1024 * 1024 * 1024) =
        DeviceProfile(abi, listOf(abi), 29, 4L * 1024 * 1024 * 1024, storage, true)

    @Test fun `all six environments are discovered`() {
        val providers = ProviderRegistry.createAll(termuxInstalled = false)
        assertEquals(
            listOf("debian", "ubuntu", "termux", "kali", "arch", "parrot"),
            providers.map { it.id }
        )
    }

    @Test fun `debian is the recommended default and is supported on arm64`() {
        val debian = ProviderRegistry.createAll(false).first { it.id == "debian" }
        assertTrue(debian.recommended)
        assertEquals(CompatibilityLevel.SUPPORTED, debian.checkCompatibility(device()).level)
    }

    @Test fun `kali offers full minimal nano with minimal default`() {
        val kali = KaliNetHunterProvider()
        val variants = kali.variants()
        assertEquals(listOf("minimal", "full", "nano"), variants.map { it.id })
        assertTrue(variants.first { it.isDefault }.id == "minimal")
        // Real official sizes (kali.download, captured at build time)
        assertEquals(137_313_840L, variants.first { it.id == "minimal" }.downloadBytesEstimate)
        assertEquals(1_764_123_932L, variants.first { it.id == "full" }.downloadBytesEstimate)
    }

    @Test fun `parrot reports honest limited compatibility and cannot install`() {
        val parrot = ParrotProvider()
        val report = parrot.checkCompatibility(device())
        assertEquals(CompatibilityLevel.LIMITED, report.level)
        assertFalse(parrot.canInstall(device()))
        assertTrue(variantless(parrot))
    }

    @Test fun `termux integration is detection-based not faked`() {
        val absent = TermuxProvider(isTermuxAppInstalled = false)
        assertEquals(CompatibilityLevel.UNSUPPORTED, absent.checkCompatibility(device()).level)
        assertFalse(absent.canInstall(device()))

        val present = TermuxProvider(isTermuxAppInstalled = true)
        assertEquals(CompatibilityLevel.LIMITED, present.checkCompatibility(device()).level)
        assertFalse(present.canInstall(device())) // never a rootfs install
        assertTrue(present.capabilities().contains(
            com.crossberry.noxs.environments.model.EnvironmentCapability.PACKAGE_MANAGER))
    }

    @Test fun `arch uses pacman and never apt`() {
        val arch = ArchProvider()
        assertEquals(PackageManagerKind.PACMAN, arch.distro.packageManager)
        assertEquals(CompatibilityLevel.SUPPORTED, arch.checkCompatibility(device()).level)
    }

    @Test fun `ubuntu sources use the ports archive`() {
        val sources = DistroSpecs.UBUNTU.aptSourcesHttps!!.joinToString("\n")
        assertTrue(sources.contains("ports.ubuntu.com/ubuntu-ports"))
        assertTrue(sources.contains("noble"))
        assertFalse(sources.contains("deb.debian.org"))
    }

    @Test fun `kali sources use the kali rolling repository`() {
        val sources = DistroSpecs.KALI.aptSourcesHttps!!.joinToString("\n")
        assertTrue(sources.contains("http.kali.org/kali"))
        assertTrue(sources.contains("kali-rolling"))
    }

    @Test fun `every rootfs image is https only`() {
        ProviderRegistry.createAll(false).forEach { provider ->
            (provider as? com.crossberry.noxs.environments.providers.RootfsTarballProvider)
                ?.let { rootfs ->
                    rootfs.variants().forEach { variant ->
                        val image = rootfs.imageFor(variant)
                        assertTrue(image.url.startsWith("https://"))
                        image.mirrors.forEach { assertTrue(it.startsWith("https://")) }
                        if (image.sumsUrl.isNotEmpty()) assertTrue(image.sumsUrl.startsWith("https://"))
                    }
                }
        }
    }

    @Test fun `terminal profiles reflect the actual environment prompt`() {
        val env = Environment(
            id = "kali", providerId = "kali", displayName = "Kali NetHunter Rootless",
            version = "current", architecture = "arm64-v8a", variant = "minimal",
            status = EnvironmentStatus.READY, storagePath = "/x", createdAt = 0, updatedAt = 0
        )
        val profile = KaliNetHunterProvider().terminalProfile(env)
        assertEquals("kali", profile.promptHost)
        assertEquals("noxs", profile.user)
        assertEquals("/bin/bash", profile.shell)
        assertEquals("/home/noxs", profile.home)
    }

    private fun variantless(provider: EnvironmentProvider) = provider.variants().isEmpty()
}

/**
 * Pure command translation (spec §28): UI never constructs raw
 * package-manager commands.
 */
class PackageManagerAdaptersTest {

    @Test fun `apt adapter translates generic operations`() {
        val apt = AptAdapter()
        assertEquals(listOf("/usr/bin/apt-get", "update", "--yes"), apt.update())
        assertEquals(
            listOf("/usr/bin/apt-get", "install", "--yes", "--no-install-recommends", "git", "python3"),
            apt.install("git", "python3")
        )
        assertEquals(listOf("/usr/bin/apt-get", "remove", "--yes", "vim"), apt.remove("vim"))
        assertEquals(listOf("/usr/bin/apt-cache", "search", "editor"), apt.search("editor"))
        assertEquals(listOf("/usr/bin/apt-get", "upgrade", "--yes"), apt.upgrade())
    }

    @Test fun `pacman adapter never emits apt commands`() {
        val pacman = PacmanAdapter()
        assertEquals(listOf("/usr/bin/pacman", "-Sy", "--noconfirm"), pacman.update())
        assertTrue(pacman.install("git").contains("pacman"))
        assertTrue(pacman.install("git").none { it.contains("apt") })
        assertEquals(listOf("/usr/bin/pacman", "-Syu", "--noconfirm"), pacman.upgrade())
    }

    @Test fun `termux pkg adapter uses pkg`() {
        val pkg = TermuxPkgAdapter()
        assertEquals(listOf("pkg", "update", "-y"), pkg.update())
        assertEquals(listOf("pkg", "install", "-y", "python"), pkg.install("python"))
    }

    @Test fun `adapter lookup by kind and optional component names`() {
        assertEquals(PackageManagerKind.APT, PackageManagerAdapters.forKind(PackageManagerKind.APT)!!.kind)
        assertEquals(PackageManagerKind.PACMAN, PackageManagerAdapters.forKind(PackageManagerKind.PACMAN)!!.kind)
        assertEquals(PackageManagerKind.PKG, PackageManagerAdapters.forKind(PackageManagerKind.PKG)!!.kind)
        assertEquals(null, PackageManagerAdapters.forKind(PackageManagerKind.NONE))
        assertEquals("git", PackageManagerAdapters.optionalComponentPackage("Git", PackageManagerKind.APT))
        assertEquals("python", PackageManagerAdapters.optionalComponentPackage("Python", PackageManagerKind.PACMAN))
        assertEquals("python3", PackageManagerAdapters.optionalComponentPackage("Python", PackageManagerKind.APT))
        assertEquals(null, PackageManagerAdapters.optionalComponentPackage("OpenCode", PackageManagerKind.APT))
    }
}

/**
 * Distro-specific configuration of an extracted rootfs (spec §22-§27): the
 * noxs user is created, the prompt reflects the environment (§61), apt
 * sources match the actual distro (§62) and Arch never receives apt config.
 */
class EnvironmentConfiguratorTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private fun configureEnv(distro: com.crossberry.noxs.environments.providers.DistroSpec): com.crossberry.noxs.runtime.NoxsPaths {
        val paths = com.crossberry.noxs.runtime.NoxsPaths(temporary.newFolder("env-${distro.id}"))
        paths.rootfs.mkdirs()
        // Minimal rootfs skeleton every configurator expects.
        java.io.File(paths.rootfs, "etc/passwd").writeText("root:x:0:0:root:/root:/bin/bash\n")
        java.io.File(paths.rootfs, "etc/shadow").writeText("root:!:19000:0:99999:7:::\n")
        java.io.File(paths.rootfs, "etc/group").writeText("root:x:0:\n")
        java.io.File(paths.rootfs, "etc/skel").mkdirs()
        java.io.File(paths.rootfs, "usr/bin").mkdirs()
        java.io.File(paths.rootfs, "etc/skel/.bashrc").writeText("# skel bashrc\n")
        if (distro.packageManager == PackageManagerKind.PACMAN) {
            java.io.File(paths.rootfs, "etc/pacman.conf").writeText("[options]\nHoldPkg = pacman glibc\n")
        } else {
            java.io.File(paths.rootfs, "usr/bin/apt-get").writeText("#!/bin/sh\n")
        }
        EnvironmentConfigurator(paths, distro).configure()
        return paths
    }

    @Test fun `kali environment gets kali sources and kali prompt`() {
        val paths = configureEnv(DistroSpecs.KALI)
        val sources = java.io.File(paths.rootfs, "etc/apt/sources.list.d/noxs.sources").readText()
        assertTrue(sources.contains("http.kali.org/kali"))
        assertTrue(sources.contains("kali-rolling"))
        val profile = java.io.File(paths.rootfs, "etc/profile.d/noxs.sh").readText()
        assertTrue(profile.contains("noxs@kali"))
        assertTrue(profile.contains("NOXS_ENV=kali"))
        assertTrue(java.io.File(paths.rootfs, "home/noxs").isDirectory)
        assertTrue(java.io.File(paths.rootfs, "usr/local/bin/noxs").isFile)
        assertTrue(java.io.File(paths.base, "environment.json").isFile)
    }

    @Test fun `arch environment never receives apt configuration`() {
        val paths = configureEnv(DistroSpecs.ARCH)
        assertFalse(java.io.File(paths.rootfs, "etc/apt/sources.list.d/noxs.sources").exists())
        val profile = java.io.File(paths.rootfs, "etc/profile.d/noxs.sh").readText()
        assertTrue(profile.contains("noxs@arch"))
        assertTrue(java.io.File(paths.rootfs, "etc/pacman.conf").isFile)
        val tag = EnvJson.readObject(java.io.File(paths.base, "environment.json").readText())
        assertEquals("ARCH", tag["family"])
        assertEquals("PACMAN", tag["packageManager"])
    }

    @Test fun `apt sources switch to https after ca verification`() {
        val paths = configureEnv(DistroSpecs.UBUNTU)
        val before = java.io.File(paths.rootfs, "etc/apt/sources.list.d/noxs.sources").readText()
        assertTrue(before.contains("http://ports.ubuntu.com"))
        EnvironmentConfigurator(paths, DistroSpecs.UBUNTU).configureAptSources(useHttps = true)
        val after = java.io.File(paths.rootfs, "etc/apt/sources.list.d/noxs.sources").readText()
        assertTrue(after.contains("https://ports.ubuntu.com"))
        assertFalse(after.contains("http://ports.ubuntu.com"))
    }

    @Test fun `noxs user is registered with sudo group`() {
        val paths = configureEnv(DistroSpecs.DEBIAN)
        val passwd = java.io.File(paths.rootfs, "etc/passwd").readText()
        assertTrue(passwd.contains("noxs:"))
        val group = java.io.File(paths.rootfs, "etc/group").readText()
        assertTrue(group.contains("sudo"))
        val sudoWrapper = java.io.File(paths.rootfs, "usr/local/bin/sudo")
        assertTrue(sudoWrapper.isFile)
        assertNotNull(passwd)
    }
}
