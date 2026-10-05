/*
 * Noxs terminal-emulator — original implementation.
 * Screen buffer: main + alternate screen, scrollback ring for the main screen.
 */
package com.crossberry.noxs.terminal.emulator

class TerminalBuffer(initialCols: Int, initialRows: Int, scrollbackMax: Int) {

    var scrollbackMax: Int = scrollbackMax.coerceAtLeast(0)
        private set
    private var nextLineIdentity = 1L

    inner class Line(initialCols: Int) {
        /** Stable while this row moves between screen and scrollback. */
        val identity: Long = nextLineIdentity++
        var chars: CharArray = CharArray(initialCols) { ' ' }
        var styles: LongArray = LongArray(initialCols) { TextStyle.defaultStyle() }
        var hyperlinks: Array<String?> = arrayOfNulls(initialCols)
        var lineWrap = false // soft-wrapped continuation (renderer joins for copy)

        fun clear(from: Int = 0, to: Int = chars.size, style: Long = TextStyle.defaultStyle()) {
            for (i in from until minOf(to, chars.size)) {
                chars[i] = ' '
                styles[i] = style
                hyperlinks[i] = null
            }
            if (from == 0 && to >= chars.size) lineWrap = false
        }

        fun copyFrom(src: Line) {
            val n = minOf(chars.size, src.chars.size)
            System.arraycopy(src.chars, 0, chars, 0, n)
            System.arraycopy(src.styles, 0, styles, 0, n)
            System.arraycopy(src.hyperlinks, 0, hyperlinks, 0, minOf(n, src.hyperlinks.size))
            lineWrap = src.lineWrap
            for (i in n until chars.size) {
                chars[i] = ' '
                styles[i] = TextStyle.defaultStyle()
                hyperlinks[i] = null
            }
        }

        fun text(): String {
            var end = chars.size
            while (end > 0 && chars[end - 1] == ' ') end--
            return String(chars, 0, end)
        }
    }

    var cols: Int = initialCols
        private set
    var rows: Int = initialRows
        private set

    val mainLines = ArrayList<Line>(initialRows)
    val altLines = ArrayList<Line>(initialRows)
    var usingAlt = false

    // Fixed-capacity ring: visible-row lookup stays O(1), even with thousands
    // of history lines. Rows retain their identity as they enter scrollback.
    private var scrollbackRing: Array<Line?> = arrayOfNulls(this.scrollbackMax)
    private var scrollbackHead = 0
    private var scrollbackCount = 0
    private var scrollbackSlotsByIdentity = HashMap<Long, Int>(this.scrollbackMax.coerceAtLeast(16))
    val scrollbackSize: Int get() = scrollbackCount

    /** Monotonic count used by the viewport to hold its position during output. */
    var scrollbackSerial: Long = 0L
        private set

    var cursorCol = 0
    var cursorRow = 0
    var cursorVisible = true

    var topMargin = 0
    var bottomMargin = initialRows - 1

    // Saved cursor (DECSC/DECRC) per screen
    var savedCol = 0
    var savedRow = 0
    var savedStyle: Long = TextStyle.defaultStyle()
    var savedOriginMode = false
    var savedAutoWrap = true
    var savedCharset = 0

    var currentStyle: Long = TextStyle.defaultStyle()
    var autoWrap = true
    var originMode = false
    var insertMode = false

    // Charset state: 0=BASIC Latin, 1=DEC special graphics
    var charsetG0 = 0
    var charsetG1 = 0
    var charsetActive = 0

    fun screen(): ArrayList<Line> = if (usingAlt) altLines else mainLines

    fun blankLine(): Line = Line(cols)

    fun currentLine(): Line = screen()[cursorRow]

    fun setAltScreen(on: Boolean) {
        if (on == usingAlt) return
        // Ensure the target screen has rows (defensive; reset() pre-fills both).
        val target = if (on) altLines else mainLines
        while (target.size < rows) target.add(Line(cols))
        usingAlt = on
        cursorCol = 0
        cursorRow = 0
        topMargin = 0
        bottomMargin = rows - 1
    }

    fun pushToScrollback(line: Line) {
        if (usingAlt || scrollbackMax == 0) return
        scrollbackSerial++
        if (scrollbackCount < scrollbackMax) {
            val index = (scrollbackHead + scrollbackCount) % scrollbackMax
            scrollbackRing[index] = line
            scrollbackSlotsByIdentity[line.identity] = index
            scrollbackCount++
        } else {
            scrollbackRing[scrollbackHead]?.let { scrollbackSlotsByIdentity.remove(it.identity) }
            scrollbackRing[scrollbackHead] = line
            scrollbackSlotsByIdentity[line.identity] = scrollbackHead
            scrollbackHead = (scrollbackHead + 1) % scrollbackMax
        }
    }

