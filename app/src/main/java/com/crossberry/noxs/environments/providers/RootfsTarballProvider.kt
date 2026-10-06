/*
 * Noxs — original implementation.
 * RootfsTarballProvider: shared install pipeline for tarball-based Linux
 * environments (spec §66 translated into explicit Noxs-controlled operations:
 * detect → download → verify → extract → configure → create user → configure
 * shell → verify → terminal profile → launch).
 *
 * Noxs owns the complete lifecycle. Downloaded archives are NEVER executed —
 * only their contents are extracted through SafeExtractor after checksum
 * verification (spec §48, §66).
 */
package com.crossberry.noxs.environments.providers

import com.crossberry.noxs.environments.DeviceProfile
import com.crossberry.noxs.environments.EnvironmentProvider
import com.crossberry.noxs.environments.InstallContext
import com.crossberry.noxs.environments.StageWeights
import com.crossberry.noxs.environments.download.ChecksumAlgorithm
import com.crossberry.noxs.environments.download.DownloadManager
import com.crossberry.noxs.environments.download.DownloadSpec
import com.crossberry.noxs.environments.model.CompatibilityCheck
import com.crossberry.noxs.environments.model.CompatibilityReport
import com.crossberry.noxs.environments.model.Environment
import com.crossberry.noxs.environments.model.EnvironmentCapability
import com.crossberry.noxs.environments.model.EnvironmentVariant
import com.crossberry.noxs.environments.model.SetupState
import com.crossberry.noxs.environments.model.StoragePlan
import com.crossberry.noxs.environments.model.TerminalProfile
import com.crossberry.noxs.runtime.NoxsAptBootstrapper
import com.crossberry.noxs.runtime.NoxsPaths
import com.crossberry.noxs.shared.NoxsLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Official image metadata for one variant. Checksums come from the official SUMS URL. */
data class ImageSpec(
    val url: String,
    val mirrors: List<String> = emptyList(),
    val fileName: String,
    val format: String,
    /** Fallback pinned checksum (captured from the official SUMS at build time). */
    val pinnedChecksum: String = "",
    val algorithm: ChecksumAlgorithm = ChecksumAlgorithm.SHA256,
    /** Official SUMS file fetched live at install time (fresh trusted metadata). */
    val sumsUrl: String = "",
    /** Honest compressed/extracted estimates for the storage pre-check. */
    val downloadBytesEstimate: Long,
    val extractedBytesEstimate: Long
)

abstract class RootfsTarballProvider : EnvironmentProvider {

    abstract val distro: DistroSpec

    private val variantsByImage = linkedMapOf<String, ImageSpec>()

    protected fun registerVariant(variant: EnvironmentVariant, image: ImageSpec) {
        variantsByImage[variant.id] = image
    }

    fun imageFor(variant: EnvironmentVariant): ImageSpec =
        variantsByImage[variant.id] ?: variantsByImage.values.first()

    override fun variants(): List<EnvironmentVariant> = variantsByImage.entries.mapIndexed { index, (id, image) ->
        EnvironmentVariant(
            id = id,
            name = id.replaceFirstChar { it.uppercase() },
            description = "Official ${distro.id.replaceFirstChar { it.uppercase() }} $id image",
            downloadBytesEstimate = image.downloadBytesEstimate,
            extractedBytesEstimate = image.extractedBytesEstimate,
            isDefault = index == 0
        )
    }

    override fun checkCompatibility(device: DeviceProfile): CompatibilityReport {
        val image = variantsByImage.values.firstOrNull()
            ?: ImageSpec(url = "", fileName = "", format = "", downloadBytesEstimate = 0L, extractedBytesEstimate = 0L)
        val plan = StoragePlan.compute(image.downloadBytesEstimate, image.extractedBytesEstimate)
        return com.crossberry.noxs.environments.CompatibilityEngine.report(
            device = device,
            requiredStorageBytes = plan.totalRequired,
            extraChecks = extraCompatibilityChecks(device)
        )
    }

    override fun capabilities(): Set<EnvironmentCapability> = when (distro.packageManager) {
        com.crossberry.noxs.environments.model.PackageManagerKind.APT -> setOf(
            EnvironmentCapability.TERMINAL, EnvironmentCapability.FILES,
            EnvironmentCapability.PROCESSES, EnvironmentCapability.PACKAGE_MANAGER,
            EnvironmentCapability.SUDO, EnvironmentCapability.CODE_SERVER,
            EnvironmentCapability.BACKGROUND_TASKS
        )
        com.crossberry.noxs.environments.model.PackageManagerKind.PACMAN -> setOf(
            EnvironmentCapability.TERMINAL, EnvironmentCapability.FILES,
            EnvironmentCapability.PROCESSES, EnvironmentCapability.PACKAGE_MANAGER,
            EnvironmentCapability.SUDO, EnvironmentCapability.BACKGROUND_TASKS
        )
        else -> setOf(EnvironmentCapability.TERMINAL)
    }

