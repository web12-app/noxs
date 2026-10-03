/*
 * Noxs terminal-emulator — original implementation.
 * Screen buffer: main + alternate screen, scrollback ring for the main screen.
 */
package com.noxs.linux.terminal.emulator

class TerminalBuffer(initialCols: Int, initialRows: Int, var scrollbackMax: Int) {

    inner class Line(initialCols: Int) {
        var chars: CharArray = CharArray(initialCols) { ' ' }
        var styles: LongArray = LongArray(initialCols) { TextStyle.defaultStyle() }
        var lineWrap = false // soft-wrapped continuation (renderer joins for copy)

        fun clear(from: Int = 0, to: Int = chars.size, style: Long = TextStyle.defaultStyle()) {
            for (i in from until minOf(to, chars.size)) {
                chars[i] = ' '
                styles[i] = style
            }
        }

        fun copyFrom(src: Line) {
            val n = minOf(chars.size, src.chars.size)
            System.arraycopy(src.chars, 0, chars, 0, n)
            System.arraycopy(src.styles, 0, styles, 0, n)
            lineWrap = src.lineWrap
            for (i in n until chars.size) { chars[i] = ' '; styles[i] = TextStyle.defaultStyle() }
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

    // Scrollback ring
    private val scrollback = ArrayDeque<Line>()
    val scrollbackSize: Int get() = scrollback.size

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
        if (usingAlt) return
        scrollback.addLast(line)
        while (scrollback.size > scrollbackMax) scrollback.removeFirst()
    }

    fun scrollbackLine(indexFromBottom: Int): Line? {
        val size = scrollback.size
        if (indexFromBottom < 0 || indexFromBottom >= size) return null
        return scrollback.elementAt(size - 1 - indexFromBottom)
    }

    fun clearScrollback() = scrollback.clear()

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
        if (newCols == cols && newRows == rows) return
        if (newCols != cols) {
            for (list in listOf(mainLines, altLines)) {
                for (line in list) {
                    val newChars = CharArray(newCols) { ' ' }
                    System.arraycopy(line.chars, 0, newChars, 0, minOf(cols, newCols))
                    line.chars = newChars
                    val newStyles = LongArray(newCols) { TextStyle.defaultStyle() }
                    System.arraycopy(line.styles, 0, newStyles, 0, minOf(cols, newCols))
                    line.styles = newStyles
                }
            }
        }
        for (list in listOf(mainLines, altLines)) {
            while (list.size < newRows) list.add(Line(cols))
            while (list.size > newRows) list.removeAt(list.size - 1)
        }
        cols = newCols
        rows = newRows
        cursorCol = cursorCol.coerceIn(0, cols - 1)
        cursorRow = cursorRow.coerceIn(0, rows - 1)
        topMargin = 0
        bottomMargin = rows - 1
    }

    fun reset() {
        mainLines.clear(); altLines.clear()
        for (i in 0 until rows) { mainLines.add(Line(cols)); altLines.add(Line(cols)) }
        scrollback.clear()
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
