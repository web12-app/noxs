/*
 * Noxs — original implementation.
 * Builtin environment providers (spec §22-§27).
 *
 * Debian 12 (bookworm) is the one and only environment: installed through
 * the proven NoxsInstaller pipeline (staged downloads, TarGuard extraction,
 * signed APT bootstrap) from the pinned debuerreotype artifact that the CI
 * release manages. There is no variant matrix to maintain.
 */
package com.crossberry.noxs.environments.providers

import com.crossberry.noxs.environments.DeviceProfile
import com.crossberry.noxs.environments.EnvironmentProvider
import com.crossberry.noxs.environments.model.EnvironmentCapability
import com.crossberry.noxs.environments.model.CompatibilityReport
import com.crossberry.noxs.environments.model.EnvironmentVariant

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

// NOTE: Noxs ships exactly ONE environment — Debian 12 (bookworm). The
// experimental multi-OS providers (Ubuntu, Kali, Arch, Parrot, Termux) were
// removed: one proven environment, one install path, no variant matrix to
// maintain. ProviderRegistry keeps the discovery seam so a future second
// environment can be added deliberately.

// ------------------------------------------------------------------ registry

/** Central provider discovery (spec §2, §57). */
object ProviderRegistry {

    fun createAll(termuxInstalled: Boolean): List<EnvironmentProvider> = listOf(
        DebianProvider()
    )

    fun findById(providers: List<EnvironmentProvider>, id: String): EnvironmentProvider? =
        providers.firstOrNull { it.id == id }
}

object StorageRequirements {
    fun forVariant(downloadBytes: Long, extractedBytes: Long): Long =
        com.crossberry.noxs.environments.model.StoragePlan.compute(downloadBytes, extractedBytes).totalRequired
}
