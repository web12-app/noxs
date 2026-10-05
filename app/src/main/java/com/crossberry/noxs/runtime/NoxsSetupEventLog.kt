/*
 * Noxs — original implementation.
 * Structured setup event stream (internal diagnostics only).
 *
 * The terminal remains the primary user-facing interface; this file-backed
 * event stream is for support and regression analysis. Events are sanitized:
 * no repository URLs, no backend addresses, no storage paths, no passwords —
 * only event names, step numbers, durations and coarse reasons.
 */
package com.crossberry.noxs.runtime

import java.io.File

object NoxsSetupEventLog {

    const val STARTED = "setup.started"
    const val RESUMED = "setup.resumed"
    const val STEP_STARTED = "setup.step.started"
    const val STEP_COMPLETED = "setup.step.completed"
    const val OUTPUT_TICK = "setup.output"
    const val COMPLETED = "setup.completed"
    const val FAILED = "setup.failed"
    const val CANCELLED = "setup.cancelled"

    /** Serializable event line — one JSON object per line. */
    data class Event(val name: String, val step: Int? = null, val elapsedMs: Long = 0L, val note: String = "") {
        fun toJsonLine(): String = buildString {
            append("{\"ts\":").append(System.currentTimeMillis())
            append(",\"event\":\"").append(sanitize(name)).append('"')
            step?.let { append(",\"step\":").append(it) }
            if (elapsedMs > 0) append(",\"elapsed_ms\":").append(elapsedMs)
            if (note.isNotBlank()) append(",\"note\":\"").append(sanitize(note)).append('"')
            append("}")
        }
    }

    fun append(file: File, event: Event) {
        runCatching {
            file.parentFile?.mkdirs()
            if (file.isFile && file.length() > MAX_BYTES) file.delete()
            file.appendText(event.toJsonLine() + "\n")
        }
    }

    /**
     * Events never carry raw installer output: notes are truncated and any
     * URL-like token is stripped (requirement: no internals in the stream).
     */
    fun sanitize(text: String): String =
        text.replace(URL_LIKE, "<url>")
            .replace(PATH_LIKE, "<path>")
            .replace("\"", "'")
            .replace("\n", " ")
            .take(160)

    private val URL_LIKE = Regex("(?i)https?://\\S+")
    private val PATH_LIKE = Regex("/(?:data|storage|system)[^\\s\"']*")

    private const val MAX_BYTES = 256L * 1024
}
