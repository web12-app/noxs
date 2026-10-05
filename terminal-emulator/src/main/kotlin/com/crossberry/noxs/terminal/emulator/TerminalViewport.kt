package com.crossberry.noxs.terminal.emulator

/** Scrollback position measured in rows above the live bottom of the PTY screen. */
class TerminalViewportState {
    var scrollOffsetFromBottom: Int = 0
        private set

    val isFollowingLiveOutput: Boolean
        get() = scrollOffsetFromBottom == 0

    private var lastScrollbackSerial: Long? = null
    private var wasUsingAltScreen = false
    private var suspendedMainOffset: Int? = null

    fun attach(buffer: TerminalBuffer, viewportRows: Int) {
        scrollOffsetFromBottom = 0
        wasUsingAltScreen = buffer.usingAlt
        suspendedMainOffset = if (buffer.usingAlt) 0 else null
        lastScrollbackSerial = buffer.scrollbackSerial
        clamp(buffer, viewportRows)
    }

    /**
     * Keep the same history rows under the viewport while new PTY output pushes
     * lines into scrollback. At offset zero, the view naturally follows live output.
     */
    fun synchronize(buffer: TerminalBuffer, viewportRows: Int) {
        val currentSerial = buffer.scrollbackSerial
        val previousSerial = lastScrollbackSerial
        val pushedRows = if (previousSerial == null) 0L else
            (currentSerial - previousSerial).coerceAtLeast(0L)
        val pushed = pushedRows.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

        if (buffer.usingAlt) {
            if (!wasUsingAltScreen) {
                val mainOffset = scrollOffsetFromBottom
                suspendedMainOffset = if (mainOffset > 0) {
                    (mainOffset.toLong() + pushed).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                } else 0
                scrollOffsetFromBottom = 0
            }
            // Keep alternate-screen scrolling independent while retaining the
            // main-screen history position for when the application exits it.
        } else {
            if (wasUsingAltScreen) {
                scrollOffsetFromBottom = suspendedMainOffset ?: 0
                suspendedMainOffset = null
            }
            if (scrollOffsetFromBottom > 0 && pushed > 0) {
                scrollOffsetFromBottom = (scrollOffsetFromBottom.toLong() + pushed)
                    .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            }
        }

        lastScrollbackSerial = currentSerial
        wasUsingAltScreen = buffer.usingAlt
        clamp(buffer, viewportRows)
    }

    /** Positive rows move toward older output; negative rows move toward live output. */
    fun scrollByRows(deltaRows: Int, buffer: TerminalBuffer, viewportRows: Int) {
        synchronize(buffer, viewportRows)
        scrollOffsetFromBottom = (scrollOffsetFromBottom.toLong() + deltaRows)
            .coerceIn(0L, buffer.maxScrollOffset(viewportRows).toLong()).toInt()
    }

    fun setOffsetFromBottom(offset: Int, buffer: TerminalBuffer, viewportRows: Int) {
        synchronize(buffer, viewportRows)
        scrollOffsetFromBottom = offset.coerceIn(0, buffer.maxScrollOffset(viewportRows))
    }

    fun scrollToBottom(buffer: TerminalBuffer, viewportRows: Int) {
        scrollOffsetFromBottom = 0
        suspendedMainOffset = if (buffer.usingAlt) 0 else null
        wasUsingAltScreen = buffer.usingAlt
        lastScrollbackSerial = buffer.scrollbackSerial
        clamp(buffer, viewportRows)
    }

    fun maxScrollOffset(buffer: TerminalBuffer, viewportRows: Int): Int =
        buffer.maxScrollOffset(viewportRows)

    fun viewportStartRow(buffer: TerminalBuffer, viewportRows: Int): Int =
        buffer.viewportStartDocumentRow(scrollOffsetFromBottom, viewportRows)

    private fun clamp(buffer: TerminalBuffer, viewportRows: Int) {
        scrollOffsetFromBottom = scrollOffsetFromBottom.coerceIn(0, buffer.maxScrollOffset(viewportRows))
    }
}

/**
 * Frame-rate-independent selection edge scrolling. Near-edge speed grows
 * quadratically toward a capped maximum; positive rows mean older history.
 */
class SelectionAutoScroller(
    private val edgeSizePx: Float,
    private val maxRowsPerSecond: Float = 40f,
    private val minimumRowsPerSecond: Float = 1.5f
) {
    private var fractionalRows = 0f

    fun rowsPerSecond(pointerY: Float, viewportHeight: Float): Float {
        if (viewportHeight <= 0f || edgeSizePx <= 0f) return 0f
        val edge = minOf(edgeSizePx, viewportHeight / 2f)
        if (pointerY < edge) {
            val depth = ((edge - pointerY) / edge).coerceIn(0f, 1f)
            return minimumRowsPerSecond + maxRowsPerSecond * depth * depth
        }
        val bottomEdge = viewportHeight - edge
        if (pointerY > bottomEdge) {
            val depth = ((pointerY - bottomEdge) / edge).coerceIn(0f, 1f)
            return -(minimumRowsPerSecond + maxRowsPerSecond * depth * depth)
        }
        return 0f
    }

    /** Returns an integral row delta; fractional motion accumulates across frames. */
    fun step(pointerY: Float, viewportHeight: Float, elapsedMs: Long): Int {
        val speed = rowsPerSecond(pointerY, viewportHeight)
        if (speed == 0f || elapsedMs <= 0L) return 0
        fractionalRows += speed * elapsedMs.coerceAtMost(100L) / 1000f
        val wholeRows = fractionalRows.toInt()
        fractionalRows -= wholeRows
        return wholeRows
    }

    fun reset() {
        fractionalRows = 0f
    }
}
