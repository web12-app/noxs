/*
 * Noxs — original implementation.
 * Builtin environment providers (spec §22-§27).
 *
 * Download metadata uses ONLY official HTTPS sources, verified live at
 * install time from the distros' own checksum files, with pinned fallbacks
 * captured from the same official sources at build time:
 *
 *   Debian  — bootstrap.manifest (pinned debuerreotype artifact, CI-managed)
 *   Ubuntu  — cdimage.ubuntu.com ubuntu-base + official SHA256SUMS (live)
 *   Kali    — kali.download NetHunter rootless rootfs + official SHA256SUMS (live)
 *   Arch    — de3.mirror.archlinuxarm.org (official HTTPS mirror) + official MD5
 *   Parrot  — honest LIMITED: no official ARM64 rootfs image exists
 *   Termux  — detection-only integration (never fakes a Debian rootfs, §24)
 */
package com.crossberry.noxs.environments.providers

import com.crossberry.noxs.environments.DeviceProfile
import com.crossberry.noxs.environments.EnvironmentProvider
import com.crossberry.noxs.environments.model.CompatibilityCheck
import com.crossberry.noxs.environments.model.CompatibilityLevel
import com.crossberry.noxs.environments.model.CompatibilityReport
import com.crossberry.noxs.environments.model.EnvironmentCapability
import com.crossberry.noxs.environments.model.EnvironmentFamily
import com.crossberry.noxs.environments.model.EnvironmentMetadata
import com.crossberry.noxs.environments.model.EnvironmentVariant
import com.crossberry.noxs.environments.model.PackageManagerKind
import com.crossberry.noxs.environments.download.ChecksumAlgorithm

// ------------------------------------------------------------------- Debian

/**
 * Debian 12 Bookworm — the proven default (spec §22, §63). Installation runs
 * the existing battle-tested NoxsInstaller pipeline (staged downloads, TarGuard
 * extraction, signed APT bootstrap) through the SetupTaskManager.
 */
class DebianProvider : EnvironmentProvider {
    override val id = "debian"
    override val displayName = "Debian 12"
    override val description = "Stable general-purpose Linux"
    override val recommended = true

    /**
     * Debian installs through the proven NoxsInstaller pipeline (staged
     * downloads, TarGuard extraction, signed APT bootstrap) — the exact path
     * existing Noxs installations use (spec §22, §64 backward compatibility).
     */
    override suspend fun runInstall(ctx: com.crossberry.noxs.environments.InstallContext,
                                    variant: EnvironmentVariant): Boolean {
        val installer = com.crossberry.noxs.runtime.NoxsInstaller(
            ctx.context, ctx.paths, ctx.launcher, ctx.isCancelled
        ).apply {
            promptHostLabel = "debian"
            distroBanner = "Noxs Debian 12 (bookworm)"
        }
        val progress = object : com.crossberry.noxs.runtime.NoxsInstaller.Progress {
            override fun onStep(step: Int, titleRes: Int, detail: String) {
                val state = when (step) {
                    5 -> com.crossberry.noxs.environments.model.SetupState.DOWNLOADING
                    6 -> com.crossberry.noxs.environments.model.SetupState.EXTRACTING
                    7, 9 -> com.crossberry.noxs.environments.model.SetupState.CONFIGURING
                    8 -> com.crossberry.noxs.environments.model.SetupState.CREATING_USER
                    10 -> com.crossberry.noxs.environments.model.SetupState.VERIFYING_ENVIRONMENT
                    else -> com.crossberry.noxs.environments.model.SetupState.PREPARING
                }
                val op = detail.ifBlank { ctx.context.getString(titleRes) }
                ctx.onStage(state, op)
            }
            override fun onProgressBytes(downloaded: Long, total: Long) =
                ctx.onDownloadProgress(downloaded, total)
            override fun onExtracted(entries: Long) = ctx.onExtractProgress(entries)
            override fun onLog(line: String) = ctx.onLog(line)
            override fun onPasswordRequired(): CharArray? = ctx.passwordProvider()
        }
        return when (val result = installer.install(progress)) {
            is com.crossberry.noxs.runtime.NoxsInstaller.InstallResult.Success -> true
            is com.crossberry.noxs.runtime.NoxsInstaller.InstallResult.Failure ->
                throw java.io.IOException(
                    ctx.context.getString(result.userMessageRes) + ": " + result.detail
                )
            com.crossberry.noxs.runtime.NoxsInstaller.InstallResult.Cancelled ->
                throw com.crossberry.noxs.environments.SetupCancelledException()
        }
    }

