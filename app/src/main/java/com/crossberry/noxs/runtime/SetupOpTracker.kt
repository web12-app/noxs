/*
 * Noxs — original implementation.
 * Live long-running operation tracking for the setup console (pure Kotlin,
 * JVM-testable).
 *
 * The tracker owns ONLY presentation facts: the current operation name, its
 * monotonic start time, the spinner frame and the formatted elapsed time.
 * It never touches the installer, the shell, or any log content — the real
 * setup output always flows independently.
 *
 * Elapsed time comes from a monotonic clock (System.nanoTime), so going to
 * the background, activity recreation or process pauses never reset or fake
 * the timer. No synthetic progress percentages are produced anywhere.
 */
package com.crossberry.noxs.runtime

import java.util.Locale

class SetupOpTracker(
    /** Monotonic milliseconds source; overridable for tests. */
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L }
) {

    data class Operation(
        val name: String,
        val startedAtMs: Long,
        /** Elapsed ms captured when the operation ended; −1 while running. */
        val finalMs: Long = -1L,
        val failed: Boolean = false
    )

    val current: Operation?
        get() = op

    fun start(name: String) {
        op = Operation(name, nowMs())
        longOpHintShown = false
    }

    /** Ends the current operation; returns the completed record (or null). */
    fun finish(success: Boolean): Operation? {
        val cur = op ?: return null
        val done = cur.copy(finalMs = elapsedMs(), failed = !success)
        op = null
        return done
    }

    /** Monotonic elapsed of the current operation, 0 when idle. */
    fun elapsedMs(): Long = op?.let { (nowMs() - it.startedAtMs).coerceAtLeast(0L) } ?: 0L

    /** Current braille spinner frame; advances every SPINNER_PERIOD_MS. */
    fun spinnerFrame(): Char {
        val started = op?.startedAtMs ?: return FRAMES[0]
        val index = ((nowMs() - started) / SPINNER_PERIOD_MS).toInt()
        return FRAMES[index % FRAMES.size]
    }

    /** True once the same operation has been running unusually long. */
    fun isLongRunning(): Boolean = elapsedMs() >= LONG_OP_HINT_MS

    /** One-shot gate so the "still working" hint prints at most once per op. */
    fun consumeLongOpHint(): Boolean {
        if (!isLongRunning() || longOpHintShown) return false
        longOpHintShown = true
        return true
    }

    private var op: Operation? = null
    private var longOpHintShown = false

    companion object {
        /** Smooth, terminal-safe braille spinner. */
        val FRAMES = charArrayOf('⠋', '⠙', '⠹', '⠸', '⠼', '⠴', '⠦', '⠧', '⠇', '⠏')
        const val SPINNER_PERIOD_MS = 100L
        const val LONG_OP_HINT_MS = 60_000L

        /**
         * MM:SS below one hour, H:MM:SS above — 00:04, 00:59, 01:02:14.
         */
        fun formatElapsed(ms: Long): String {
            val totalSeconds = ms.coerceAtLeast(0L) / 1000L
            val hours = totalSeconds / 3600L
            val minutes = (totalSeconds % 3600L) / 60L
            val seconds = totalSeconds % 60L
            return if (hours > 0L) {
                String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(Locale.US, "%02d:%02d", minutes, seconds)
            }
        }
    }
}
