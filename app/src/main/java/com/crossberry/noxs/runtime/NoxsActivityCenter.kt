/*
 * Noxs — original implementation.
 * NoxsActivityCenter: the single source of truth for background activity.
 *
 * Every terminal session, long-running command, service and code-server/Docker
 * process Noxs owns registers here. The center survives Activity recreation
 * and configuration changes (it lives outside any Activity), persists its
 * metadata to app-private storage, and drives the foreground-service
 * notification. UI layers only observe — they never own session state.
 */
package com.crossberry.noxs.runtime

import com.crossberry.noxs.shared.NoxsLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Abstraction over signal delivery so the stop path is unit-testable. */
interface NoxsSignaller {
    fun terminate(pid: Int)      // SIGTERM
    fun kill(pid: Int)           // SIGKILL
    fun isAlive(pid: Int): Boolean
}

class NoxsActivityCenter(private val signaller: NoxsSignaller) {

    private val _records = MutableStateFlow<List<NoxsActivityRecord>>(emptyList())
    val records: StateFlow<List<NoxsActivityRecord>> = _records

    /** Wired by NoxsService: performs the real session/process stop. */
    var stopHandler: ((NoxsActivityRecord) -> Unit)? = null

    private val seq = AtomicLong(System.currentTimeMillis())

    // Output summaries are kept in memory only; persistence stores the last line.
    private val outputBuffers = HashMap<String, ArrayDeque<String>>()
    private var storageFile: File? = null

    // ---------------------------------------------------------------- create

    fun newId(): String = "act-${seq.incrementAndGet()}"

    fun register(
        title: String,
        command: String,
        sessionId: String = "",
        kind: String = NoxsActivityKind.SESSION,
        pid: Int? = null,
        workingDirectory: String = "",
        status: NoxsActivityStatus = NoxsActivityStatus.RUNNING,
        pinned: Boolean = false
    ): NoxsActivityRecord {
        val record = NoxsActivityRecord(
            activityId = newId(),
            sessionId = sessionId,
            title = title,
            command = command,
            kind = kind,
            status = status,
            startedAt = System.currentTimeMillis(),
            pid = pid,
            workingDirectory = workingDirectory,
            pinned = pinned
        )
        _records.value = _records.value + record
        persist()
        return record
    }

    // ---------------------------------------------------------------- mutate

    fun markStarting(activityId: String) = transition(activityId) { current ->
        if (current.status == NoxsActivityStatus.QUEUED) current.copy(status = NoxsActivityStatus.STARTING) else current
    }

    fun markRunning(activityId: String, pid: Int? = null) = transition(activityId) { current ->
        current.copy(
            status = NoxsActivityStatus.RUNNING,
            pid = pid ?: current.pid,
            finishedAt = null
        )
    }

    /** Attach a new output line; refreshes the summary and auto-parses progress. */
    fun attachOutput(activityId: String, line: String) {
        val clean = NoxsActivityFormat.summarize(line)
        if (clean.isEmpty()) return
        synchronized(outputBuffers) {
            val buffer = outputBuffers.getOrPut(activityId) { ArrayDeque() }
            buffer.addLast(clean)
            while (buffer.size > OUTPUT_LINES) buffer.removeFirst()
        }
        transition(activityId) { current ->
            current.copy(
                outputSummary = clean,
                progress = NoxsActivityFormat.parseProgress(clean) ?: current.progress
            )
        }
    }

    fun recentOutput(activityId: String, maxLines: Int = 20): List<String> =
        synchronized(outputBuffers) {
            outputBuffers[activityId]?.toList()?.takeLast(maxLines) ?: emptyList()
        }

    fun setProgress(activityId: String, percent: Int) = transition(activityId) { current ->
        if (percent in 0..100) current.copy(progress = percent) else current
    }

    fun markCompleted(activityId: String, exitCode: Int? = null) = transition(activityId) { current ->
        current.copy(
            status = NoxsActivityStatus.COMPLETED,
            exitCode = exitCode ?: current.exitCode,
            finishedAt = System.currentTimeMillis(),
            progress = 100
        )
    }

    fun markFailed(activityId: String, exitCode: Int? = null, summary: String = "") =
        transition(activityId) { current ->
            current.copy(
                status = NoxsActivityStatus.FAILED,
                exitCode = exitCode ?: current.exitCode,
                finishedAt = System.currentTimeMillis(),
                outputSummary = summary.ifBlank { current.outputSummary }
            )
        }

    fun markStopped(activityId: String) = transition(activityId) { current ->
        current.copy(status = NoxsActivityStatus.STOPPED, finishedAt = System.currentTimeMillis())
    }

