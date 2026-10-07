/*
 * Noxs — original implementation.
 * NoxsPluginBridge: guest→Android transport for the Plugin Store.
 *
 * `nx plug` (inside the Linux environment) writes a one-field-per-line
 * request file under /var/run/noxs/host/plugin/requests/ — the same proven
 * pattern as the web and env bridges. This bridge drains the directory,
 * re-validates every request host-side (never trust the file), answers
 * with a response file and publishes two snapshots the CLI reads directly:
 *
 *   catalog.txt   the Plugin Store registry (id, name, version, category,
 *                 keywords, description) — used by list / search / info
 *   plugins.txt   installed plugins (id, name, version, state)
 *
 * Opening the store UI is the one request that has no snapshot form.
 *
 * Security:
 *  - request ids must match [A-Za-z0-9._-]{1,120}; symlinks are deleted
 *  - every request is re-validated on the Android side
 *  - responses carry short stable messages only — no internal paths
 */
package com.crossberry.noxs.runtime.plugins

import com.crossberry.noxs.runtime.NoxsPaths
import com.crossberry.noxs.shared.NoxsLog
import java.io.File

class NoxsPluginBridge(private val paths: NoxsPaths) {

    /** Domain hooks, implemented by NoxsService. */
    var onOpenStore: (() -> Unit)? = null
    var onInstall: ((requestId: String, pluginId: String) -> Pair<Boolean, String>)? = null
    var onUninstall: ((requestId: String, pluginId: String) -> Pair<Boolean, String>)? = null
    var onEnable: ((requestId: String, pluginId: String) -> Pair<Boolean, String>)? = null
    var onDisable: ((requestId: String, pluginId: String) -> Pair<Boolean, String>)? = null
    var onUpdate: ((requestId: String, pluginId: String) -> Pair<Boolean, String>)? = null

    private val notReady: Pair<Boolean, String> =
        false to "Noxs plugin service is not ready"

    /** Recreate the IPC folders if a shell replaced one with a symlink. */
    fun ensureControlDirectories(): Boolean {
        var recreated = false
        listOf(paths.pluginControl, paths.pluginRequests, paths.pluginResponses).forEach { directory ->
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
        val pending = paths.pluginRequests.listFiles().orEmpty()
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
        val pluginId = lines.getOrNull(1)?.trim().orEmpty()

        val outcome: Pair<Boolean, String> = when (operation) {
            "open" -> {
                onOpenStore?.invoke()
                true to "store opened"
            }
            "install", "uninstall", "enable", "disable", "update" -> {
                if (!PluginJson.validId(pluginId)) {
                    false to "Invalid plugin id"
                } else {
                    val hook = when (operation) {
                        "install" -> onInstall
                        "uninstall" -> onUninstall
                        "enable" -> onEnable
                        "disable" -> onDisable
                        else -> onUpdate
                    }
                    runCatching { hook?.invoke(requestId, pluginId) ?: notReady }
                        .getOrElse {
                            NoxsLog.w("PluginBridge", "$operation failed: ${it.javaClass.simpleName}")
                            false to "The request could not be completed"
                        }
                }
            }
            else -> false to "Unsupported plugin request"
        }
        respond(requestId, outcome)
        return 1
    }

    private fun respond(requestId: String, outcome: Pair<Boolean, String>) {
        val response = File(paths.pluginResponses, requestId)
        runCatching {
            response.writeText(
                (if (outcome.first) "OK\n" else "ERR\n") + outcome.second + "\n",
                Charsets.UTF_8
            )
        }
    }

    // -------------------------------------------------------------- snapshots

    /**
     * Publishes what the CLI reads directly. Atomic per file (tmp + rename)
     * so the CLI never parses a half-written line. Field order matches the
     * CLI column parsing; tabs/CR/LF inside fields are sanitized to spaces.
     */
    fun writeSnapshots(catalog: List<NoxsPluginManager.StoreCard>, installed: List<InstalledPlugin>) {
        ensureControlDirectories()
        val catalogText = buildString {
            catalog.forEach { card ->
                appendLine(
                    listOf(
                        card.entry.id,
                        card.entry.name,
                        card.entry.version,
                        card.entry.category ?: "-",
                        card.entry.keywords.joinToString(","),
                        card.entry.description,
                        when {
                            card.installed == null -> "available"
                            card.installed.enabled -> "installed"
                            else -> "disabled"
                        },
                        if (card.updateAvailable) "update" else "-"
                    ).joinToString("\t") { field -> sanitizeField(field) }
                )
            }
        }
        atomicWrite(paths.pluginCatalogSnapshot, catalogText)

        val installedText = buildString {
            installed.forEach { plugin ->
                appendLine(
                    listOf(
                        plugin.meta.id,
                        plugin.meta.name,
                        plugin.meta.version,
                        if (plugin.enabled) "enabled" else "disabled"
                    ).joinToString("\t") { field -> sanitizeField(field) }
                )
            }
        }
        atomicWrite(paths.pluginInstalledSnapshot, installedText)
    }

    /** CRLF counts as ONE break; any other tab/CR/LF becomes a single space. */
    private fun sanitizeField(field: String): String =
        field.replace("\r\n", " ").replace(Regex("[\t\r\n]"), " ")

    private fun atomicWrite(target: File, content: String) {
        runCatching {
            target.parentFile?.mkdirs()
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
        listOf(paths.pluginRequests, paths.pluginResponses).forEach { directory ->
            directory.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        }
    }

    private fun isSymlink(file: File): Boolean =
        runCatching { java.nio.file.Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)

    companion object {
        private const val STALE_AFTER_MS = 6L * 60L * 60L * 1000L
    }
}