    /** Index 0 is the newest row, preserving the historic API contract. */
    fun scrollbackLine(indexFromBottom: Int): Line? {
        if (indexFromBottom < 0 || indexFromBottom >= scrollbackCount) return null
        return scrollbackLineFromOldest(scrollbackCount - indexFromBottom - 1)
    }

    fun scrollbackLineFromOldest(index: Int): Line? {
        if (index < 0 || index >= scrollbackCount || scrollbackMax == 0) return null
        return scrollbackRing[(scrollbackHead + index) % scrollbackMax]
    }

    fun clearScrollback() {
        java.util.Arrays.fill(scrollbackRing, null)
        scrollbackSlotsByIdentity.clear()
        scrollbackHead = 0
        scrollbackCount = 0
    }

    /**
     * Grows or shrinks the history ring in place. The screen, cursor, VT state
     * and the child process are untouched; shrinking keeps the NEWEST retained
     * rows and drops only the oldest. Growing preserves every row and order.
     * No-op when the requested capacity equals the current one.
     */
    fun resizeScrollback(newMax: Int) {
        val target = newMax.coerceAtLeast(0)
        if (target == scrollbackMax) return
        val oldCount = scrollbackCount
        val keep = if (target == 0) 0 else minOf(oldCount, target)
        val fresh = arrayOfNulls<Line>(target)
        val slots = HashMap<Long, Int>(target.coerceAtLeast(16))
        if (keep > 0) {
            // Copy the newest [keep] rows, oldest-to-newest, into slots 0..keep-1.
            val firstKeptOldest = oldCount - keep
            for (i in 0 until keep) {
                val line = scrollbackLineFromOldest(firstKeptOldest + i) ?: continue
                fresh[i] = line
                slots[line.identity] = i
            }
        }
        scrollbackRing = fresh
        scrollbackSlotsByIdentity = slots
        scrollbackHead = 0
        scrollbackCount = keep
        scrollbackMax = target
    }

    /** Number of document rows currently addressable by viewport/selection. */
    fun documentRowCount(): Int =
        (if (usingAlt) 0 else scrollbackCount) + minOf(rows, screen().size)

    /** Rows are ordered oldest-to-newest, followed by the active screen. */
    fun documentLineAt(index: Int): Line? {
        if (index < 0) return null
        val screen = screen()
        val activeRows = minOf(rows, screen.size)
        if (usingAlt) return screen.getOrNull(index.takeIf { it < activeRows } ?: return null)
        return if (index < scrollbackCount) {
            scrollbackLineFromOldest(index)
        } else {
            screen.getOrNull((index - scrollbackCount).takeIf { it < activeRows } ?: return null)
        }
    }

    fun documentRowOf(identity: Long): Int? {
        val screen = screen()
        val activeRows = minOf(rows, screen.size)
        val screenRow = screen.indexOfFirst { it.identity == identity }
        if (screenRow >= 0 && screenRow < activeRows) {
            return if (usingAlt) screenRow else scrollbackCount + screenRow
        }
        if (usingAlt || scrollbackMax == 0) return null
        val physicalIndex = scrollbackSlotsByIdentity[identity] ?: return null
        val logicalIndex = (physicalIndex - scrollbackHead + scrollbackMax) % scrollbackMax
        return logicalIndex.takeIf { it < scrollbackCount }
    }

    fun maxScrollOffset(viewportRows: Int): Int {
        val activeRows = minOf(rows, screen().size)
        val visibleRows = viewportRows.coerceAtLeast(1).coerceAtMost(activeRows.coerceAtLeast(1))
        return (documentRowCount() - visibleRows).coerceAtLeast(0)
    }

    fun viewportStartDocumentRow(scrollOffsetFromBottom: Int, viewportRows: Int): Int {
        val activeRows = minOf(rows, screen().size)
        val visibleRows = viewportRows.coerceAtLeast(1).coerceAtMost(activeRows.coerceAtLeast(1))
        return (documentRowCount() - visibleRows - scrollOffsetFromBottom.coerceIn(0, maxScrollOffset(visibleRows)))
            .coerceAtLeast(0)
    }

    /**
     * Scroll region up by [n] lines: lines at top leave (main screen: to
     * scrollback when the region starts at screen top), blanks enter at bottom.
     */
    fun scrollUp(n: Int) {
        val scr = screen()
        repeat(minOf(n, bottomMargin - topMargin + 1)) {
            val first = scr.removeAt(topMargin)
            if (!usingAlt && topMargin == 0) pushToScrollback(first)
            scr.add(bottomMargin, blankLine())
        }
    }

