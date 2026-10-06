/*
 * Noxs — original implementation.
 * NoxsWebWindowBridge: guest→Android transport for `nx ow` (spec §15).
 *
 * The guest CLI writes a one-line-per-field request file under
 * /var/run/noxs/host/web/requests/ (physically inside the app sandbox, the
 * same proven pattern as the storage bridge). This bridge watches the
 * directory, re-validates every request with NoxsUrlGuard, answers with a
 * response file, and reports opened URLs to the Noxs Web Window Manager.
 *
 * Security:
 *  - request ids must match [A-Za-z0-9._-]{1,120}; symlinks are deleted
 *  - URLs re-validated on the Android side (never trust the request file)
 *  - responses carry stable error codes only — no internal paths or details
 */
package com.crossberry.noxs.runtime

import com.crossberry.noxs.shared.NoxsLog
import java.io.File

class NoxsWebWindowBridge(private val paths: NoxsPaths) {

    /** (windowId, url) — the service turns this into a real web window. */
    var onOpenWebWindow: ((String, String) -> Unit)? = null

    /** Recreate the IPC folders if a shell replaced one with a symlink. */
    fun ensureControlDirectories(): Boolean {
        var recreated = false
        listOf(paths.webControl, paths.webRequests, paths.webResponses).forEach { directory ->
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
        val pending = paths.webRequests.listFiles().orEmpty()
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
        if (operation != "open") {
            respond(requestId, ok = false, message = "Unsupported web request")
            return 0
        }
        val url = lines.getOrNull(1)?.trim().orEmpty()
        when (val decision = NoxsUrlGuard.check(url)) {
            is NoxsUrlGuard.Decision.Rejected -> {
                // Input is never echoed into logs — reason codes stay generic.
                NoxsLog.w("WebWindowBridge", "web request rejected (${decision.reason})")
                respond(requestId, ok = false, message = "Unsupported URL: only http:// and https:// can be opened")
                return 0
            }
            is NoxsUrlGuard.Decision.Allowed -> {
                val windowId = newWindowId()
                respond(requestId, ok = true, message = windowId)
                NoxsLog.i("WebWindowBridge", "web window requested: $windowId")
                onOpenWebWindow?.invoke(windowId, decision.url)
                return 1
            }
        }
    }

    private fun respond(requestId: String, ok: Boolean, message: String) {
        val response = File(paths.webResponses, requestId)
        runCatching {
            response.writeText(if (ok) "OK\n$message\n" else "ERR\n$message\n", Charsets.UTF_8)
        }
    }

    private fun newWindowId(): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        val suffix = buildString {
            repeat(8) { append(alphabet[(Math.random() * alphabet.length).toInt()]) }
        }
        return "web-$suffix"
    }

    /** Keep the IPC folders tidy: transfers older than 6 hours are stale. */
    fun cleanupStale() {
        val cutoff = System.currentTimeMillis() - STALE_AFTER_MS
        listOf(paths.webRequests, paths.webResponses).forEach { directory ->
            directory.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        }
    }

    private fun isSymlink(file: File): Boolean =
        runCatching { java.nio.file.Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)

    companion object {
        private const val STALE_AFTER_MS = 6L * 60L * 60L * 1000L
    }
}