    protected open fun extraCompatibilityChecks(device: DeviceProfile): List<CompatibilityCheck> = emptyList()

    // ------------------------------------------------------------ pipeline

    /**
     * Runs the complete install for [variant]. Every stage is reported through
     * [ctx] so the UI, task list and notification share one source of truth.
     */
    override suspend fun runInstall(ctx: InstallContext, variant: EnvironmentVariant): Boolean =
        withContext(Dispatchers.IO) {
            val image = imageFor(variant)
            ctx.checkCancelled()

            // PREPARING — storage pre-check BEFORE any download (spec §6).
            ctx.onStage(SetupState.PREPARING, "Preparing storage")
            ctx.paths.ensureBaseDirs()
            val plan = StoragePlan.compute(image.downloadBytesEstimate, image.extractedBytesEstimate)
            val usable = ctx.paths.base.usableSpace
            if (usable in 1 until plan.totalRequired) {
                throw IOException(
                    "Not enough storage: required ${plan.totalRequired / (1024 * 1024)} MB, available ${usable / (1024 * 1024)} MB"
                )
            }
            ctx.onLog("storage check passed: ${plan.totalRequired / (1024 * 1024)} MB required")

            // DOWNLOAD — streaming, resumable, verified (spec §10, §11).
            ctx.onStage(SetupState.DOWNLOADING, "Downloading ${distro.displayName}")
            val downloader = DownloadManager(ctx.paths.cache)
            val spec = DownloadSpec(
                taskId = ctx.paths.base.name + "-" + variant.id,
                url = image.url,
                mirrors = image.mirrors,
                destFile = File(ctx.paths.cache, image.fileName),
                expectedChecksum = image.pinnedChecksum,
                algorithm = image.algorithm,
                checksumUrl = image.sumsUrl,
                checksumFileName = image.fileName,
                expectedSizeBytes = image.downloadBytesEstimate
            )
            val archive = downloader.download(spec, object :
                com.crossberry.noxs.environments.download.DownloadListener {
                override fun onProgress(bytesDone: Long, totalBytes: Long) {
                    ctx.onDownloadProgress(bytesDone, totalBytes)
                }
                override fun onLog(line: String) = ctx.onLog(line)
                override fun onStage(stage: String) = Unit
                override fun isCancelled(): Boolean = ctx.isCancelled()
            })
            ctx.checkCancelled()

            // VERIFY — explicit stage even though DownloadManager already verified
            // (the UI shows the honest "✓ Verification successful" step, spec §12).
            ctx.onStage(SetupState.VERIFYING, "Verifying image checksum")
            ctx.onLog("verification successful")

            // EXTRACT — SafeExtractor boundary-checked (spec §13).
            ctx.onStage(SetupState.EXTRACTING, "Extracting filesystem")
            val extractorRoot = ctx.paths.base
            val stats = ctx.safeExtractor.extract(
                archive,
                ctx.paths.rootfs,
                onProgress = { entries ->
                    ctx.onExtractProgress(entries)
                },
                beforeEntry = { ctx.checkCancelled() }
            )
            ctx.onLog("extracted files=${stats.files} dirs=${stats.dirs} links=${stats.links}")
            // The verified archive stays cached so a failed configure can retry
            // without re-downloading; it is cleaned by Clear Temporary Setup Files.

            // CONFIGURE — distro-parameterized userspace configuration.
            ctx.onStage(SetupState.CONFIGURING, "Configuring environment")
            val configurator = EnvironmentConfigurator(ctx.paths, distro)
            configurator.configure()
            if (distro.packageManager ==
                com.crossberry.noxs.environments.model.PackageManagerKind.APT) {
                ctx.onLog("initializing signed package metadata (apt)")
                val apt = NoxsAptBootstrapper(ctx.paths, ctx.launcher, ctx.isCancelled)
                    .initialize(force = true, onLog = ctx::onLog)
                if (!apt.success) {
                    // Non-fatal by design: the shell works without APT state; the
                    // background repair keeps fixing the package layer (spec §64).
                    ctx.onLog("package layer setup deferred to background repair")
                }
                configurator.configureAptSources(useHttps = true)
            }
            ctx.checkCancelled()

            // CREATING USER — masked password, stdin only, never stored (spec §30).
            ctx.onStage(SetupState.CREATING_USER, "Creating user")
            provisionUser(ctx)

            // CONFIGURING SHELL — profile/motd/prompt already written; final touch.
            ctx.onStage(SetupState.CONFIGURING_SHELL, "Configuring shell")
            ctx.onLog("shell configured: bash for user noxs (noxs@${distro.promptHost})")

            // VERIFYING ENVIRONMENT — real checks, no fake success (spec §67).
            ctx.onStage(SetupState.VERIFYING_ENVIRONMENT, "Verifying installation")
            verifyEnvironment(ctx)

            ctx.onStage(SetupState.READY, "Environment ready")
            true
        }

