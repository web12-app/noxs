/*
 * Noxs — original implementation.
 * NoxsEnvBridge: guest→Android transport for `nx env` (Multi-Env Manager CLI).
 *
 * The guest CLI writes a one-field-per-line request file under
 * /var/run/noxs/host/env/requests/ (physically inside the app sandbox —
 * the same proven pattern as the web bridge). This bridge watches the
 * directory, re-validates every request host-side, answers with a response
 * file, and publishes two snapshots the CLI can read:
 *
 *   registry.txt   installed environments + live setup task state
 *   providers.txt  installable providers + variants
 *
 * Security:
 *  - request ids must match [A-Za-z0-9._-]{1,120}; symlinks are deleted
 *  - every request is re-validated on the Android side (never trust the file)
 *  - passwords sent by `nx env install` are read into a CharArray, the
 *    request file is deleted immediately and the array is zeroed on every
 *    rejection path — the pipeline zeroes it after use (never a String)
 *  - responses carry short stable messages only — no internal paths
 */
package com.crossberry.noxs.runtime

import com.crossberry.noxs.environments.EnvironmentProvider
import com.crossberry.noxs.environments.model.Environment
import com.crossberry.noxs.environments.model.SetupTask
import com.crossberry.noxs.shared.NoxsLog
import java.io.File

class NoxsEnvBridge(private val paths: NoxsPaths) {

    /**
     * Domain hooks, implemented by NoxsService. Each returns (ok, message);
     * the bridge turns that into the response file for [requestId].
     */
    var onInstall: ((requestId: String, providerId: String, variantId: String, password: CharArray?) -> Pair<Boolean, String>)? = null
    var onRemove: ((requestId: String, environmentId: String) -> Pair<Boolean, String>)? = null
    var onUse: ((requestId: String, environmentId: String) -> Pair<Boolean, String>)? = null

    /** (ok, message) when no hook is wired — honest refusal instead of silence. */
    private val notReady: Pair<Boolean, String> =
        false to "Noxs environment bridge is not ready"

    /** Recreate the IPC folders if a shell replaced one with a symlink. */
    fun ensureControlDirectories(): Boolean {
        var recreated = false
        listOf(paths.envControl, paths.envRequests, paths.envResponses).forEach { directory ->
            if (isSymlink(directory)) {
                directory.delete()
                recreated = true
            }
            if (!directory.isDirectory) {
                directory.mkdirs()
                recreated = true
            }
        }
        return recreated
    }

    /** Drain all pending requests; safe to call repeatedly. Returns count handled. */
    fun processPendingRequests(): Int {
        ensureControlDirectories()
        val pending = paths.envRequests.listFiles().orEmpty()
        pending.filter(::isSymlink).forEach { it.delete() }
        var handled = 0
        pending.filter { it.isFile && !isSymlink(it) }
            .sortedBy { it.lastModified() }
            .forEach { request -> handled += processOne(request) }
        cleanupStale()
        return handled
    }

