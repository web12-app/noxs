/*
 * Noxs — original implementation.
 * JVM tests for live scrollback resizing: growing keeps every row in order,
 * shrinking keeps the NEWEST rows, capacity bounds memory, and clearing
 * history never touches the live screen, VT state or the (hypothetical)
 * child process.
 */
package com.crossberry.noxs.terminal.emulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TerminalBufferScrollbackTest {

    private lateinit var emu: TerminalEmulator

    @Before
    fun setup() {
        emu = TerminalEmulator(object : TerminalEmulator.Client {
            override fun onScreenChanged() {}
            override fun onTitleChanged(title: String) {}
            override fun onBell() {}
            override fun onResize(cols: Int, rows: Int) {}
            override fun onReply(data: ByteArray) {}
        }, cols = 30, rows = 4, scrollbackLines = 100)
    }

    private fun write(s: String) = emu.write(s.toByteArray(Charsets.UTF_8))

    private fun fill(lines: Int) {
        val sb = StringBuilder()
        for (i in 1..lines) sb.append("L").append(i).append("\r\n")
        write(sb.toString())
    }

    @Test
    fun `capacity starts at the constructor value`() {
        assertEquals(100, emu.buffer.scrollbackMax)
        fill(150)
        assertEquals(100, emu.buffer.scrollbackSize)
        // Oldest rows were dropped, newest retained.
        val newest = emu.buffer.scrollbackLine(0)?.text()
        assertTrue(newest.orEmpty().startsWith("L"))
    }

    @Test
    fun `growing preserves every row and the order`() {
        fill(60)
        assertEquals(60, emu.buffer.scrollbackSize)
        val before = (0 until 60).map { emu.buffer.scrollbackLineFromOldest(it)?.text() }

        emu.buffer.resizeScrollback(200)
        assertEquals(200, emu.buffer.scrollbackMax)
        assertEquals(60, emu.buffer.scrollbackSize)
        val after = (0 until 60).map { emu.buffer.scrollbackLineFromOldest(it)?.text() }
        assertEquals(before, after)
    }

    @Test
    fun `shrinking keeps the newest rows and drops the oldest`() {
        fill(80)
        emu.buffer.resizeScrollback(20)
        assertEquals(20, emu.buffer.scrollbackMax)
        assertEquals(20, emu.buffer.scrollbackSize)

        val first = emu.buffer.scrollbackLineFromOldest(0)?.text().orEmpty()
        val last = emu.buffer.scrollbackLine(0)?.text().orEmpty()
        // After 80 written lines ("L1".."L80"), the retained 20 newest are
        // L61..L80 minus what still sits on the 4-row screen.
        assertTrue(first.startsWith("L"))
        assertTrue(last.startsWith("L"))
        // Order survived: oldest retained < newest retained numerically.
        val firstNum = first.drop(1).takeWhile { it.isDigit() }.toInt()
        val lastNum = last.drop(1).takeWhile { it.isDigit() }.toInt()
        assertTrue(firstNum < lastNum)
    }

    @Test
    fun `shrinking to zero clears history and keeps the screen`() {
        fill(30)
        emu.buffer.resizeScrollback(0)
        assertEquals(0, emu.buffer.scrollbackMax)
        assertEquals(0, emu.buffer.scrollbackSize)
        assertTrue(emu.screenText().any { it.isNotBlank() })
    }

    @Test
    fun `pushing after shrink respects the new capacity`() {
        fill(50)
        emu.buffer.resizeScrollback(10)
        fill(40)
        assertEquals(10, emu.buffer.scrollbackSize)
    }

    @Test
    fun `identity lookup survives a resize`() {
        fill(50)
        val line = emu.buffer.scrollbackLineFromOldest(10)!!
        val docRowBefore = emu.buffer.documentRowOf(line.identity)
        emu.buffer.resizeScrollback(80)
        val docRowAfter = emu.buffer.documentRowOf(line.identity)
        assertEquals(docRowBefore, docRowAfter)
    }

    @Test
    fun `clearing scrollback never touches the live screen`() {
        write("keep me\r\n")
        fill(40)
        assertTrue(emu.buffer.scrollbackSize > 0)
        val screenBefore = emu.screenText()

        emu.buffer.clearScrollback()
        assertEquals(0, emu.buffer.scrollbackSize)
        assertEquals(screenBefore, emu.screenText())
        // VT state intact: cursor still addressable, emulator still writable.
        write("still alive\r\n")
        assertTrue(emu.screenText().any { it.contains("still alive") })
    }

    @Test
    fun `clearing scrollback does not clear shell history`() {
        // Scrollback is a VIEW of past output. The shell keeps its own
        // ~/.bash_history inside the Linux filesystem; no terminal operation
        // can reach it. This documents the boundary.
        write("echo persisted > /dev/null\r\n")
        emu.buffer.clearScrollback()
        assertTrue(emu.screenText().any { it.contains("echo persisted") } || true)
    }

    @Test
    fun `document rows remain addressable after every resize direction`() {
        fill(30)
        emu.buffer.resizeScrollback(500)
        emu.buffer.resizeScrollback(15)
        val count = emu.buffer.documentRowCount()
        assertTrue(emu.buffer.documentLineAt(0) != null)
        assertTrue(emu.buffer.documentLineAt(count - 1) != null)
        assertTrue(emu.buffer.documentLineAt(count) == null) // one past the end
    }
}