    fun scrollDown(n: Int) {
        val scr = screen()
        repeat(minOf(n, bottomMargin - topMargin + 1)) {
            scr.removeAt(bottomMargin)
            scr.add(topMargin, blankLine())
        }
    }

    fun insertLines(n: Int) {
        val scr = screen()
        repeat(minOf(n, bottomMargin - cursorRow + 1)) {
            scr.removeAt(bottomMargin)
            scr.add(cursorRow, blankLine())
        }
    }

    fun deleteLines(n: Int) {
        val scr = screen()
        repeat(minOf(n, bottomMargin - cursorRow + 1)) {
            scr.removeAt(cursorRow)
            scr.add(bottomMargin, blankLine())
        }
    }

    /** Grows/shrinks the screen. No reflow (documented limitation). */
    fun resize(newCols: Int, newRows: Int) {
        val targetCols = newCols.coerceAtLeast(1)
        val targetRows = newRows.coerceAtLeast(1)
        if (targetCols == cols && targetRows == rows) return
        if (targetCols != cols) {
            fun resizeLine(line: Line) {
                // Bound each copy by the line's ACTUAL array lengths, never
                // the nominal cols: mixed widths must never crash here.
                if (line.chars.size != targetCols) {
                    val newChars = CharArray(targetCols) { ' ' }
                    System.arraycopy(line.chars, 0, newChars, 0, minOf(line.chars.size, targetCols))
                    line.chars = newChars
                }
                if (line.styles.size != targetCols) {
                    val newStyles = LongArray(targetCols) { TextStyle.defaultStyle() }
                    System.arraycopy(line.styles, 0, newStyles, 0, minOf(line.styles.size, targetCols))
                    line.styles = newStyles
                }
                if (line.hyperlinks.size != targetCols) {
                    val newLinks = arrayOfNulls<String>(targetCols)
                    System.arraycopy(line.hyperlinks, 0, newLinks, 0, minOf(line.hyperlinks.size, targetCols))
                    line.hyperlinks = newLinks
                }
            }
            for (list in listOf(mainLines, altLines)) list.forEach(::resizeLine)
            // History rows participate in selection and rendering too; keep
            // every retained row at the same physical width after a resize.
            scrollbackRing.forEach { it?.let(::resizeLine) }
            // Update cols BEFORE growing rows so newly added lines are created
            // at the NEW width (previously they used the stale width, breaking
            // the "every line is cols wide" invariant on the next resize).
            cols = targetCols
        }
        for (list in listOf(mainLines, altLines)) {
            while (list.size < targetRows) list.add(Line(cols))
            while (list.size > targetRows) list.removeAt(list.size - 1)
        }
        rows = targetRows
        cursorCol = cursorCol.coerceIn(0, cols - 1)
        cursorRow = cursorRow.coerceIn(0, rows - 1)
        topMargin = 0
        bottomMargin = rows - 1
    }

    fun reset() {
        mainLines.clear(); altLines.clear()
        for (i in 0 until rows) { mainLines.add(Line(cols)); altLines.add(Line(cols)) }
        clearScrollback()
        cursorCol = 0; cursorRow = 0
        topMargin = 0; bottomMargin = rows - 1
        currentStyle = TextStyle.defaultStyle()
        autoWrap = true; originMode = false; insertMode = false
        usingAlt = false
        cursorVisible = true
        charsetG0 = 0; charsetG1 = 0; charsetActive = 0
        saveCursor()
    }

    fun saveCursor() {
        savedCol = cursorCol; savedRow = cursorRow
        savedStyle = currentStyle
        savedOriginMode = originMode
        savedAutoWrap = autoWrap
        savedCharset = charsetActive
    }

    fun restoreCursor() {
        cursorCol = savedCol.coerceIn(0, cols - 1)
        cursorRow = savedRow.coerceIn(0, rows - 1)
        currentStyle = savedStyle
        originMode = savedOriginMode
        autoWrap = savedAutoWrap
        charsetActive = savedCharset
    }

    /** DEC special graphics mapping for box drawing (line-drawing in top/htop). */
    fun mapCharset(c: Char): Char {
        if (charsetActive == 0) return c
        return when (c) {
            'j' -> '┘'; 'k' -> '┐'; 'l' -> '┌'; 'm' -> '└'; 'n' -> '┼'
            'q' -> '─'; 't' -> '├'; 'u' -> '┤'; 'v' -> '┴'; 'w' -> '┬'
            'x' -> '│'; 'a' -> '▒'; '`' -> '◆'; 'f' -> '°'; 'g' -> '±'
            '~' -> '·'; 'o' -> '⎺'; 'p' -> '⎻'; 'r' -> '⎼'; 's' -> '⎽'
            '0' -> '▮'; else -> c
        }
    }
}
