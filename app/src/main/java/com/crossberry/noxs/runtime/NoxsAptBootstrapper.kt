/*
 * Noxs — signed Debian APT bootstrap.
 * Installs/repairs CA certificates over signed Debian HTTP metadata first,
 * then switches the sole source file to HTTPS and verifies a real TLS update.
 */
package com.crossberry.noxs.runtime

import com.crossberry.noxs.shared.NoxsLog
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class NoxsAptBootstrapper(
    private val paths: NoxsPaths,
    private val launcher: ProotLauncher
) {
    data class Result(val success: Boolean, val detail: String = "")

    /**
     * Runs the bootstrap once per rootfs. [force] is used by fresh installs;
     * existing installs are repaired automatically when the marker is absent.
     */
    fun initialize(force: Boolean = false, onLog: (String) -> Unit = {}): Result =
        synchronized(PROCESS_LOCK) {
            if (!paths.rootfs.isDirectory) return@synchronized Result(false, "Debian rootfs is missing")
            if (!force && paths.aptReadyMarker.isFile) {
                return@synchronized Result(true, "APT, dpkg and HTTPS were previously verified")
            }

            try {
                paths.tmp.mkdirs()
                paths.logs.mkdirs()
                RootfsConfigurator.repairDpkgPermissions(paths)
                // Keep the temporary signed HTTP definition in place before
                // running dpkg recovery, not merely before the first apt-get.
                RootfsConfigurator.configureAptSources(paths, useHttps = false)

                // Exercise the same link operation dpkg uses for its status
                // backup in the dpkg database directory before touching packages.
                val linkProbeScript = """
                    set -eu
                    dir=${'$'}(mktemp -d /var/lib/dpkg/.noxs-link-probe.XXXXXX)
                    trap 'rm -rf "${'$'}dir"' EXIT
                    printf 'ok' > "${'$'}dir/source"
                    ln "${'$'}dir/source" "${'$'}dir/copy"
                    test "${'$'}(cat "${'$'}dir/copy")" = ok
                """.trimIndent()
                val linkProbe = run(listOf("/bin/bash", "-c", linkProbeScript), 30)
                if (!requireSuccessful("dpkg filesystem compatibility check", linkProbe, onLog)) {
                    return@synchronized Result(false, summarize("dpkg filesystem compatibility check", linkProbe))
                }
                NoxsLog.i("AptBootstrap", "PRoot dpkg filesystem compatibility check passed")

                onLog("Checking for interrupted dpkg configuration")
                val auditBefore = run(listOf("/usr/bin/dpkg", "--audit"), 60)
                if (auditBefore.output.isNotBlank()) onLog("dpkg audit found incomplete package state")
                val initialDpkg = run(listOf("/usr/bin/dpkg", "--configure", "-a"), 180)
                reportOutput("dpkg preflight", initialDpkg, onLog)
                if (initialDpkg.timedOut) return@synchronized failure("dpkg preflight timed out", initialDpkg, onLog)
                val interruptedConfiguration = initialDpkg.exitCode != 0 || auditBefore.output.isNotBlank()
                if (interruptedConfiguration) {
                    onLog("dpkg has pending configuration; will repair after signed package metadata is available")
                }

                // APT's normal Release-file signature checks remain enabled.
                onLog("Refreshing signed Debian package metadata over HTTP")
                val httpUpdate = run(aptCommand("update"), 240)
                if (!requireSuccessful("signed HTTP apt update", httpUpdate, onLog)) {
                    return@synchronized Result(false, summarize("signed HTTP apt update", httpUpdate))
                }
                if (interruptedConfiguration) {
                    onLog("Repairing interrupted package dependencies from authenticated Debian repositories")
                    val dependencyRepair = run(
                        aptCommand("--fix-broken", "install", "--yes", "--no-install-recommends"),
                        300
                    )
                    if (!requireSuccessful("apt dependency repair", dependencyRepair, onLog)) {
                        return@synchronized Result(false, summarize("apt dependency repair", dependencyRepair))
                    }
                    RootfsConfigurator.repairDpkgPermissions(paths)
                    val configured = run(listOf("/usr/bin/dpkg", "--configure", "-a"), 240)
                    if (!requireSuccessful("dpkg recovery", configured, onLog)) {
                        return@synchronized Result(false, summarize("dpkg recovery", configured))
                    }
                }

                onLog("Installing or repairing ca-certificates and the Debian archive keyring")
                val certInstall = run(
                    aptCommand("install", "--yes", "--no-install-recommends", "--reinstall", "ca-certificates", "debian-archive-keyring"),
                    300
                )
                if (!requireSuccessful("certificate package installation", certInstall, onLog)) {
                    return@synchronized Result(false, summarize("certificate package installation", certInstall))
                }

                val updateCertificates = run(listOf("/usr/sbin/update-ca-certificates", "--fresh"), 120)
                if (!requireSuccessful("update-ca-certificates", updateCertificates, onLog)) {
                    return@synchronized Result(false, summarize("update-ca-certificates", updateCertificates))
                }
                val certificateCheck = run(
                    listOf("/bin/bash", "-c", "test -s /etc/ssl/certs/ca-certificates.crt && grep -q 'BEGIN CERTIFICATE' /etc/ssl/certs/ca-certificates.crt"),
                    30
                )
                if (certificateCheck.exitCode != 0 || certificateCheck.timedOut) {
                    return@synchronized failure("verified CA bundle is missing or empty", certificateCheck, onLog)
                }
                onLog("Verified the generated CA certificate bundle")

                // HTTPS is enabled only after the CA bundle exists.
                RootfsConfigurator.configureAptSources(paths, useHttps = true)
                onLog("Refreshing signed Debian package metadata over validated HTTPS")
                val httpsUpdate = run(aptCommand("update"), 240)
                if (!requireSuccessful("HTTPS apt update", httpsUpdate, onLog)) {
                    return@synchronized Result(false, summarize("HTTPS apt update", httpsUpdate))
                }

                onLog("Finishing interrupted package configuration")
                RootfsConfigurator.repairDpkgPermissions(paths)
                var finalDpkg = run(listOf("/usr/bin/dpkg", "--configure", "-a"), 240)
                if (finalDpkg.exitCode != 0 || finalDpkg.timedOut) {
                    onLog("Retrying dpkg configuration after authenticated dependency repair")
                    val dependencyRepair = run(
                        aptCommand("--fix-broken", "install", "--yes", "--no-install-recommends"),
                        300
                    )
                    if (!requireSuccessful("final apt dependency repair", dependencyRepair, onLog)) {
                        return@synchronized Result(false, summarize("final apt dependency repair", dependencyRepair))
                    }
                    RootfsConfigurator.repairDpkgPermissions(paths)
                    finalDpkg = run(listOf("/usr/bin/dpkg", "--configure", "-a"), 240)
                }
                if (!requireSuccessful("dpkg --configure -a", finalDpkg, onLog)) {
                    return@synchronized Result(false, summarize("dpkg --configure -a", finalDpkg))
                }
                val audit = run(listOf("/usr/bin/dpkg", "--audit"), 60)
                if (audit.timedOut || audit.exitCode != 0 || audit.output.isNotBlank()) {
                    return@synchronized failure("dpkg audit found incomplete package state", audit, onLog)
                }

                val installedCerts = run(
                    listOf("/bin/bash", "-c", "/usr/bin/dpkg -s ca-certificates | /bin/grep -q '^Status: install ok installed$'"),
                    30
                )
                if (installedCerts.exitCode != 0 || installedCerts.timedOut) {
                    return@synchronized failure("ca-certificates is not in the installed state", installedCerts, onLog)
                }
                val metadata = run(listOf("/usr/bin/apt-cache", "policy", "ca-certificates"), 60)
                if (metadata.exitCode != 0 || metadata.timedOut ||
                    !metadata.output.contains("Candidate:") || metadata.output.contains("Candidate: (none)")) {
                    return@synchronized failure("APT package metadata verification failed", metadata, onLog)
                }
                reportOutput("APT metadata", metadata, onLog)

                val markerTemp = File(paths.base, "${paths.aptReadyMarker.name}.tmp")
                markerTemp.writeText("verified=${System.currentTimeMillis()}\nsuite=bookworm\ntransport=https\n")
                if (paths.aptReadyMarker.exists() && !paths.aptReadyMarker.delete()) {
                    markerTemp.delete()
                    return@synchronized Result(false, "Cannot replace APT readiness marker")
                }
                if (!markerTemp.renameTo(paths.aptReadyMarker)) {
                    markerTemp.delete()
                    return@synchronized Result(false, "Cannot save APT readiness marker")
                }
                onLog("APT, dpkg, CA certificates, signed metadata and HTTPS verified")
                NoxsLog.i("AptBootstrap", "APT initialized with signed Bookworm HTTPS sources")
                Result(true)
            } catch (e: Exception) {
                NoxsLog.e("AptBootstrap", "APT bootstrap failed", e)
                Result(false, e.message ?: e.javaClass.simpleName)
            }
        }

    private fun aptCommand(vararg args: String): List<String> =
        listOf("/usr/bin/apt-get") + APT_OPTIONS + args

    private fun run(command: List<String>, timeoutSeconds: Long): CommandResult {
        val process = ProcessBuilder(launcher.oneShotArgv(command, asRoot = true)).apply {
            redirectErrorStream(true)
            launcher.applyEnvTo(this, mapOf(
                "NOXS_ROOT_LOGIN" to "1",
                "DEBIAN_FRONTEND" to "noninteractive",
                "DEBIAN_PRIORITY" to "critical"
            ))
        }.start()
        val output = StringBuilder()
        val outputLock = Any()
        val reader = Thread({
            try {
                BufferedReader(InputStreamReader(process.inputStream)).use { stream ->
                    while (true) {
                        val line = stream.readLine() ?: break
                        synchronized(outputLock) {
                            output.append(line).append('\n')
                            if (output.length > MAX_CAPTURE_CHARS) {
                                output.delete(0, output.length - MAX_CAPTURE_CHARS)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // The process result below carries the useful failure details.
            }
        }, "noxs-apt-output").apply { isDaemon = true }
        reader.start()

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        }
        reader.join(5_000)
        val captured = synchronized(outputLock) { output.toString() }
        return CommandResult(
            exitCode = if (finished) process.exitValue() else 124,
            output = captured,
            timedOut = !finished
        )
    }

    private fun requireSuccessful(step: String, result: CommandResult, onLog: (String) -> Unit): Boolean {
        reportOutput(step, result, onLog)
        val insecureOrTlsFailure = INSECURE_OR_TLS_FAILURE.containsMatchIn(result.output)
        val partialUpdateFailure = step.contains("apt update") && PARTIAL_UPDATE_FAILURE.containsMatchIn(result.output)
        val success = !result.timedOut && result.exitCode == 0 && !insecureOrTlsFailure && !partialUpdateFailure
        if (!success) NoxsLog.e("AptBootstrap", summarize(step, result))
        return success
    }

    private fun reportOutput(step: String, result: CommandResult, onLog: (String) -> Unit) {
        result.output.lineSequence()
            .filter { it.startsWith("Err:") || it.startsWith("W:") || it.startsWith("E:") ||
                it.contains("Candidate:") || it.contains("Setting up ") || it.contains("Unpacking ") }
            .toList()
            .takeLast(10)
            .forEach { onLog("$step: $it") }
    }

    private fun failure(step: String, result: CommandResult, onLog: (String) -> Unit): Result {
        reportOutput(step, result, onLog)
        val detail = summarize(step, result)
        NoxsLog.e("AptBootstrap", detail)
        return Result(false, detail)
    }

    private fun summarize(step: String, result: CommandResult): String {
        val detail = result.output.lineSequence()
            .filter { it.isNotBlank() }
            .toList()
            .takeLast(16)
            .joinToString("\n")
        return buildString {
            append(step)
            append(" failed (exit ")
            append(result.exitCode)
            append(if (result.timedOut) ", timed out)" else ")")
            if (detail.isNotBlank()) append(":\n").append(detail)
        }
    }

    private data class CommandResult(val exitCode: Int, val output: String, val timedOut: Boolean)

    companion object {
        private val PROCESS_LOCK = Any()
        private const val MAX_CAPTURE_CHARS = 48_000
        private val APT_OPTIONS = listOf(
            "-o", "Acquire::Retries=1",
            "-o", "Acquire::http::Timeout=20",
            "-o", "Acquire::https::Timeout=20"
        )
        private val INSECURE_OR_TLS_FAILURE = Regex(
            "(?i)(certificate verification failed|certificate is not trusted|tls handshake|\\bNO_PUBKEY\\b|repository .* is not signed|unauthenticated packages)"
        )
        private val PARTIAL_UPDATE_FAILURE = Regex(
            "(?im)(^Err:|failed to fetch|some index files failed|could not resolve|connection timed out)"
        )
    }
}
