/*
 * Noxs — original implementation.
 * Word selection expansion (long-press / double-tap Copy support).
 */
package com.crossberry.noxs.terminal.emulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalWordSelectionTest {
    private fun emulator(cols: Int = 40, rows: Int = 4, history: Int = 5000) =
        TerminalEmulator(object : TerminalEmulator.Client {
            override fun onScreenChanged() {}
            override fun onTitleChanged(title: String) {}
            override fun onBell() {}
            override fun onResize(cols: Int, rows: Int) {}
            override fun onReply(data: ByteArray) {}
        }, cols, rows, history)

    private fun writeLine(emu: TerminalEmulator, text: String) {
        emu.write(text.toByteArray(Charsets.UTF_8))
    }

    private fun wordRange(emu: TerminalEmulator, column: Int): IntRange? {
        // Fresh emulator: the written line is the first screen row.
        val line = emu.buffer.documentLineAt(0) ?: return null
        return emu.buffer.wordRangeAt(line.identity, column)
    }

    @Test fun `long press expands to the whole word`() {
        val emu = emulator()
        writeLine(emu, "hello world foo")
        // Tap inside "world" (columns 6..10).
        assertEquals(6..10, wordRange(emu, 8))
    }

    @Test fun `tap at word start and end produce the same range`() {
        val emu = emulator()
        writeLine(emu, "hello world foo")
        assertEquals(6..10, wordRange(emu, 6))
        assertEquals(6..10, wordRange(emu, 10))
    }

    @Test fun `tapping whitespace yields no word selection`() {
        val emu = emulator()
        writeLine(emu, "hello world foo")
        assertNull(wordRange(emu, 5))
        assertNull(wordRange(emu, 11))
    }

    @Test fun `punctuation is part of a word so URLs copy whole`() {
        val emu = emulator()
        writeLine(emu, "at http://localhost:8080 end")
        val range = wordRange(emu, 10)
        assertNotNull(range)
        val text = emu.buffer.documentLineAt(0)!!.text()
        val word = text.substring(range!!.first, range.last + 1)
        assertEquals("http://localhost:8080", word)
    }

    @Test fun `tapping empty padding selects nothing`() {
        val emu = emulator(cols = 40)
        writeLine(emu, "hi")
        // Columns beyond the text are padding spaces.
        assertNull(wordRange(emu, 20))
    }

    @Test fun `wide characters stay one word`() {
        val emu = emulator()
        writeLine(emu, "字")
        val range = wordRange(emu, 1)
        assertNotNull(range)
        assertEquals(0, range!!.first)
    }
}
