package com.crossberry.noxs.terminal.emulator

import kotlin.math.abs

/**
 * Gesture ownership for the terminal surface. A handle down owns the stream
 * before GestureDetector can interpret it as a terminal scroll; a long-press
 * can promote only an ordinary down to text selection.
 */
class TerminalGesturePolicy {
    enum class Owner { IDLE, SCROLL_OR_TAP, TEXT_SELECTION, SELECTION_HANDLE }

    var owner: Owner = Owner.IDLE
        private set

    val isSelecting: Boolean get() = owner == Owner.TEXT_SELECTION
    val isDraggingHandle: Boolean get() = owner == Owner.SELECTION_HANDLE

    fun onDown(onSelectionHandle: Boolean) {
        owner = if (onSelectionHandle) Owner.SELECTION_HANDLE else Owner.SCROLL_OR_TAP
    }

    /** Returns true only when a regular tap/scroll stream becomes selection. */
    fun onLongPress(): Boolean {
        if (owner != Owner.SCROLL_OR_TAP) return false
        owner = Owner.TEXT_SELECTION
        return true
    }

    fun shouldStartVerticalScroll(deltaX: Float, deltaY: Float, touchSlop: Float): Boolean =
        abs(deltaY) > touchSlop && abs(deltaY) >= abs(deltaX)

    /** Positive means a finger moved upward, so older terminal rows should appear. */
    fun verticalScrollDelta(previousY: Float, currentY: Float): Float = previousY - currentY

    fun finish() {
        owner = Owner.IDLE
    }
}
