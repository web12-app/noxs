/*
 * Noxs — original implementation.
 * NoxsApiBridge: the single authorization point between NX packages and the
 * Noxs runtime (Noxs API spec §3, §11-§14, §31-§33).
 *
 *     Package -> @noxs/nx-api -> Noxs API Bridge -> Permission Manager
 *             -> Noxs Runtime -> Kotlin / Rust / C++ -> Android / Linux
 *
 * Enforced invariants:
 *  - packageId is supplied by the WebView host AFTER origin verification —
 *    a request body claiming a different identity is rejected outright
 *  - every request carries packageId / requestId / apiVersion / operation
 *    and validated arguments (spec §11)
 *  - privileged modules map to Noxs Permission Center ids; an absent
 *    decision means NOT granted (packages are never trusted for being
 *    installed; WebView JavaScript is never trusted by default)
 *  - responses are structured JSON; internal paths, stack traces, GitHub
 *    internals and secrets never appear in messages (spec §11, §23)
 *  - each package sees only its own isolated storage context (spec §12)
 *
 * Pure JVM: MiniJson parsing, no Android imports — fully unit-testable.
 */
package com.crossberry.noxs.runtime.api

import com.crossberry.noxs.runtime.NoxsPermissionCenter
import com.crossberry.noxs.shared.MiniJson
import java.io.File

object NoxsApi {
    const val API_VERSION = "1"

    // Stable error codes (spec §32).
    const val ERR_INVALID_ARGUMENT = "INVALID_ARGUMENT"
    const val ERR_INVALID_URL = "INVALID_URL"
    const val ERR_PERMISSION_DENIED = "PERMISSION_DENIED"
    const val ERR_PACKAGE_NOT_FOUND = "PACKAGE_NOT_FOUND"
    const val ERR_PACKAGE_VERSION_INVALID = "PACKAGE_VERSION_INVALID"
    const val ERR_CHECKSUM_FAILED = "CHECKSUM_FAILED"
    const val ERR_DOWNLOAD_FAILED = "DOWNLOAD_FAILED"
    const val ERR_WINDOW_NOT_FOUND = "WINDOW_NOT_FOUND"
    const val ERR_WEBVIEW_ERROR = "WEBVIEW_ERROR"
    const val ERR_NETWORK_ERROR = "NETWORK_ERROR"
    const val ERR_TASK_CANCELLED = "TASK_CANCELLED"
    const val ERR_UNSUPPORTED_OPERATION = "UNSUPPORTED_OPERATION"
    const val ERR_MALFORMED_REQUEST = "MALFORMED_REQUEST"
    const val ERR_IDENTITY_MISMATCH = "IDENTITY_MISMATCH"
}

/** Hosts wire real implementations here; unwired operations answer UNSUPPORTED_OPERATION. */
fun interface ApiPort {
    fun invoke(module: String, operation: String, packageId: String, arguments: Map<String, Any?>): Any?
}

