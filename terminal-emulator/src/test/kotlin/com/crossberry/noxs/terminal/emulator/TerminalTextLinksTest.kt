/*
 * Noxs — original implementation.
 * Plain-text URL detection over terminal text (localhost servers etc.).
 */
package com.crossberry.noxs.terminal.emulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalTextLinksTest {

    @Test
    fun findsHttpUrlWithPort() {
        val text = "code-server is running at http://127.0.0.1:8080/?folder=/root"
        val span = TerminalTextLinks.findUrlSpanAt(text, 30)!!
        assertEquals("http://127.0.0.1:8080/?folder=/root", span.second)
    }

    @Test
    fun findsLocalhostWithoutScheme() {
        val text = "Serving on localhost:3000"
        val index = text.indexOf("localhost") + 2
        assertEquals("localhost:3000", TerminalTextLinks.findUrlAt(text, index))
    }

    @Test
    fun findsLoopbackIpWithoutScheme() {
        val text = "listening on 127.0.0.1:5000/api"
        val index = text.indexOf("127") + 3
        assertEquals("127.0.0.1:5000/api", TerminalTextLinks.findUrlAt(text, index))
    }

    @Test
    fun trimsTrailingPunctuation() {
        val text = "open http://localhost:8080."
        val index = text.indexOf("open ") + 8
        assertEquals("http://localhost:8080", TerminalTextLinks.findUrlAt(text, index))
    }

    @Test
    fun missesPositionOutsideUrl() {
        val text = "open http://localhost:8080 now"
        assertNull(TerminalTextLinks.findUrlAt(text, 1))
        assertNull(TerminalTextLinks.findUrlAt(text, text.indexOf(" now") + 2))
    }

    @Test
    fun randomWordWithDigitsIsNotALink() {
        val text = "error 404: not found on host:12345x"
        assertNull(TerminalTextLinks.findUrlAt(text, 12))
    }

    @Test
    fun httpsUrlIsFoundAnywhere() {
        val text = "see https://example.com/docs for help"
        val index = text.indexOf("see ") + 6
        assertEquals("https://example.com/docs", TerminalTextLinks.findUrlAt(text, index))
    }

    @Test
    fun findAllReturnsEveryUrlOnce() {
        val text = "http://localhost:1 a localhost:2 b http://localhost:1"
        val all = TerminalTextLinks.findAll(text)
        assertEquals(listOf("http://localhost:1", "localhost:2"), all)
    }

    @Test
    fun cellColumnToTextIndexSkipsWideContinuations() {
        // Cells: A(0) 字(1..2, cell 2 is the continuation) B(3)
        val chars = charArrayOf('A', '字', '\u0000', 'B')
        val styles = LongArray(4)
        styles[2] = TextStyle.wideContOf(styles[1])
        assertEquals(0, TerminalTextLinks.cellColumnToTextIndex(chars, styles, 0))
        // Tapping the wide glyph or its continuation both map to the glyph.
        assertEquals(1, TerminalTextLinks.cellColumnToTextIndex(chars, styles, 1))
        assertEquals(1, TerminalTextLinks.cellColumnToTextIndex(chars, styles, 2))
        assertEquals(2, TerminalTextLinks.cellColumnToTextIndex(chars, styles, 3))
        assertEquals(2, TerminalTextLinks.cellColumnToTextIndex(chars, styles, 9))
    }

    @Test
    fun outOfBoundsIndexIsSafe() {
        assertNull(TerminalTextLinks.findUrlAt("http://localhost:1", -1))
        assertNull(TerminalTextLinks.findUrlAt("http://localhost:1", 999))
        assertTrue(TerminalTextLinks.findAll("").isEmpty())
    }
}
