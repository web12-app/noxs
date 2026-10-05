/*
 * Noxs — original implementation.
 * Data model for the Background Activity Center: every piece of work Noxs
 * tracks (terminal sessions, long commands, services, code-server, Docker)
 * is one NoxsActivityRecord with a single lifecycle status machine.
 */
package com.crossberry.noxs.runtime

import java.net.URLDecoder
import java.net.URLEncoder

/** Lifecycle of a tracked activity. */
enum class NoxsActivityStatus {
    QUEUED, STARTING, RUNNING, COMPLETED, FAILED, STOPPING, STOPPED;

    val isTerminal: Boolean get() = this == COMPLETED || this == FAILED || this == STOPPED
    val isActive: Boolean get() = !isTerminal && this != QUEUED
}

/** What kind of work the record represents. */
object NoxsActivityKind {
    const val SESSION = "session"      // interactive terminal shell
    const val COMMAND = "command"      // long command run inside a session
    const val SERVICE = "service"      // noxs-managed daemon/service
    const val DOCKER = "docker"        // Docker container/process Noxs owns
    const val CODESERVER = "codeserver"
    const val PACKAGE = "package"      // apt/dpkg operation
    const val BUILD = "build"
}

/**
 * One tracked background activity. Plain data only — all behavior lives in
 * NoxsActivityCenter so the model stays trivially serializable.
 */
data class NoxsActivityRecord(
    val activityId: String,
    val sessionId: String,
    val title: String,
    val command: String,
    val kind: String = NoxsActivityKind.SESSION,
    val status: NoxsActivityStatus = NoxsActivityStatus.QUEUED,
    val startedAt: Long = 0L,
    val finishedAt: Long? = null,
    val progress: Int? = null,          // 0..100, null when unknown
    val outputSummary: String = "",     // most recent output line (sanitized)
    val pid: Int? = null,               // host-side process id when known
    val workingDirectory: String = "",
    val exitCode: Int? = null,
    /** Persistent services (code-server, Docker) keep Noxs alive after the last shell exits. */
    val pinned: Boolean = false
) {
    val isRunning: Boolean get() = status == NoxsActivityStatus.RUNNING

    /** Human uptime like 00:14:32 (or 3d 02:14:32 for very long work). */
    fun uptimeText(nowMs: Long): String {
        val end = finishedAt ?: nowMs
        val total = (end - startedAt).coerceAtLeast(0L) / 1000L
        return NoxsActivityFormat.uptime(total)
    }
}

/** Formatting helpers shared by the notification, floating panel and Activity Center. */
object NoxsActivityFormat {

    fun uptime(totalSeconds: Long): String {
        val days = totalSeconds / 86_400L
        val hours = (totalSeconds % 86_400L) / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        val hms = "%02d:%02d:%02d".format(hours, minutes, seconds)
        return if (days > 0) "${days}d $hms" else hms
    }

    /** Last "NN%" token in terminal output, or null (e.g. `████████░░ 82%`). */
    fun parseProgress(line: String): Int? {
        val matches = PROGRESS_REGEX.findAll(line).toList()
        val value = matches.lastOrNull()?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return value.takeIf { it in 0..100 }
    }

    private val PROGRESS_REGEX = Regex("""(?:^|[\s█░▒▓|>=(\[])(\d{1,3})\s?%\s*$""")

    /** Short single-line summary for notifications/panels. */
    fun summarize(line: String): String =
        line.replace(Regex("""\u001B\[[0-9;]*[A-Za-z]"""), "")
            .replace('\r', ' ')
            .trim()
            .takeLast(120)
            .ifBlank { line.trim().takeLast(120) }
}

/**
 * Line-based persistence (v1). One record per line, fields separated by U+0001
 * with URL escaping — dependency-free, corruption-tolerant (bad lines skipped).
 * Persisted metadata is intentionally minimal: no terminal output history, no
 * secrets — only what the Activity Center UI needs to restore after recreation.
 */
object NoxsActivityCodec {

    const val MAGIC = "noxs-activities-v1"

    fun encode(records: List<NoxsActivityRecord>): String = buildString {
        append(MAGIC).append('\n')
        records.forEach { r ->
            appendField(r.activityId); appendField(r.sessionId)
            appendField(r.title); appendField(r.command)
            appendField(r.kind); appendField(r.status.name)
            appendField(r.startedAt.toString())
            appendField(r.finishedAt?.toString() ?: "")
            appendField(r.progress?.toString() ?: "")
            appendField(r.outputSummary)
            appendField(r.pid?.toString() ?: "")
            appendField(r.workingDirectory)
            appendField(r.exitCode?.toString() ?: "")
            appendField(if (r.pinned) "1" else "0")
            append('\n')
        }
    }

    fun decode(text: String): List<NoxsActivityRecord> =
        text.lineSequence()
            .filter { it.isNotBlank() && it != MAGIC }
            .mapNotNull { line ->
                runCatching { parseLine(line) }.getOrNull()
            }
            .toList()

    private fun parseLine(line: String): NoxsActivityRecord? {
        val f = line.split('\u0001').map { URLDecoder.decode(it, "UTF-8") }
        if (f.size < 13) return null
        val status = NoxsActivityStatus.entries.firstOrNull { it.name == f[5] }
            ?: return null
        return NoxsActivityRecord(
            activityId = f[0],
            sessionId = f[1],
            title = f[2],
            command = f[3],
            kind = f[4].ifBlank { NoxsActivityKind.SESSION },
            status = status,
            startedAt = f[6].toLongOrNull() ?: 0L,
            finishedAt = f[7].toLongOrNull(),
            progress = f[8].toIntOrNull()?.takeIf { it in 0..100 },
            outputSummary = f[9],
            pid = f[10].toIntOrNull()?.takeIf { it > 0 },
            workingDirectory = f[11],
            exitCode = f[12].toIntOrNull(),
            pinned = f.getOrNull(13) == "1"
        )
    }

    private fun StringBuilder.appendField(value: String) {
        append(URLEncoder.encode(value, "UTF-8"))
        append('\u0001')
    }
}