    override fun capabilities() = setOf(
        EnvironmentCapability.TERMINAL, EnvironmentCapability.FILES,
        EnvironmentCapability.PROCESSES, EnvironmentCapability.PACKAGE_MANAGER,
        EnvironmentCapability.SUDO, EnvironmentCapability.DOCKER,
        EnvironmentCapability.CODE_SERVER, EnvironmentCapability.BACKGROUND_TASKS
    )

    override fun variants(): List<EnvironmentVariant> = listOf(
        EnvironmentVariant(
            id = "bookworm",
            name = "Bookworm (stable)",
            description = "Debian 12 stable — the recommended Noxs environment",
            downloadBytesEstimate = 48_389_910L,
            extractedBytesEstimate = 190_000_000L,
            isDefault = true
        )
    )

    override fun checkCompatibility(device: DeviceProfile): CompatibilityReport =
        com.crossberry.noxs.environments.CompatibilityEngine.report(
            device = device,
            requiredStorageBytes = StorageRequirements.forVariant(48_389_910L, 190_000_000L)
        )
}

// ------------------------------------------------------------------- Ubuntu

class UbuntuProvider : RootfsTarballProvider() {
    override val distro = DistroSpecs.UBUNTU
    override val id = "ubuntu"
    override val displayName = "Ubuntu"
    override val description = "Developer-friendly Linux"

    init {
        registerVariant(
            EnvironmentVariant(
                id = "noble",
                name = "24.04 LTS (noble)",
                description = "Ubuntu base userspace for ARM64",
                downloadBytesEstimate = 29_936_675L,
                extractedBytesEstimate = 85_000_000L,
                isDefault = true
            ),
            ImageSpec(
                url = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz",
                fileName = "ubuntu-base-24.04.5-base-arm64.tar.gz",
                format = "tar.gz",
                pinnedChecksum = "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2",
                algorithm = ChecksumAlgorithm.SHA256,
                sumsUrl = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/SHA256SUMS",
                downloadBytesEstimate = 29_936_675L,
                extractedBytesEstimate = 85_000_000L
            )
        )
    }
}

// --------------------------------------------------------------------- Kali

/**
 * Kali NetHunter Rootless (spec §8, §9, §25). The official installer workflow
 * is used only as the REFERENCE: Noxs implements architecture detection,
 * image selection (full/minimal/nano), download, checksum verification,
 * extraction and rootless setup as explicit Noxs-controlled operations —
 * the official shell script is never downloaded or executed (spec §66).
 */
class KaliNetHunterProvider : RootfsTarballProvider() {
    override val distro = DistroSpecs.KALI
    override val id = "kali"
    override val displayName = "Kali NetHunter Rootless"
    override val description = "Security-focused Linux userspace"

