package com.crossberry.noxs.terminal.emulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalViewportSelectionTest {
    private fun emulator(cols: Int = 24, rows: Int = 4, history: Int = 5000) =
        TerminalEmulator(object : TerminalEmulator.Client {
            override fun onScreenChanged() {}
            override fun onTitleChanged(title: String) {}
            override fun onBell() {}
            override fun onResize(cols: Int, rows: Int) {}
            override fun onReply(data: ByteArray) {}
        }, cols, rows, history)

    private fun writeLines(
        emu: TerminalEmulator,
        count: Int,
        prefix: String = "row",
        newLineBeforeFirst: Boolean = false
    ) {
        val output = buildString(count * 12) {
            if (newLineBeforeFirst && count > 0) append("\r\n")
            repeat(count) { index ->
                append(prefix).append(index)
                if (index < count - 1) append("\r\n")
            }
        }
        emu.write(output.toByteArray(Charsets.UTF_8))
    }

    private fun rowText(emu: TerminalEmulator, documentRow: Int): String? =
        emu.buffer.documentLineAt(documentRow)?.text()

    @Test fun `normal swipe rows move toward history and downward returns to live`() {
        val emu = emulator(rows = 3)
        writeLines(emu, 6)
        val viewport = TerminalViewportState()
        viewport.attach(emu.buffer, 3)

        assertEquals(0, viewport.scrollOffsetFromBottom)
        assertTrue(rowText(emu, viewport.viewportStartRow(emu.buffer, 3) + 2)!!.startsWith("row5"))

        viewport.scrollByRows(2, emu.buffer, 3)
        assertEquals(2, viewport.scrollOffsetFromBottom)
        assertTrue(rowText(emu, viewport.viewportStartRow(emu.buffer, 3))!!.startsWith("row1"))

        viewport.scrollByRows(-2, emu.buffer, 3)
        assertEquals(0, viewport.scrollOffsetFromBottom)
        assertTrue(rowText(emu, viewport.viewportStartRow(emu.buffer, 3) + 2)!!.startsWith("row5"))
    }

    @Test fun `live view follows output but detached view preserves its history anchor`() {
        val emu = emulator(rows = 3)
        writeLines(emu, 8)
        val viewport = TerminalViewportState()
        viewport.attach(emu.buffer, 3)

        val bottomRow = viewport.viewportStartRow(emu.buffer, 3) + 2
        assertTrue(rowText(emu, bottomRow)!!.startsWith("row7"))
        writeLines(emu, 1, "live", newLineBeforeFirst = true)
        viewport.synchronize(emu.buffer, 3)
        assertEquals(0, viewport.scrollOffsetFromBottom)
        val liveRow = viewport.viewportStartRow(emu.buffer, 3) + 2
        assertTrue(rowText(emu, liveRow)!!.startsWith("live0"))

        viewport.scrollByRows(2, emu.buffer, 3)
        val anchoredIdentity = emu.buffer.documentLineAt(viewport.viewportStartRow(emu.buffer, 3))!!.identity
        writeLines(emu, 5, "more", newLineBeforeFirst = true)
        viewport.synchronize(emu.buffer, 3)
        val preservedIdentity = emu.buffer.documentLineAt(viewport.viewportStartRow(emu.buffer, 3))!!.identity
        assertEquals(anchoredIdentity, preservedIdentity)

        viewport.scrollToBottom(emu.buffer, 3)
        assertEquals(0, viewport.scrollOffsetFromBottom)
        val latest = viewport.viewportStartRow(emu.buffer, 3) + 2
        assertTrue(rowText(emu, latest)!!.startsWith("more4"))
    }

    @Test fun `selection copies exact spaces and hard line breaks`() {
        val emu = emulator(cols = 20, rows = 4)
        emu.write("first\r\nsecond\r\nthird".toByteArray())
        val buffer = emu.buffer
        val first = buffer.documentLineAt(buffer.scrollbackSize)!!
        val third = buffer.documentLineAt(buffer.scrollbackSize + 2)!!
        val selection = TerminalSelection(
            TerminalSelectionPoint(first.identity, 1),
            TerminalSelectionPoint(third.identity, 2)
        )
        assertEquals("irst\nsecond\nthi", selection.copyText(buffer))

        val tabEmu = emulator(cols = 12, rows = 2)
        tabEmu.write("\tA".toByteArray())
        val tabLine = tabEmu.buffer.documentLineAt(0)!!
        val tabSelection = TerminalSelection(
            TerminalSelectionPoint(tabLine.identity, 0),
            TerminalSelectionPoint(tabLine.identity, 8)
        )
        assertEquals("        A", tabSelection.copyText(tabEmu.buffer))

        val spacesEmu = emulator(cols = 8, rows = 2)
        spacesEmu.write("a  b".toByteArray())
        val spacesLine = spacesEmu.buffer.documentLineAt(0)!!
        val spacesSelection = TerminalSelection(
            TerminalSelectionPoint(spacesLine.identity, 0),
            TerminalSelectionPoint(spacesLine.identity, 3)
        )
        assertEquals("a  b", spacesSelection.copyText(spacesEmu.buffer))
    }

    @Test fun `soft-wrapped rows copy continuously without an inserted newline`() {
        val emu = emulator(cols = 5, rows = 3)
        emu.write("abcdefgh".toByteArray())
        val first = emu.buffer.documentLineAt(0)!!
        val second = emu.buffer.documentLineAt(1)!!
        assertTrue(first.lineWrap)
        val selection = TerminalSelection(
            TerminalSelectionPoint(first.identity, 3),
            TerminalSelectionPoint(second.identity, 2)
        )
        assertEquals("defgh", selection.copyText(emu.buffer))
    }

    @Test fun `selection remains anchored while the viewport scrolls`() {
        val emu = emulator(rows = 4)
        writeLines(emu, 15)
        val buffer = emu.buffer
        val line = buffer.documentLineAt(2)!!
        val selection = TerminalSelection(
            TerminalSelectionPoint(line.identity, 0),
            TerminalSelectionPoint(line.identity, 4)
        )
        val before = selection.copyText(buffer)
        val cursorRow = buffer.cursorRow
        val cursorCol = buffer.cursorCol
        val viewport = TerminalViewportState()
        viewport.attach(buffer, 4)
        viewport.scrollByRows(8, buffer, 4)
        assertEquals(before, selection.copyText(buffer))
        assertEquals(cursorRow, buffer.cursorRow)
        assertEquals(cursorCol, buffer.cursorCol)
    }

    @Test fun `thousands of retained rows stay addressable and selectable`() {
        val emu = emulator(cols = 32, rows = 5, history = 4000)
        writeLines(emu, 3500)
        val buffer = emu.buffer
        assertTrue(buffer.scrollbackSize > 3000)
        assertTrue(buffer.scrollbackSize <= 4000)
        val oldest = buffer.scrollbackLineFromOldest(0)!!
        assertEquals(0, buffer.documentRowOf(oldest.identity))

        val all = buffer.selectAllText()
        assertNotNull(all)
        val copied = all!!.copyText(buffer)!!
        assertTrue(copied.startsWith("row0"))
        assertTrue(copied.contains("row1749"))
        assertTrue(copied.contains("row3499"))
        assertTrue(copied.lineSequence().count() > 3400)
    }

    @Test fun `edge auto-scroll accelerates toward the edge and repeats while held`() {
        val auto = SelectionAutoScroller(edgeSizePx = 48f)
        val slowTop = auto.rowsPerSecond(pointerY = 47f, viewportHeight = 600f)
        val fastTop = auto.rowsPerSecond(pointerY = 0f, viewportHeight = 600f)
        val fastBottom = auto.rowsPerSecond(pointerY = 600f, viewportHeight = 600f)
        assertTrue(slowTop > 0f)
        assertTrue(fastTop > slowTop)
        assertTrue(fastBottom < 0f)

        var moved = 0
        repeat(60) { moved += auto.step(pointerY = 0f, viewportHeight = 600f, elapsedMs = 16L) }
        assertTrue("held edge drag should keep scrolling", moved > 20)
        auto.reset()
        assertEquals(0, auto.step(pointerY = 300f, viewportHeight = 600f, elapsedMs = 16L))
    }

    @Test fun `scrolling through a full ring clamps safely after old rows expire`() {
        val emu = emulator(rows = 3, history = 100)
        writeLines(emu, 400)
        val viewport = TerminalViewportState()
        viewport.attach(emu.buffer, 3)
        viewport.scrollByRows(Int.MAX_VALUE, emu.buffer, 3)
        assertEquals(100, viewport.scrollOffsetFromBottom)
        assertTrue(rowText(emu, viewport.viewportStartRow(emu.buffer, 3))!!.startsWith("row297"))
        writeLines(emu, 20, "next", newLineBeforeFirst = true)
        viewport.synchronize(emu.buffer, 3)
        assertEquals(100, viewport.scrollOffsetFromBottom)
        assertNotNull(emu.buffer.documentLineAt(viewport.viewportStartRow(emu.buffer, 3)))
    }

    @Test fun `selection handle owns touch stream before scroll and long-press recognition`() {
        val policy = TerminalGesturePolicy()
        policy.onDown(onSelectionHandle = true)
        assertTrue(policy.isDraggingHandle)
        assertFalse(policy.onLongPress())
        assertFalse(policy.isSelecting)
        policy.finish()

        policy.onDown(onSelectionHandle = false)
        assertEquals(TerminalGesturePolicy.Owner.SCROLL_OR_TAP, policy.owner)
        assertTrue(policy.onLongPress())
        assertTrue(policy.isSelecting)
        policy.finish()
        assertEquals(TerminalGesturePolicy.Owner.IDLE, policy.owner)

        assertTrue(policy.shouldStartVerticalScroll(deltaX = 2f, deltaY = 20f, touchSlop = 10f))
        assertFalse(policy.shouldStartVerticalScroll(deltaX = 20f, deltaY = 2f, touchSlop = 10f))
        assertEquals(5f, policy.verticalScrollDelta(previousY = 100f, currentY = 95f), 0f)
        assertEquals(-5f, policy.verticalScrollDelta(previousY = 95f, currentY = 100f), 0f)
    }

    @Test fun `scrollback and wrapped rows keep valid widths and identities after resize`() {
        val emu = emulator(cols = 8, rows = 3)
        emu.write("abcdefghijk\r\nlast\r\nnext\r\nmore".toByteArray())
        val first = emu.buffer.documentLineAt(0)!!
        val identity = first.identity
        assertTrue(first.lineWrap)

        emu.buffer.resize(6, 3)
        val resized = emu.buffer.documentLineAt(0)!!
        assertEquals(identity, resized.identity)
        assertEquals(6, resized.chars.size)
        assertEquals(6, resized.styles.size)
        assertEquals(6, resized.hyperlinks.size)
    }

    @Test fun `hyperlink lookup follows stable row identity into scrollback`() {
        val emu = emulator(cols = 20, rows = 2)
        emu.write("\u001b]8;;https://crossberry.vercel.app\u001b\\site\u001b]8;;\u001b\\\r\nnext\r\nthird".toByteArray())
        val historicalLine = emu.buffer.documentLineAt(0)!!
        assertEquals(
            "https://crossberry.vercel.app",
            emu.hyperlinkAtDocumentPosition(historicalLine.identity, 1)
        )
    }

    @Test fun `alternate screen returns to the prior main history position`() {
        val emu = emulator(rows = 3)
        writeLines(emu, 12)
        val viewport = TerminalViewportState()
        viewport.attach(emu.buffer, 3)
        viewport.scrollByRows(2, emu.buffer, 3)
        val anchoredIdentity = emu.buffer.documentLineAt(viewport.viewportStartRow(emu.buffer, 3))!!.identity

        emu.write("\u001b[?1049hALT".toByteArray())
        viewport.synchronize(emu.buffer, 3)
        assertEquals(0, viewport.scrollOffsetFromBottom)
        emu.write("\u001b[?1049l".toByteArray())
        viewport.synchronize(emu.buffer, 3)

        assertEquals(2, viewport.scrollOffsetFromBottom)
        assertEquals(
            anchoredIdentity,
            emu.buffer.documentLineAt(viewport.viewportStartRow(emu.buffer, 3))!!.identity
        )
    }
}