    /** Creates the noxs user; password via masked dialog → chpasswd stdin (never stored). */
    private fun provisionUser(ctx: InstallContext) {
        ctx.checkCancelled()
        val etc = File(ctx.paths.rootfs, "etc")
        val passwdFile = File(etc, "passwd")
        val users = com.crossberry.noxs.shared.PasswdDb.parsePasswd(passwdFile.readText())
        if (users.none { it.name == com.crossberry.noxs.shared.NoxsConstants.DEFAULT_USER }) {
            throw IOException("rootfs does not contain a passwd database to register the noxs user")
        }
        val password = ctx.passwordProvider()
            ?: throw IllegalStateException("A Noxs password is required to create your Linux user")
        try {
            if (password.size < 4 || password.any { it == ':' || Character.isISOControl(it) }) {
                throw IllegalStateException("Invalid Noxs password input")
            }
            val argv = ctx.launcher.oneShotArgv(listOf("/usr/sbin/chpasswd"), asRoot = true)
            val pb = ProcessBuilder(argv)
            ctx.launcher.applyEnvTo(pb, mapOf("NOXS_ROOT_LOGIN" to "1"))
            pb.redirectErrorStream(true)
            val proc = pb.start()
            proc.outputStream.use { output ->
                output.write("noxs:".toByteArray(Charsets.UTF_8))
                val encoded = Charsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(password))
                val bytes = ByteArray(encoded.remaining())
                encoded.get(bytes)
                try {
                    output.write(bytes)
                    output.write('\n'.code)
                    output.flush()
                } finally {
                    bytes.fill(0)
                }
            }
            proc.inputStream.bufferedReader().use { reader -> while (reader.readLine() != null) Unit }
            val code = proc.waitFor()
            if (code != 0) {
                NoxsLog.w("EnvSetup", "chpasswd exited with code $code")
                throw IllegalStateException("Could not set the Noxs password")
            }
            ctx.onLog("user 'noxs' created (userspace only — not Android root, spec §49)")
        } finally {
            password.fill('\u0000')
        }
    }

    /** Real verification: rootfs layout + shell + package manager binary (spec §67). */
    private fun verifyEnvironment(ctx: InstallContext) {
        val rootfs = ctx.paths.rootfs
        val problems = mutableListOf<String>()
        if (!File(rootfs, "bin/sh").isFile && !File(rootfs, "usr/bin/sh").isFile) {
            problems += "shell binary missing"
        }
        when (distro.packageManager) {
            com.crossberry.noxs.environments.model.PackageManagerKind.APT -> {
                if (!File(rootfs, "usr/bin/apt-get").isFile) problems += "apt-get missing"
            }
            com.crossberry.noxs.environments.model.PackageManagerKind.PACMAN -> {
                if (!File(rootfs, "usr/bin/pacman").isFile) problems += "pacman missing"
            }
            else -> Unit
        }
        if (!File(ctx.paths.base, "environment.json").isFile) problems += "environment tag missing"
        if (problems.isNotEmpty()) throw IOException("environment verification failed: ${problems.joinToString("; ")}")
        ctx.onLog("verification passed: shell, package manager and layout are intact")
    }

    // ------------------------------------------------------------- profile

    fun terminalProfile(env: Environment): TerminalProfile = TerminalProfile(
        environmentId = env.id,
        displayName = distro.id.replaceFirstChar { it.uppercase() },
        shell = "/bin/bash",
        user = "noxs",
        home = "/home/noxs",
        workingDirectory = "/home/noxs",
        environmentVariables = mapOf(
            "NOXS" to "1",
            "NOXS_ENV" to distro.id,
            "TERM" to "xterm-256color"
        ),
        promptHost = distro.promptHost
    )
}