class NoxsApiBridge(
    private val permissionCenter: NoxsPermissionCenter,
    /** Ports keyed by module name (window, web, terminal, logs, package, system, storage). */
    private val ports: Map<String, ApiPort> = emptyMap(),
    /** Verified package ids; empty set disables the known-package check for tests/tools. */
    private val knownPackages: Set<String> = emptySet()
) {

    /**
     * Handle one API request. [verifiedPackageId] is the identity established
     * by the WebView host (origin-verified), never the JS-supplied value.
     */
    fun handle(verifiedPackageId: String, requestJson: String): String {
        val response = runCatching { evaluate(verifiedPackageId, requestJson) }
            .getOrElse { failure -> error(NoxsApi.ERR_MALFORMED_REQUEST, "Request failed validation.") }
        return writeJson(response)
    }

    /** Structured error for host-side rejections (origin failures etc.). */
    fun writeOnlyError(code: String, message: String): String = writeJson(error(code, message))

    private fun evaluate(verifiedPackageId: String, requestJson: String): Map<String, Any?> {
        val body = MiniJson.parse(requestJson)
        if (body !is Map<*, *>) {
            return error(NoxsApi.ERR_MALFORMED_REQUEST, "Request must be a JSON object.")
        }
        val request = Request.from(body) ?: return error(
            NoxsApi.ERR_MALFORMED_REQUEST, "Request is missing required fields."
        )

        // Identity: the host-verified id wins. The body value must agree with it.
        if (request.packageId != verifiedPackageId) {
            return error(NoxsApi.ERR_IDENTITY_MISMATCH, "Package identity could not be verified.")
        }
        if (knownPackages.isNotEmpty() && verifiedPackageId !in knownPackages) {
            return error(NoxsApi.ERR_PERMISSION_DENIED, "Permission required.")
        }
        if (request.apiVersion != NoxsApi.API_VERSION) {
            return error(NoxsApi.ERR_MALFORMED_REQUEST, "API version not supported.")
        }
        if (!request.requestId.matches(REQUEST_ID_REGEX)) {
            return error(NoxsApi.ERR_INVALID_ARGUMENT, "Invalid request identifier.")
        }

        // Permission gate (spec §6): every privileged call is checked here.
        val requiredPermission = requiredPermission(request.module, request.operation, request.arguments)
            ?: return error(NoxsApi.ERR_UNSUPPORTED_OPERATION, "Operation is not available.")
        if (!permissionCenter.isAllowed(requiredPermission)) {
            return error(NoxsApi.ERR_PERMISSION_DENIED, "Permission required.")
        }

        val port = ports[request.module] ?: return error(
            NoxsApi.ERR_UNSUPPORTED_OPERATION, "Operation is not available."
        )
        return runCatching {
            val data = port.invoke(request.module, request.operation, verifiedPackageId, request.arguments)
            ok(request.requestId, data)
        }.getOrElse {
            error(NoxsApi.ERR_INVALID_ARGUMENT, "Operation failed validation.")
        }
    }

    // ---------------------------------------------------- permission mapping

    /**
     * Noxs permission ids for module+operation (spec §7). Default package
     * access is read/subscribe only: terminal.write/execute are NEVER
     * granted automatically (spec §13).
     */
    fun requiredPermission(module: String, operation: String, arguments: Map<String, Any?>): String? = when (module) {
        "window" -> when (operation) {
            "open" -> "window.create"
            "close", "minimize", "restore", "maximize", "fullscreen", "focus", "getState" -> "window.control"
            else -> null
        }
        "web" -> when (operation) {
            "open" -> "web.open"
            else -> "web.control"
        }
        "terminal" -> when (operation) {
            "read" -> "terminal.read"
            "subscribe" -> "terminal.subscribe"
            "write" -> "terminal.write"
            "execute" -> "terminal.execute"
            else -> null
        }
        "logs" -> when (operation) {
            "read" -> "logs.read"
            else -> "logs.subscribe"
        }
        "events" -> when (operation) {
            "subscribe", "unsubscribe" -> requiredPermissionForEvent(arguments["event"] as? String)
            else -> null
        }
        "package" -> when (operation) {
            "info" -> "package.read"
            "install" -> "package.install"
            else -> null
        }
        "permissions" -> when (operation) {
            "query", "request" -> "system.info" // self-query only; grants happen in the Permission Center
            else -> null
        }
        "system" -> when (operation) {
            "info" -> "system.info"
            else -> null
        }
        "storage" -> when (operation) {
            "read", "write" -> "storage.package" // per-package isolated storage (spec §12)
            else -> null
        }
        else -> null
    }

    private fun requiredPermissionForEvent(event: String?): String? = when {
        event == null -> null
        event.startsWith("window.") -> "window.control"
        event.startsWith("terminal.") -> "terminal.subscribe"
        event.startsWith("logs.") -> "logs.subscribe"
        event.startsWith("web.") -> "web.control"
        event.startsWith("task.") -> "background.tasks"
        event.startsWith("permission.") -> "system.info"
        else -> null
    }

    // ------------------------------------------------------ package isolation

    /** Isolated per-package storage root (spec §12). Package A never sees B. */
    fun packageDataDir(root: File, packageId: String): File {
        require(packageId.matches(PACKAGE_ID_REGEX)) { "Invalid package identifier." }
        val dir = File(root, packageId)
        require(dir.canonicalPath.startsWith(root.canonicalPath)) { "Storage path escapes package root." }
        return dir
    }

    // ------------------------------------------------------------- envelopes

    data class Request(
        val packageId: String,
        val requestId: String,
        val apiVersion: String,
        val module: String,
        val operation: String,
        val arguments: Map<String, Any?>
    ) {
        companion object {
            fun from(body: Map<*, *>): Request? {
                val packageId = body["packageId"] as? String ?: return null
                val requestId = body["requestId"] as? String ?: return null
                // The SDK envelope uses "v"; accept the long form too.
                val apiVersion = body["v"] as? String ?: body["apiVersion"] as? String ?: return null
                val module = body["module"] as? String ?: return null
                val operation = body["operation"] as? String ?: return null
                val arguments = (body["arguments"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()
                if (!packageId.matches(PACKAGE_ID_REGEX)) return null
                if (module.length > 32 || operation.length > 32) return null
                return Request(packageId, requestId, apiVersion, module, operation, arguments)
            }
        }
    }

    private fun ok(requestId: String, data: Any?): Map<String, Any?> = mapOf(
        "ok" to true,
        "data" to data
    )

    private fun error(code: String, message: String): Map<String, Any?> = mapOf(
        "ok" to false,
        "error" to mapOf("code" to code, "message" to message)
    )

    /** Deterministic minimal JSON writer for the envelope (strings/objects only). */
    private fun writeJson(map: Map<String, Any?>): String {
        val sb = StringBuilder()
        writeValue(map, sb)
        return sb.toString()
    }

    private fun writeValue(value: Any?, sb: StringBuilder) {
        when (value) {
            null -> sb.append("null")
            is String -> writeString(value, sb)
            is Boolean -> sb.append(if (value) "true" else "false")
            is Number -> sb.append(value.toString())
            is Map<*, *> -> {
                sb.append('{')
                value.entries.forEachIndexed { index, entry ->
                    if (index > 0) sb.append(',')
                    writeString(entry.key.toString(), sb)
                    sb.append(':')
                    writeValue(entry.value, sb)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) sb.append(',')
                    writeValue(item, sb)
                }
                sb.append(']')
            }
            else -> writeString(value.toString(), sb)
        }
    }

    private fun writeString(value: String, sb: StringBuilder) {
        sb.append('"')
        value.forEach { c ->
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    companion object {
        private val REQUEST_ID_REGEX = Regex("[A-Za-z0-9._-]{1,64}")
        private val PACKAGE_ID_REGEX = Regex("[a-z0-9][a-z0-9._-]{0,63}")
    }
}
