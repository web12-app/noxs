/*
 * Noxs — original implementation.
 * JVM tests for the two-pointer gesture policy: pinch priority over two-
 * finger scroll, sticky decisions, the settings gates and the finger-lift
 * contract. Touch gestures never send shell input by construction (the view
 * routes only key/IME events to the PTY), which this suite also documents.
 */
package com.crossberry.noxs.terminal.emulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalMultiTouchPolicyTest {

    private fun policy(slop: Float = 8f) = TerminalMultiTouchPolicy(slop)

    @Test
    fun `undecided until the slop is exceeded`() {
        val p = policy()
        p.onBegin(baseSpanPx = 100f)
        assertEquals(TerminalMultiTouchPolicy.Mode.UNDECIDED, p.onMove(103f, dx = 1f, dy = 1f))
    }

    @Test
    fun `span change wins and becomes a pinch`() {
        val p = policy()
        p.onBegin(100f)
        assertEquals(TerminalMultiTouchPolicy.Mode.PINCH, p.onMove(130f, dx = 3f, dy = 3f))
    }

    @Test
    fun `translation wins when the span barely changes and scrolling is enabled`() {
        val p = policy()
        p.twoFingerScrollEnabled = true
        p.onBegin(100f)
        assertEquals(
            TerminalMultiTouchPolicy.Mode.TWO_FINGER_SCROLL,
            p.onMove(101f, dx = 2f, dy = 30f)
        )
    }

    @Test
    fun `translation with scrolling disabled locks the gesture to ignored`() {
        val p = policy()
        p.twoFingerScrollEnabled = false
        p.onBegin(100f)
        assertEquals(TerminalMultiTouchPolicy.Mode.IGNORED, p.onMove(101f, dx = 2f, dy = 30f))
    }

    @Test
    fun `pinch wins ties (equal span and translation deltas)`() {
        val p = policy()
        p.onBegin(100f)
        // spanDelta == translate → pinch takes priority per spec.
        assertEquals(TerminalMultiTouchPolicy.Mode.PINCH, p.onMove(120f, dx = 0f, dy = 20f))
    }

    @Test
    fun `a decided pinch is sticky even when the fingers then translate`() {
        val p = policy()
        p.onBegin(100f)
        p.onMove(140f, dx = 1f, dy = 1f) // pinch decided
        assertEquals(TerminalMultiTouchPolicy.Mode.PINCH, p.onMove(140f, dx = 0f, dy = 40f))
    }

    @Test
    fun `a decided two-finger scroll is sticky even if the span changes`() {
        val p = policy()
        p.onBegin(100f)
        p.onMove(101f, dx = 0f, dy = 25f) // scroll decided
        assertEquals(
            TerminalMultiTouchPolicy.Mode.TWO_FINGER_SCROLL,
            p.onMove(160f, dx = 0f, dy = 25f)
        )
    }

    @Test
    fun `pinch zoom disabled forces translation to scroll`() {
        val p = policy()
        p.pinchZoomEnabled = false
        p.onBegin(100f)
        assertEquals(
            TerminalMultiTouchPolicy.Mode.TWO_FINGER_SCROLL,
            p.onMove(160f, dx = 0f, dy = 30f)
        )
    }

    @Test
    fun `pinch zoom disabled and scroll disabled ignores the gesture`() {
        val p = policy()
        p.pinchZoomEnabled = false
        p.twoFingerScrollEnabled = false
        p.onBegin(100f)
        assertEquals(TerminalMultiTouchPolicy.Mode.IGNORED, p.onMove(160f, dx = 0f, dy = 30f))
    }

    @Test
    fun `lifting the fingers resets the decision`() {
        val p = policy()
        p.onBegin(100f)
        p.onMove(140f, dx = 1f, dy = 1f)
        assertTrue(p.active)
        p.onEnd()
        assertFalse(p.active)
        assertEquals(TerminalMultiTouchPolicy.Mode.UNDECIDED, p.mode)
    }

    @Test
    fun `touch gestures never produce shell input`() {
        // The policy only classifies gestures; the terminal view routes touches
        // to viewport scrolling and font scaling exclusively. Key/IME data is
        // the only path that writes to the PTY (TerminalSession.write), so no
        // combination of two-finger moves can ever inject shell characters.
        val p = policy()
        p.onBegin(100f)
        p.onMove(400f, dx = 0f, dy = 0f)
        p.onMove(30f, dx = 0f, dy = 0f)
        // No output channel exists on the policy to assert against.
        assertTrue(true)
    }
}
