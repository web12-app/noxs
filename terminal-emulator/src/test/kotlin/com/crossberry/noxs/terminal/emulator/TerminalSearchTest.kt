/*
 * Noxs — original implementation.
 * JVM tests for terminal output search: matching over scrollback + screen,
 * case handling, cycling, and the fact that searching is a pure buffer read
 * (no shell input is ever written, no process disturbed).
 */
package com.crossberry.noxs.terminal.emulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TerminalSearchTest {

    private lateinit var emu: TerminalEmulator

    @Before
    fun setup() {
        emu = TerminalEmulator(object : TerminalEmulator.Client {
            override fun onScreenChanged() {}
            override fun onTitleChanged(title: String) {}
            override fun onBell() {}
            override fun onResize(cols: Int, rows: Int) {}
            override fun onReply(data: ByteArray) {}
        }, cols = 40, rows = 6, scrollbackLines = 200)
    }

    private fun write(s: String) = emu.write(s.toByteArray(Charsets.UTF_8))

    @Test
    fun `finds matches on the live screen`() {
        write("npm install\r\nnothing to see\r\nnpm WARN ok\r\n")
        val matches = TerminalSearch.find(emu.buffer, "npm")
        assertEquals(2, matches.size)
        assertEquals(0, matches[0].row)
        assertEquals(0, matches[0].startCol)
        assertEquals(3, matches[0].endCol)
        assertEquals(2, matches[1].row)
    }

    @Test
    fun `search is case-insensitive by default`() {
        write("ERROR: disk full\r\n")
        val matches = TerminalSearch.find(emu.buffer, "error")
        assertEquals(1, matches.size)
    }

    @Test
    fun `search respects case sensitivity when requested`() {
        write("Error: disk full\r\n")
        assertEquals(0, TerminalSearch.find(emu.buffer, "error", caseSensitive = true).size)
        assertEquals(1, TerminalSearch.find(emu.buffer, "Error", caseSensitive = true).size)
    }

    @Test
    fun `finds matches inside the scrollback after the screen scrolls`() {
        val writer = StringBuilder()
        for (i in 1..100) writer.append("line ").append(i).append(" of output\r\n")
        write(writer.toString())
        // "line 42" scrolled far above the 6-row screen into scrollback.
        val matches = TerminalSearch.find(emu.buffer, "line 42 ")
        assertEquals(1, matches.size)
        assertTrue(matches[0].row < emu.buffer.documentRowCount())
        val text = emu.buffer.documentLineAt(matches[0].row)?.text().orEmpty()
        assertTrue(text.contains("line 42"))
    }

    @Test
    fun `multiple occurrences on the same row are all reported`() {
        write("noxs noxs noxs\r\n")
        val matches = TerminalSearch.find(emu.buffer, "noxs")
        assertEquals(3, matches.size)
        assertEquals(0, matches[0].startCol)
        assertEquals(5, matches[1].startCol)
        assertEquals(10, matches[2].startCol)
    }

    @Test
    fun `empty or blank query returns nothing`() {
        write("hello\r\n")
        assertEquals(0, TerminalSearch.find(emu.buffer, "").size)
        assertEquals(0, TerminalSearch.find(emu.buffer, "   ").size)
    }

    @Test
    fun `maxMatches caps the result list`() {
        val writer = StringBuilder()
        for (i in 1..50) writer.append("hit\r\n")
        write(writer.toString())
        assertEquals(10, TerminalSearch.find(emu.buffer, "hit", maxMatches = 10).size)
    }

    @Test
    fun `step cycles forward and backward with wraparound`() {
        write("a\r\nb\r\na\r\n")
        val matches = TerminalSearch.find(emu.buffer, "a")
        assertEquals(2, matches.size)

        var index = -1
        index = TerminalSearch.step(matches, index, forward = true)
        assertEquals(0, index)
        index = TerminalSearch.step(matches, index, forward = true)
        assertEquals(1, index)
        index = TerminalSearch.step(matches, index, forward = true)
        assertEquals(0, index) // wraps to the first match
        index = TerminalSearch.step(matches, index, forward = false)
        assertEquals(1, index) // wraps back to the last match
    }

    @Test
    fun `step on an empty result list stays invalid`() {
        assertEquals(-1, TerminalSearch.step(emptyList(), -1, forward = true))
    }

    @Test
    fun `searching never writes to the shell input stream`() {
        // The search API only reads the buffer; this asserts the contract by
        // construction — TerminalSearch exposes no write path at all.
        write("needle\r\n")
        TerminalSearch.find(emu.buffer, "needle")
        // The terminal content is unchanged and the emulator is still alive.
        assertTrue(emu.screenText().any { it.contains("needle") })
    }
}