    private fun processOne(request: File): Int {
        val requestId = request.name
        if (!requestId.matches(Regex("[A-Za-z0-9._-]{1,120}"))) {
            request.delete()
            return 0
        }
        val lines = runCatching { request.readLines(Charsets.UTF_8) }.getOrDefault(emptyList())
        request.delete()
        val operation = lines.getOrNull(0)?.trim()?.lowercase().orEmpty()
        return when (operation) {
            "install" -> {
                val providerId = lines.getOrNull(1)?.trim().orEmpty()
                val variantId = lines.getOrNull(2)?.trim().orEmpty()
                val password = lines.getOrNull(3)?.takeIf { it.isNotEmpty() }?.toCharArray()
                val outcome = try {
                    onInstall?.invoke(requestId, providerId, variantId, password) ?: notReady
                } catch (t: Throwable) {
                    password?.fill('\u0000')
                    NoxsLog.w("EnvBridge", "install failed: ${t.javaClass.simpleName}")
                    false to "Install could not be started"
                }
                // The request file is gone; scrub any password remnant eagerly
                // so failed validations never leave credentials in memory.
                if (!outcome.first) password?.fill('\u0000')
                respond(requestId, outcome)
                1
            }
            "remove" -> {
                val environmentId = lines.getOrNull(1)?.trim().orEmpty()
                val outcome = runCatching {
                    onRemove?.invoke(requestId, environmentId) ?: notReady
                }.getOrElse {
                    NoxsLog.w("EnvBridge", "remove failed: ${it.javaClass.simpleName}")
                    false to "Remove could not be started"
                }
                respond(requestId, outcome)
                1
            }
            "use" -> {
                val environmentId = lines.getOrNull(1)?.trim().orEmpty()
                val outcome = runCatching {
                    onUse?.invoke(requestId, environmentId) ?: notReady
                }.getOrElse {
                    NoxsLog.w("EnvBridge", "use failed: ${it.javaClass.simpleName}")
                    false to "Switch could not be started"
                }
                respond(requestId, outcome)
                1
            }
            else -> {
                respond(requestId, false to "Unsupported environment request")
                0
            }
        }
    }

    private fun respond(requestId: String, outcome: Pair<Boolean, String>) {
        val response = File(paths.envResponses, requestId)
        runCatching {
            response.writeText(
                (if (outcome.first) "OK\n" else "ERR\n") + outcome.second + "\n",
                Charsets.UTF_8
            )
        }
    }

    // -------------------------------------------------------------- snapshots

    /**
     * Publishes the state the guest CLI reads. Atomic per file (tmp + rename)
     * so the CLI never parses a half-written line.
     */
    fun writeSnapshot(
        environments: List<Environment>,
        activeId: String?,
        tasks: List<SetupTask>,
        providers: List<EnvironmentProvider>
    ) {
        ensureControlDirectories()
        // Newest task per environment (the flow is sorted newest-first).
        val taskByEnv = HashMap<String, SetupTask>()
        tasks.forEach { task -> taskByEnv.putIfAbsent(task.environmentId, task) }

        val registry = environments.joinToString("") { env ->
            val task = taskByEnv[env.id]
            listOf(
                env.id,
                env.displayName,
                env.status.name.lowercase(),
                if (env.id == activeId) "active" else "-",
                task?.state?.name?.lowercase().orEmpty(),
                (task?.progress ?: -1).toString(),
                task?.currentOperation.orEmpty()
            ).joinToString("\t") { field -> field.replace(Regex("[\t\r\n]"), " ") }
        } + "\n"
        atomicWrite(paths.envSnapshot, registry)

        val providerLines = providers.joinToString("") { provider ->
            val variants = provider.variants()
            listOf(
                provider.id,
                provider.displayName,
                provider.description,
                variants.joinToString(",") { it.id },
                variants.firstOrNull { it.isDefault }?.id
                    ?: variants.firstOrNull()?.id.orEmpty()
            ).joinToString("\t") { field -> field.replace(Regex("[\t\r\n]"), " ") }
        } + "\n"
        atomicWrite(paths.envProvidersSnapshot, providerLines)
    }

    private fun atomicWrite(target: File, content: String) {
        runCatching {
            val tmp = File(target.parentFile, ".${target.name}.tmp")
            tmp.writeText(content, Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                target.writeText(content, Charsets.UTF_8)
                tmp.delete()
            }
        }
    }

    /** Keep the IPC folders tidy: transfers older than 6 hours are stale. */
    fun cleanupStale() {
        val cutoff = System.currentTimeMillis() - STALE_AFTER_MS
        listOf(paths.envRequests, paths.envResponses).forEach { directory ->
            directory.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        }
    }

    private fun isSymlink(file: File): Boolean =
        runCatching { java.nio.file.Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)

    companion object {
        private const val STALE_AFTER_MS = 6L * 60L * 60L * 1000L
    }
}
