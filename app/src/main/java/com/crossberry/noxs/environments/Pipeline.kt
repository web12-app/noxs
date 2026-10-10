/*
 * Noxs — original implementation.
 * Install pipeline contract (spec §20 methods mapped to explicit Noxs-controlled
 * operations: detect → download → verify → extract → configure → verify → launch).
 *
 * InstallContext carries everything an environment provider needs at install
 * time, so provider CLASSES stay context-free (JVM-testable metadata/compat).
 */
package com.crossberry.noxs.environments

import com.crossberry.noxs.environments.model.SetupState
import com.crossberry.noxs.runtime.NoxsPaths
import com.crossberry.noxs.runtime.ProotLauncher
import java.io.File

/** Everything a provider may use while installing; provided by SetupTaskManager. */
class InstallContext(
    val context: android.content.Context,
    val paths: NoxsPaths,
    val launcher: ProotLauncher,
    val safeExtractor: SafeExtractor,
    val isCancelled: () -> Boolean,
    /** Stage transitions (spec §14) — persisted by the task manager. */
    val onStage: (SetupState, String) -> Unit,
    /** Human-readable progress log lines (also written to setup-<env>-<task>.log). */
    val onLog: (String) -> Unit,
    /** Real download progress (spec §10). */
    val onDownloadProgress: (bytesDone: Long, totalBytes: Long) -> Unit,
    /** Real extraction progress (cumulative tar entries). */
    val onExtractProgress: (entries: Long) -> Unit,
    /**
     * Password collector (spec §30): UI shows a masked dialog; the array is
     * in memory only, passed to chpasswd on stdin and zeroed afterwards.
     * Never logged, never stored, never persisted.
     */
    val passwordProvider: () -> CharArray?
) {
    fun checkCancelled() {
        if (isCancelled()) throw SetupCancelledException()
    }
}

/** Cooperative cancellation (mirrors the runtime package's exception). */
class SetupCancelledException : RuntimeException("Setup stopped by user")

/** Stage weights for honest overall progress (real sub-progress only). */
object StageWeights {
    val DOWNLOAD = 0.55
    val VERIFY = 0.05
    val EXTRACT = 0.25
    val CONFIGURE = 0.10
    val FINALIZE = 0.05

    /** Overall 0..100 from real download progress (unknown total → -1). */
    fun overallFromDownload(bytesDone: Long, totalBytes: Long): Int {
        if (totalBytes <= 0L) return -1
        val fraction = (bytesDone.toDouble() / totalBytes).coerceIn(0.0, 1.0)
        return (fraction * DOWNLOAD * 100).toInt().coerceIn(0, 100)
    }

    fun extractStage(entriesDone: Long, approximateTotalEntries: Long): Int {
        // Tar entry count is unknown upfront — honest entries-based estimate only
        // when we have a reference; otherwise indeterminate.
        if (approximateTotalEntries <= 0L) return -1
        val fraction = (entriesDone.toDouble() / approximateTotalEntries).coerceIn(0.0, 1.0)
        val base = ((DOWNLOAD + VERIFY) * 100).toInt()
        return (base + (fraction * EXTRACT * 100).toInt()).coerceIn(0, 99)
    }
}