    init {
        registerVariant(
            EnvironmentVariant(
                id = "minimal",
                name = "Minimal",
                description = "Core Kali tools — balanced size (default)",
                downloadBytesEstimate = 137_313_840L,
                extractedBytesEstimate = 780_000_000L,
                isDefault = true
            ),
            ImageSpec(
                url = "https://kali.download/nethunter-images/current/rootfs/kali-nethunter-rootfs-minimal-arm64.tar.xz",
                fileName = "kali-nethunter-rootfs-minimal-arm64.tar.xz",
                format = "tar.xz",
                pinnedChecksum = "d6403a5da175df325611d23af4b92330856059c45454eced7f4cdf3ca6df2e4e",
                algorithm = ChecksumAlgorithm.SHA256,
                sumsUrl = "https://kali.download/nethunter-images/current/rootfs/SHA256SUMS",
                downloadBytesEstimate = 137_313_840L,
                extractedBytesEstimate = 780_000_000L
            )
        )
        registerVariant(
            EnvironmentVariant(
                id = "full",
                name = "Full",
                description = "The complete Kali toolset — large download",
                downloadBytesEstimate = 1_764_123_932L,
                extractedBytesEstimate = 5_500_000_000L,
                isDefault = false
            ),
            ImageSpec(
                url = "https://kali.download/nethunter-images/current/rootfs/kali-nethunter-rootfs-full-arm64.tar.xz",
                fileName = "kali-nethunter-rootfs-full-arm64.tar.xz",
                format = "tar.xz",
                pinnedChecksum = "fd108959bd9252b03d1ce3d573afaef2e372e4a0f137d256ba5f92e41d62ca6e",
                algorithm = ChecksumAlgorithm.SHA256,
                sumsUrl = "https://kali.download/nethunter-images/current/rootfs/SHA256SUMS",
                downloadBytesEstimate = 1_764_123_932L,
                extractedBytesEstimate = 5_500_000_000L
            )
        )
        registerVariant(
            EnvironmentVariant(
                id = "nano",
                name = "Nano",
                description = "Smallest Kali userspace — only the essentials",
                downloadBytesEstimate = 198_274_248L,
                extractedBytesEstimate = 900_000_000L,
                isDefault = false
            ),
            ImageSpec(
                url = "https://kali.download/nethunter-images/current/rootfs/kali-nethunter-rootfs-nano-arm64.tar.xz",
                fileName = "kali-nethunter-rootfs-nano-arm64.tar.xz",
                format = "tar.xz",
                pinnedChecksum = "2ea1c50446b9b35506c4b1cc84a731c752892baafe0dc2a1332e460c2d2a1e4e",
                algorithm = ChecksumAlgorithm.SHA256,
                sumsUrl = "https://kali.download/nethunter-images/current/rootfs/SHA256SUMS",
                downloadBytesEstimate = 198_274_248L,
                extractedBytesEstimate = 900_000_000L
            )
        )
    }

    override fun capabilities() = super.capabilities() + setOf(
        EnvironmentCapability.GUI_KE_X // KeX GUI is optional in the official workflow
    )
}

// --------------------------------------------------------------------- Arch

class ArchProvider : RootfsTarballProvider() {
    override val distro = DistroSpecs.ARCH
    override val id = "arch"
    override val displayName = "Arch Linux"
    override val description = "Rolling-release Linux"

    init {
        registerVariant(
            EnvironmentVariant(
                id = "aarch64",
                name = "Arch Linux ARM (aarch64)",
                description = "Rolling release with pacman",
                downloadBytesEstimate = 829_367_415L,
                extractedBytesEstimate = 2_100_000_000L,
                isDefault = true
            ),
            ImageSpec(
                url = "https://de3.mirror.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz",
                mirrors = listOf(
                    "https://mirror.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz"
                ),
                fileName = "ArchLinuxARM-aarch64-latest.tar.gz",
                format = "tar.gz",
                pinnedChecksum = "23eec86365b24f7913c403e8f4e8719b",
                algorithm = ChecksumAlgorithm.MD5, // official Arch ARM checksum
                sumsUrl = "https://de3.mirror.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz.md5",
                downloadBytesEstimate = 829_367_415L,
                extractedBytesEstimate = 2_100_000_000L
            )
        )
    }

    override fun checkCompatibility(device: DeviceProfile): CompatibilityReport {
        val base = super.checkCompatibility(device)
        // Honest note: Arch ARM's rolling toolchain expects more free RAM.
        return if (base.level == CompatibilityLevel.SUPPORTED && device.availableRamBytes in 1 until 1_500_000_000L) {
            CompatibilityReport(
                CompatibilityLevel.LIMITED,
                base.checks + CompatibilityCheck("Workload", null, "rolling-release builds can be RAM-heavy"),
                base.reasons
            )
        } else base
    }
}