    /** User/service requested a stop: flip to STOPPING, then run the safe stop. */
    fun requestStop(activityId: String) {
        val record = transition(activityId) { current ->
            if (current.status.isTerminal) current else current.copy(status = NoxsActivityStatus.STOPPING)
        } ?: return
        if (record.status.isTerminal) return
        NoxsLog.i("Activity", "stopping '${record.title}' (${record.activityId})")
        stopHandler?.invoke(record) ?: run {
            // No handler wired (e.g. service down) — fall back to direct signals.
            record.pid?.let { stopProcessTree(it) }
            markStopped(activityId)
        }
    }

    /** Called by the service after the handler finished, to reconcile state. */
    fun settleStop(activityId: String, exitCode: Int? = null) {
        val record = find(activityId) ?: return
        when {
            exitCode == null || exitCode == 0 -> markStopped(activityId)
            exitCode < 0 -> markStopped(activityId)
            else -> markFailed(activityId, exitCode)
        }
    }

    fun clearFinished() {
        _records.value = _records.value.filter { !it.status.isTerminal }
        synchronized(outputBuffers) {
            _records.value.forEach { outputBuffers.remove(it.activityId) }
        }
        persist()
    }

    fun clearAll() {
        _records.value = emptyList()
        synchronized(outputBuffers) { outputBuffers.clear() }
        persist()
    }

    // ------------------------------------------------------------------ read

    fun find(activityId: String): NoxsActivityRecord? =
        _records.value.firstOrNull { it.activityId == activityId }

    fun active(): List<NoxsActivityRecord> =
        _records.value.filter { it.status.isActive || it.status == NoxsActivityStatus.QUEUED }

    fun runningForSession(sessionId: String): List<NoxsActivityRecord> =
        active().filter { it.sessionId == sessionId }

    /**
     * True when at least one activity is still doing work. Used by the smart
     * exit path: the last shell may exit while services keep Noxs alive.
     */
    fun hasPersistentWork(): Boolean = active().isNotEmpty()

    // ------------------------------------------------------------------ stop

    /**
     * Safe termination: SIGTERM → wait up to [gracefulMs] → SIGKILL only if
     * still alive. Never touches unrelated processes.
     */
    fun stopProcessTree(pid: Int, gracefulMs: Long = 3_000L) {
        if (pid <= 0) return
        runCatching { signaller.terminate(pid) }
        val deadline = System.currentTimeMillis() + gracefulMs
        while (System.currentTimeMillis() < deadline) {
            if (!signaller.isAlive(pid)) return
            try { Thread.sleep(100) } catch (_: InterruptedException) { return }
        }
        runCatching { signaller.kill(pid) }
    }

    // ------------------------------------------------------------ persistence

    fun attachStorage(file: File) {
        storageFile = file
        load()
    }

    private fun persist() {
        val file = storageFile ?: return
        synchronized(persistLock) {
            try {
                file.parentFile?.mkdirs()
                val tmp = File(file.absolutePath + ".tmp")
                tmp.writeText(NoxsActivityCodec.encode(_records.value), Charsets.UTF_8)
                if (!tmp.renameTo(file)) {
                    tmp.copyTo(file, overwrite = true)
                    tmp.delete()
                }
            } catch (e: Exception) {
                NoxsLog.w("Activity", "persist failed: ${e.message}")
            }
        }
    }

    private fun load() {
        val file = storageFile ?: return
        try {
            if (!file.isFile) return
            val loaded = NoxsActivityCodec.decode(file.readText(Charsets.UTF_8))
                // Activities that were RUNNING when the process died are stale;
                // surface them as failed so the UI never shows phantom work.
                .map {
                    if (it.status.isActive && !it.pinned) {
                        it.copy(
                            status = NoxsActivityStatus.FAILED,
                            finishedAt = it.finishedAt ?: System.currentTimeMillis(),
                            outputSummary = it.outputSummary.ifBlank { "Noxs was closed" }
                        )
                    } else it
                }
            _records.value = loaded
        } catch (e: Exception) {
            NoxsLog.w("Activity", "load failed: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ util

    private fun transition(
        activityId: String,
        transform: (NoxsActivityRecord) -> NoxsActivityRecord
    ): NoxsActivityRecord? {
        val list = _records.value
        val index = list.indexOfFirst { it.activityId == activityId }
        if (index < 0) return null
        val updated = transform(list[index])
        _records.value = list.toMutableList().apply { this[index] = updated }
        persist()
        return updated
    }

    private companion object {
        const val OUTPUT_LINES = 32
        val persistLock = Any()
    }
}
