/*
 * Noxs terminal-emulator — original implementation.
 * Two-pointer gesture ownership policy for the terminal surface.
 *
 * Priority (spec): text selection handles first (single pointer, owned before
 * this policy runs), then pinch zoom, then two-finger scroll. When both a
 * span change and a translation appear on the same gesture, pinch wins ties.
 * A decided gesture is sticky until all pointers lift, so a pinch never
 * degenerates into a scroll and two-finger scrolling never sends shell input.
 */
package com.crossberry.noxs.terminal.emulator

import kotlin.math.abs
import kotlin.math.max

class TerminalMultiTouchPolicy(
    /** Same touch slop the single-finger scroll uses, in pixels. */
    val touchSlopPx: Float
) {
    enum class Mode { UNDECIDED, PINCH, TWO_FINGER_SCROLL, IGNORED }

    var mode: Mode = Mode.UNDECIDED
        private set

    /** True between a second pointer landing and all pointers lifting. */
    var active: Boolean = false
        private set

    var pinchZoomEnabled: Boolean = true
    var twoFingerScrollEnabled: Boolean = true

    private var baseSpan = 0f

    /** Second pointer landed; [baseSpanPx] is the initial two-pointer span. */
    fun onBegin(baseSpanPx: Float) {
        baseSpan = baseSpanPx
        mode = Mode.UNDECIDED
        active = true
    }

    /**
     * Feed each two-pointer move. [spanPx] is the current pointer span,
     * [dx]/[dy] the centroid translation since the last event. Returns the
     * decided mode; UNDECIDED means not enough evidence yet.
     */
    fun onMove(spanPx: Float, dx: Float, dy: Float): Mode {
        if (mode != Mode.UNDECIDED) return mode
        val spanDelta = abs(spanPx - baseSpan)
        val translate = max(abs(dx), abs(dy))
        if (pinchZoomEnabled && spanDelta >= touchSlopPx && spanDelta >= translate) {
            mode = Mode.PINCH
            return mode
        }
        if (translate >= touchSlopPx && translate > spanDelta) {
            mode = if (twoFingerScrollEnabled) Mode.TWO_FINGER_SCROLL else Mode.IGNORED
        }
        return mode
    }

    /** All pointers lifted or the gesture handed back to single-finger flow. */
    fun onEnd() {
        mode = Mode.UNDECIDED
        active = false
    }
}
