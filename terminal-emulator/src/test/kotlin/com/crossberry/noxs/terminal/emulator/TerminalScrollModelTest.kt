/*
 * Noxs — original implementation.
 * JVM tests for the scroll-mode policy (Normal / Smart / History Mirror),
 * the new-output counter and the indicator labels.
 */
package com.crossberry.noxs.terminal.emulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalScrollModelTest {

    // ---- Smart (default) ----

    @Test
    fun `smart follows output while anchored and counts nothing`() {
        val model = TerminalScrollModel()
        model.mode = TerminalScrollMode.SMART

        assertTrue(model.onOutputPushed(5, userAtBottom = true, sessionHasProcess = true))
        assertEquals(0, model.newLinesBehind)
        assertTrue(model.shouldShowIndicator(userAtBottom = true))
        assertNull(model.indicatorLabel(userAtBottom = true, viewportOffset = 0))
    }

    @Test
    fun `smart stops following when the user scrolls away and counts new lines`() {
        val model = TerminalScrollModel()
        model.mode = TerminalScrollMode.SMART

        model.onUserScroll(userAtBottom = false)
        assertFalse(model.onOutputPushed(10, userAtBottom = false, sessionHasProcess = true))
        assertEquals(10, model.newLinesBehind)
        model.onOutputPushed(8, userAtBottom = false, sessionHasProcess = true)
        assertEquals(18, model.newLinesBehind)

        assertEquals("18 new lines", model.indicatorLabel(userAtBottom = false, viewportOffset = 18))
    }

    @Test
    fun `smart resumes following when the user reaches the bottom naturally`() {
        val model = TerminalScrollModel()
        model.mode = TerminalScrollMode.SMART
        model.onUserScroll(false)
        model.onOutputPushed(7, userAtBottom = false)
        assertEquals(7, model.newLinesBehind)

        model.onUserScroll(true)
        assertTrue(model.onOutputPushed(3, userAtBottom = true))
        assertEquals(0, model.newLinesBehind)
    }

    @Test
    fun `smart with follow disabled holds the reading position`() {
        val model = TerminalScrollModel()
        model.mode = TerminalScrollMode.SMART
        model.followLiveOutput = false

        assertFalse(model.onOutputPushed(4, userAtBottom = true, sessionHasProcess = true))
        assertEquals(4, model.newLinesBehind)
    }

    @Test
    fun `smart with auto-follow off stops following while a process prints`() {
        val model = TerminalScrollModel()
        model.mode = TerminalScrollMode.SMART
        model.autoFollowWhileRunning = false

        assertFalse(model.onOutputPushed(2, userAtBottom = true, sessionHasProcess = true))
        assertEquals(2, model.newLinesBehind)
    }

    @Test
    fun `smart ignores auto-follow when no process is attached`() {
        val model = TerminalScrollModel()
        model.mode = TerminalScrollMode.SMART
        model.autoFollowWhileRunning = false

        assertTrue(model.onOutputPushed(2, userAtBottom = true, sessionHasProcess = false))
        assertEquals(0, model.newLinesBehind)
    }

    // ---- Normal ----

    @Test
    fun `normal mode follows when anchored and keeps no counter`() {
        val model = TerminalScrollModel()
        model.mode = TerminalScrollMode.NORMAL
        model.onUserScroll(false)

        assertFalse(model.onOutputPushed(9, userAtBottom = false))
        assertFalse(model.shouldShowIndicator(userAtBottom = false))
        assertNull(model.indicatorLabel(userAtBottom = false, viewportOffset = 9))
    }

    @Test
    fun `normal mode holds position after the user scrolled away`() {
        val model = TerminalScrollModel()
        model.mode = TerminalScrollMode.NORMAL
        model.onUserScroll(false)
        assertFalse(model.onOutputPushed(5, userAtBottom = false))
        assertTrue(model.onOutputPushed(5, userAtBottom = true))
    }

    // ---- History Mirror ----

    @Test
    fun `history mirror reports lines behind and LIVE at the bottom`() {
        val model = TerminalScrollModel()
        model.mode = TerminalScrollMode.HISTORY
        model.onUserScroll(false)
        model.onOutputPushed(124, userAtBottom = false)

        assertEquals("124 lines behind", model.indicatorLabel(userAtBottom = false, viewportOffset = 124))
        assertNull(model.indicatorLabel(userAtBottom = true, viewportOffset = 0))
    }

    @Test
    fun `history mirror indicator can be disabled by the show setting`() {
        val model = TerminalScrollModel()
        model.mode = TerminalScrollMode.HISTORY
        model.showNewOutputIndicator = false
        model.onUserScroll(false)

        assertFalse(model.shouldShowIndicator(userAtBottom = false))
        assertNull(model.indicatorLabel(userAtBottom = false, viewportOffset = 12))
    }

    // ---- alternate screen ----

    @Test
    fun `alternate screen programs always stay live`() {
        val model = TerminalScrollModel()
        model.mode = TerminalScrollMode.SMART
        model.onUserScroll(false)
        model.onOutputPushed(30, userAtBottom = false, usingAltScreen = true)

        assertTrue(model.onOutputPushed(1, userAtBottom = true, sessionHasProcess = true, usingAltScreen = true))
        assertEquals(0, model.newLinesBehind)
    }

    // ---- reset ----

    @Test
    fun `reset clears the counter and follows again`() {
        val model = TerminalScrollModel()
        model.onUserScroll(false)
        model.onOutputPushed(50, userAtBottom = false)
        assertEquals(50, model.newLinesBehind)

        model.reset()
        assertEquals(0, model.newLinesBehind)
        assertTrue(model.isFollowing)
    }

    @Test
    fun `no pushed rows means no change`() {
        val model = TerminalScrollModel()
        assertTrue(model.onOutputPushed(0, userAtBottom = true))
        assertEquals(0, model.newLinesBehind)
    }
}