// ------------------------------------------------------------------- Parrot

/**
 * Parrot OS (spec §27): "Do not assume every Parrot image is compatible with
 * every Android architecture. Check actual provider metadata." Parrot does
 * not publish an official ARM64 rootfs tarball — Noxs reports this honestly
 * instead of faking an install (spec §5 example: "Parrot: ⚠ Limited").
 */
class ParrotProvider : EnvironmentProvider {
    override val id = "parrot"
    override val displayName = "Parrot OS"
    override val description = "Security/privacy-focused Linux"

    override fun capabilities() = setOf(EnvironmentCapability.TERMINAL)

    override fun variants(): List<EnvironmentVariant> = emptyList()

    override fun checkCompatibility(device: DeviceProfile): CompatibilityReport = CompatibilityReport(
        level = CompatibilityLevel.LIMITED,
        checks = listOf(
            CompatibilityCheck("CPU architecture (${device.abi})", null,
                "no official ARM64 rootfs image published by Parrot"),
            CompatibilityCheck("Official image", false,
                "Parrot publishes no installable rootfs for Android/proot; community images only"),
            CompatibilityCheck("Status", null,
                "not installable in this Noxs version — shown honestly instead of faked")
        ),
        reasons = listOf("No official Parrot ARM64 rootfs image is available")
    )

    override fun canInstall(device: DeviceProfile): Boolean = false
}

// ------------------------------------------------------------------- Termux

/**
 * Termux (spec §24) is fundamentally different: an Android-native userspace
 * with its own package manager. Noxs does NOT pretend it is a Debian rootfs.
 * Integration is detection-based: if the Termux app is present Noxs reports
 * external integration; otherwise it is honestly unavailable.
 */
class TermuxProvider(private val isTermuxAppInstalled: Boolean) : EnvironmentProvider {
    override val id = "termux"
    override val displayName = "Termux"
    override val description = "Android-native terminal"

    override fun capabilities(): Set<EnvironmentCapability> =
        if (isTermuxAppInstalled) setOf(
            EnvironmentCapability.TERMINAL, EnvironmentCapability.PACKAGE_MANAGER
        ) else emptySet()

    override fun variants(): List<EnvironmentVariant> = emptyList()

    override fun checkCompatibility(device: DeviceProfile): CompatibilityReport {
        val integration = when {
            isTermuxAppInstalled -> CompatibilityCheck(
                "Termux app", true, "external integration — pkg available inside Termux")
            else -> CompatibilityCheck(
                "Termux app", false, "not installed — install Termux from F-Droid first")
        }
        val level = if (isTermuxAppInstalled) CompatibilityLevel.LIMITED else CompatibilityLevel.UNSUPPORTED
        return CompatibilityReport(
            level = level,
            checks = listOf(
                integration,
                CompatibilityCheck("Noxs integration mode", null,
                    if (isTermuxAppInstalled) "external (Termux runs its own userspace)"
                    else "unavailable"),
                CompatibilityCheck("Rootfs install", false,
                    "Termux is not a Debian root filesystem — Noxs does not fake one (spec §24)")
            ),
            reasons = if (isTermuxAppInstalled) emptyList()
            else listOf("Termux app is not installed on this device")
        )
    }

    override fun canInstall(device: DeviceProfile): Boolean = false
}

// ------------------------------------------------------------------ registry

/** Central provider discovery (spec §2, §57). */
object ProviderRegistry {

    fun createAll(termuxInstalled: Boolean): List<EnvironmentProvider> = listOf(
        DebianProvider(),
        UbuntuProvider(),
        TermuxProvider(termuxInstalled),
        KaliNetHunterProvider(),
        ArchProvider(),
        ParrotProvider()
    )

    fun findById(providers: List<EnvironmentProvider>, id: String): EnvironmentProvider? =
        providers.firstOrNull { it.id == id }
}

object StorageRequirements {
    fun forVariant(downloadBytes: Long, extractedBytes: Long): Long =
        com.crossberry.noxs.environments.model.StoragePlan.compute(downloadBytes, extractedBytes).totalRequired
}
