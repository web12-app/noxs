package com.noxs.linux.terminal.emulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TerminalEmulatorTest {

    private lateinit var emu: TerminalEmulator
    private val replies = StringBuilder()
    private var bellCount = 0
    private var lastTitle = ""

    @Before
    fun setup() {
        replies.setLength(0)
        bellCount = 0
        lastTitle = ""
        emu = TerminalEmulator(object : TerminalEmulator.Client {
            override fun onScreenChanged() {}
            override fun onTitleChanged(title: String) { lastTitle = title }
            override fun onBell() { bellCount++ }
            override fun onResize(cols: Int, rows: Int) {}
            override fun onReply(data: ByteArray) { replies.append(String(data)) }
        }, cols = 20, rows = 6)
    }

    private fun write(s: String) = emu.write(s.toByteArray(Charsets.UTF_8))

    private fun screen() = emu.screenText()

    @Test fun `prints plain text and newline`() {
        write("whoami\r\nnoxs\r\n")
        val s = screen()
        assertEquals("whoami", s[0])
        assertEquals("noxs", s[1])
    }

    @Test fun `carriage return returns column 0`() {
        write("abcdef\rX")
        assertEquals("Xbcdef", screen()[0])
    }

    @Test fun `cursor positioning CUP`() {
        write("\u001b[2;3H")
        write("X")
        assertEquals("  X", screen()[1].substring(0, 3))
    }

    @Test fun `SGR colors are tracked in styles`() {
        write("\u001b[31mR\u001b[0m")
        val line = emu.buffer.screen()[0]
        assertEquals('R', line.chars[0])
        assertEquals(1, TextStyle.fg(line.styles[0])) // red palette index
        assertEquals(0, TextStyle.flags(line.styles[0]))
    }

    @Test fun `SGR bold and truecolor`() {
        write("\u001b[1;38;2;12;34;56mZ")
        val line = emu.buffer.screen()[0]
        assertTrue(TextStyle.flags(line.styles[0]) and TextStyle.FLAG_BOLD != 0)
        assertEquals((12 shl 16) or (34 shl 8) or 56, TextStyle.fg(line.styles[0]))
    }

    @Test fun `256 color SGR`() {
        write("\u001b[38;5;196mQ")
        assertEquals(196, TextStyle.fg(emu.buffer.screen()[0].styles[0]))
    }

    @Test fun `erase display ED2 clears screen`() {
        write("garbage")
        write("\u001b[2J")
        assertTrue(screen().all { it.isBlank() })
    }

    @Test fun `erase line EL keeps other rows`() {
        write("AAA\r\nBBB\r\nCCC")
        write("\u001b[2K") // erase entire current row — cursor sits on the row with CCC
        val s = screen()
        assertEquals("AAA", s[0])
        assertEquals("BBB", s[1])
        assertEquals("", s[2])
    }

    @Test fun `alt screen mode 1049 restores main content`() {
        write("MAIN")
        write("\u001b[?1049h") // enter alt
        write("ALT-SCREEN")
        assertTrue(screen().any { it.contains("ALT-SCREEN") })
        write("\u001b[?1049l") // leave alt
        assertEquals("MAIN", screen()[0])
    }

    @Test fun `linefeed at bottom pushes to scrollback`() {
        for (i in 1..10) write("line$i\r\n")
        assertEquals(5, emu.buffer.scrollbackSize) // 6-row screen, 5 lines pushed out
    }

    @Test fun `wide characters occupy two cells`() {
        write("中")
        val line = emu.buffer.screen()[0]
        assertEquals('中', line.chars[0])
        assertEquals(' ', line.chars[1])
        assertTrue(TextStyle.isWideCont(line.styles[1])) // wide continuation marker
        assertEquals(2, WcWidth.width('中'.code))
    }

    @Test fun `utf8 multibyte decodes incrementally`() {
        val bytes = "héllo".toByteArray(Charsets.UTF_8)
        emu.write(bytes.copyOfRange(0, 2)) // 'h' + first byte of é
        emu.write(bytes.copyOfRange(2, bytes.size))
        assertTrue(screen()[0].startsWith("héllo"))
    }

    @Test fun `DSR cursor position report`() {
        write("\u001b[3;4H")
        write("\u001b[6n")
        assertEquals("\u001b[3;4R", replies.toString())
    }

    @Test fun `device attributes response`() {
        write("\u001b[c")
        assertTrue(replies.toString().startsWith("\u001b[?"))
    }

    @Test fun `OSC title change`() {
        write("\u001b]2;noxs@android: ~\u0007")
        assertEquals("noxs@android: ~", lastTitle)
    }

    @Test fun `bell counted`() {
        write("\u0007")
        assertEquals(1, bellCount)
    }

    @Test fun `insert and delete chars`() {
        write("ABCDEF")
        write("\u001b[1;1H\u001b[2@") // ICH 2 at col 1
        assertEquals("  ABCDEF", screen()[0].substring(0, 8))
        write("\u001b[1;1H\u001b[2P") // DCH 2
        assertEquals("ABCDEF", screen()[0].substring(0, 6))
    }

    @Test fun `reverse index at top scrolls down`() {
        write("\u001b[1;1Htop")
        write("\u001bM") // RI
        write("\u001b[1;1Hnew")
        assertEquals("new", screen()[0])
        assertEquals("top", screen()[1])
    }

    @Test fun `bracketed paste wraps payload`() {
        write("\u001b[?2004h")
        val pasted = String(emu.paste("ls\npwd"))
        assertTrue(pasted.startsWith("\u001b[200~"))
        assertTrue(pasted.endsWith("\u001b[201~"))
        assertTrue(pasted.contains("ls\rpwd"))
        write("\u001b[?2004l")
        assertFalse(String(emu.paste("x")).contains("200~"))
    }

    @Test fun `resize preserves content and clamps cursor`() {
        write("hello")
        emu.resize(4, 3)
        assertTrue(screen()[0].startsWith("hell"))
        assertEquals(4, emu.buffer.cols)
    }

    @Test fun `resize across growing widths keeps line-width invariant`() {
        // Regression: v0.1.1 crashed in onSizeChanged ->
        // ArrayIndexOutOfBoundsException(src.length=80, dst.length=135, length=90)
        // because resize() created newly added rows at the STALE width, so a
        // later column resize copied past the end of a narrow line.
        val buf = TerminalBuffer(80, 5, 100)
        buf.reset()
        buf.resize(90, 8)   // cols AND rows grow: previously added rows stayed 80 wide
        buf.resize(135, 10) // previously crashed: arraycopy(80-wide src, 135 dst, len 90)
        assertEquals(135, buf.cols)
        assertEquals(10, buf.rows)
        for (line in buf.mainLines + buf.altLines) {
            assertEquals(135, line.chars.size)
            assertEquals(135, line.styles.size)
        }
        buf.resize(40, 4) // shrink must stay consistent too
        for (line in buf.mainLines + buf.altLines) {
            assertEquals(40, line.chars.size)
            assertEquals(40, line.styles.size)
        }
        assertEquals(40, buf.cols)
        assertEquals(4, buf.rows)
    }

    @Test fun `resize rows only never breaks invariant`() {
        val buf = TerminalBuffer(24, 4, 50)
        buf.reset()
        buf.resize(24, 12)
        buf.resize(24, 3)
        buf.resize(24, 9)
        for (line in buf.mainLines + buf.altLines) {
            assertEquals(24, line.chars.size)
            assertEquals(24, line.styles.size)
        }
        assertEquals(9, buf.rows)
    }

    @Test fun `tab advances to next stop`() {
        write("a\tb")
        val row = screen()[0]
        assertEquals('a', row[0])
        assertEquals('b', row[8])
    }

    @Test fun `DEC special graphics map box drawing`() {
        write("\u001b(0") // designate G0 as DEC special
        write("qqq")
        val row = screen()[0]
        assertEquals('─', row[0])
    }

    @Test fun `KeyHandler ctrl-c yields ETX`() {
        assertTrue(KeyHandler.map(31 /* C */, KeyHandler.MOD_CTRL, false).contentEquals(byteArrayOf(3)))
    }

    @Test fun `KeyHandler arrows with and without app mode`() {
        assertTrue(KeyHandler.map(19, 0, false).contentEquals("\u001b[A".toByteArray()))
        assertTrue(KeyHandler.map(19, 0, true).contentEquals("\u001bOA".toByteArray()))
        assertTrue(KeyHandler.map(22, KeyHandler.MOD_CTRL or KeyHandler.MOD_ALT, false).contentEquals("\u001b[1;5C".toByteArray()))
    }

    @Test fun `KeyHandler plain letters are not terminal keys`() {
        assertNull(KeyHandler.map(29 /* A */, 0, false))
    }

    @Test fun `style pack round trip`() {
        val s = TextStyle.encode(196, 17, TextStyle.FLAG_BOLD or TextStyle.FLAG_UNDERLINE, 1)
        assertEquals(196, TextStyle.fg(s))
        assertEquals(17, TextStyle.bg(s))
        assertEquals(TextStyle.FLAG_BOLD or TextStyle.FLAG_UNDERLINE, TextStyle.flags(s))
        assertEquals(1, TextStyle.charset(s))
    }
}
