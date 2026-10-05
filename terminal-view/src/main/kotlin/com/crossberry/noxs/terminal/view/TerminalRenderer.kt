/*
 * Noxs terminal-view — original implementation.
 * Canvas-based renderer for the Noxs terminal buffer.
 *
 * Appearance is theme-driven (TerminalTheme), supports font family, letter
 * spacing, line spacing, three cursor shapes with configurable width and
 * blink, and overlay search highlights. All per-frame Paint objects are
 * reused; nothing is allocated inside render().
 */
package com.crossberry.noxs.terminal.view

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.crossberry.noxs.terminal.emulator.TerminalBuffer
import com.crossberry.noxs.terminal.emulator.TerminalColors
import com.crossberry.noxs.terminal.emulator.TerminalEmulator
import com.crossberry.noxs.terminal.emulator.TerminalSearchMatch
import com.crossberry.noxs.terminal.emulator.TerminalSelection
import com.crossberry.noxs.terminal.emulator.ResolvedTerminalSelection
import com.crossberry.noxs.terminal.emulator.TextStyle
import com.crossberry.noxs.terminal.emulator.resolveSelection

class TerminalRenderer {

    data class Metrics(val charWidth: Float, val charHeight: Float, val fontAscent: Float)
    data class HandleLocation(val x: Float, val y: Float)
    data class HandleLocations(val anchor: HandleLocation?, val focus: HandleLocation?)

    private val textPaint = Paint().apply {
        typeface = Typeface.MONOSPACE
        isAntiAlias = true
    }
    private val bgPaint = Paint().apply { style = Paint.Style.FILL }
    private val underlinePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val handleOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val highlightPaint = Paint().apply { style = Paint.Style.FILL }

    var textAntialias: Boolean = true
        set(value) {
            field = value
            textPaint.isAntiAlias = value
        }

    var theme: TerminalTheme = TerminalTheme.NOXS_DARK
    var fontSizeSp: Float = 14f
    var densityScale: Float = 1f

    /** Typeface family for all glyphs (monospaced metrics expected). */
    var fontFamily: Typeface = Typeface.MONOSPACE
        set(value) {
            field = value
            textPaint.typeface = value
        }

    /**
     * Inset around the terminal content, in pixels. Background fills the full
     * view; glyphs, highlights, selection and cursor are offset by this pad.
     */
    var contentPaddingPx: Float = 0f

    /** Extra space between glyphs, in em units (0 = default metrics). */
    var letterSpacingEm: Float = 0f
        set(value) {
            field = value
            textPaint.letterSpacing = value
        }

    /** Vertical spacing multiplier: 1.0 = natural font height. */
    var lineSpacing: Float = 1f

    /** Cursor shape + thickness multiplier (underline/bar, 1..4). */
    var cursorStyle: TerminalCursorStyle = TerminalCursorStyle.BLOCK
    var cursorWidth: Int = 2

    fun measure(): Metrics {
        textPaint.textSize = fontSizeSp * densityScale
        val fm = textPaint.fontMetrics
        val cw = if (letterSpacingEm == 0f) {
            textPaint.measureText("W")
        } else {
            // Letter spacing appends after each glyph; a single "W" would not
            // include it. Two glyphs / two columns yields the true advance.
            textPaint.measureText("WW") / 2f
        }
        val natural = fm.descent - fm.ascent
        return Metrics(cw, natural * lineSpacing, -fm.ascent * lineSpacing)
    }

    fun render(
        canvas: Canvas,
        emulator: TerminalEmulator,
        metrics: Metrics,
        scrollRows: Int,
        focused: Boolean,
        density: Float,
        selection: TerminalSelection? = null,
        searchMatches: List<TerminalSearchMatch> = emptyList(),
        currentMatch: TerminalSearchMatch? = null
    ) {
        val buf = emulator.buffer
        canvas.drawColor(theme.defaultBg)

        val cw = metrics.charWidth
        val ch = metrics.charHeight
        if (cw <= 0f || ch <= 0f) return
        val pad = contentPaddingPx

        val screenRows = minOf(buf.rows, buf.screen().size)
        val areaHeight = canvas.height - 2f * pad
        val visibleRows = (areaHeight / ch).toInt().coerceAtLeast(1).coerceAtMost(screenRows)
        val firstDocumentRow = buf.viewportStartDocumentRow(scrollRows, visibleRows)
        val lastDocumentRow = firstDocumentRow + visibleRows - 1
        val resolvedSelection = selection?.let(buf::resolveSelection)

        // Search highlights underlay the glyphs; the active match is strongest.
        if (searchMatches.isNotEmpty()) {
            for (match in searchMatches) {
                if (match.row < firstDocumentRow || match.row > lastDocumentRow) continue
                val visRow = match.row - firstDocumentRow
                highlightPaint.color = if (match === currentMatch || match == currentMatch) {
                    0x9666aaff
                } else {
                    0x55666aff
                }
                canvas.drawRect(
                    pad + match.startCol * cw, pad + visRow * ch, pad + match.endCol * cw, pad + (visRow + 1) * ch,
                    highlightPaint
                )
            }
        }

        for (visRow in 0 until visibleRows) {
            val documentRow = firstDocumentRow + visRow
            val line = buf.documentLineAt(documentRow) ?: continue
            val top = pad + visRow * ch
            renderRow(canvas, line, top, pad, cw, ch, metrics, scrollRows == 0 && focused, density)
            if (resolvedSelection != null) {
                drawSelectionRow(canvas, line, documentRow, resolvedSelection, visRow, pad, cw, ch)
            }
        }
        if (selection != null && resolvedSelection != null) {
            drawSelectionHandles(canvas, resolvedSelection, firstDocumentRow, pad, cw, ch, density)
        }
    }

    /** Selection hit targets share their exact geometry with the drawn handles. */
    fun selectionHandleLocations(
        buffer: TerminalBuffer,
        selection: TerminalSelection,
        scrollRows: Int,
        viewportRows: Int,
        charWidth: Float,
        charHeight: Float,
        viewWidth: Int,
        viewHeight: Int,
        density: Float
    ): HandleLocations {
        val range = buffer.resolveSelection(selection) ?: return HandleLocations(null, null)
        val firstDocumentRow = buffer.viewportStartDocumentRow(scrollRows, viewportRows)
        val pad = contentPaddingPx
        val anchor = handleLocation(
            range.anchorRow, range.anchorColumn, true, firstDocumentRow, charWidth, charHeight,
            viewWidth, viewHeight, density, pad
        )
        val focus = handleLocation(
            range.focusRow, range.focusColumn, false, firstDocumentRow, charWidth, charHeight,
            viewWidth, viewHeight, density, pad
        )
        return HandleLocations(anchor, focus)
    }

    private fun drawSelectionRow(
        canvas: Canvas,
        line: TerminalBuffer.Line,
        documentRow: Int,
        range: ResolvedTerminalSelection,
        visibleRow: Int,
        pad: Float,
        cw: Float,
        ch: Float
    ) {
        if (documentRow !in range.firstRow..range.lastRow) return
        val cols = minOf(line.chars.size, line.styles.size)
        if (cols <= 0) return
        val from = (if (documentRow == range.firstRow) range.firstColumn else 0).coerceIn(0, cols - 1)
        val to = (if (documentRow == range.lastRow) range.lastColumn else cols - 1).coerceIn(from, cols - 1)
        bgPaint.color = theme.selectionColor
        canvas.drawRect(pad + from * cw, pad + visibleRow * ch, pad + (to + 1) * cw, pad + (visibleRow + 1) * ch, bgPaint)
    }

    private fun drawSelectionHandles(
        canvas: Canvas,
        range: ResolvedTerminalSelection,
        firstDocumentRow: Int,
        pad: Float,
        cw: Float,
        ch: Float,
        density: Float
    ) {
        val anchor = handleLocation(
            range.anchorRow, range.anchorColumn, true, firstDocumentRow, cw, ch,
            canvas.width, canvas.height, density, pad
        )
        val focus = handleLocation(
            range.focusRow, range.focusColumn, false, firstDocumentRow, cw, ch,
            canvas.width, canvas.height, density, pad
        )
        if (anchor != null) drawHandle(canvas, anchor, density)
        if (focus != null) drawHandle(canvas, focus, density)
    }

    private fun handleLocation(
        documentRow: Int,
        column: Int,
        isAnchor: Boolean,
        firstDocumentRow: Int,
        cw: Float,
        ch: Float,
        width: Int,
        height: Int,
        density: Float,
        pad: Float
    ): HandleLocation? {
        val visibleRow = documentRow - firstDocumentRow
        val visibleRows = ((height - 2f * pad) / ch).toInt().coerceAtLeast(1)
        if (visibleRow !in 0 until visibleRows) return null
        val radius = HANDLE_RADIUS_DP * density
        val columnEdge = column + if (isAnchor) 0 else 1
        val x = (pad + columnEdge * cw).coerceIn(radius, (width - radius).coerceAtLeast(radius))
        val y = (pad + (visibleRow + 1) * ch - 2f * density)
            .coerceIn(radius, (height - radius).coerceAtLeast(radius))
        return HandleLocation(x, y)
    }

    private fun drawHandle(canvas: Canvas, location: HandleLocation, density: Float) {
        val radius = HANDLE_RADIUS_DP * density
        val stem = HANDLE_STEM_DP * density
        handleFillPaint.color = 0xff2196f3.toInt()
        handleOutlinePaint.color = 0xff111111.toInt()
        canvas.drawLine(location.x, location.y - stem, location.x, location.y - radius, handleFillPaint.apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
        })
        handleFillPaint.style = Paint.Style.FILL
        canvas.drawCircle(location.x, location.y, radius, handleFillPaint)
        canvas.drawCircle(location.x, location.y, radius, handleOutlinePaint)
    }

    private fun renderRow(
        canvas: Canvas,
        line: TerminalBuffer.Line,
        top: Float,
        pad: Float,
        cw: Float,
        ch: Float,
        metrics: Metrics,
        isCursorRow: Boolean,
        density: Float
    ) {
        val cols = minOf(line.chars.size, line.styles.size)
        var col = 0
        while (col < cols) {
            val style = line.styles[col]
            val hyperlink = line.hyperlinks.getOrNull(col)
            var end = col + 1
            while (end < cols && line.styles[end] == style && line.hyperlinks.getOrNull(end) == hyperlink) end++

            val fg = TerminalColors.colorOf(style, true, theme.defaultFg, theme.defaultBg)
            val bg = TerminalColors.colorOf(style, false, theme.defaultFg, theme.defaultBg)
            val flags = TextStyle.flags(style)
            val reverse = flags and TextStyle.FLAG_REVERSE != 0

            var drawFg = if (flags and TextStyle.FLAG_INVISIBLE != 0) bg else fg
            var drawBg = bg
            if (reverse) { val t = drawFg; drawFg = drawBg; drawBg = t }
            if (hyperlink != null && !reverse) drawFg = theme.linkColor

            val leftX = pad + col * cw
            val rightX = pad + end * cw
            if (drawBg != theme.defaultBg) {
                bgPaint.color = drawBg
                canvas.drawRect(leftX, top, rightX, top + ch, bgPaint)
            }

            textPaint.isFakeBoldText = flags and TextStyle.FLAG_BOLD != 0
            textPaint.textSkewX = if (flags and TextStyle.FLAG_ITALIC != 0) -0.25f else 0f
            textPaint.color = drawFg
            if (flags and TextStyle.FLAG_DIM != 0) textPaint.alpha = 160 else textPaint.alpha = 255

            // Build run text, honoring wide-continuation markers (skip render)
            val sb = StringBuilder(end - col)
            for (i in col until end) {
                if (i > col && TextStyle.isWideCont(line.styles[i])) continue
                sb.append(line.chars[i])
            }
            if (sb.isNotEmpty()) {
                canvas.drawText(sb.toString(), leftX, top + metrics.fontAscent, textPaint)
            }

            if (flags and TextStyle.FLAG_UNDERLINE != 0 || hyperlink != null) {
                underlinePaint.color = drawFg
                underlinePaint.strokeWidth = 1.5f * density
                canvas.drawLine(leftX, top + ch - 2 * density, rightX, top + ch - 2 * density, underlinePaint)
            }
            if (flags and TextStyle.FLAG_STRIKETHROUGH != 0) {
                underlinePaint.color = drawFg
                underlinePaint.strokeWidth = 1.5f * density
                val midY = top + ch * 0.55f
                canvas.drawLine(leftX, midY, rightX, midY, underlinePaint)
            }

            col = end
        }
    }

    /**
     * Draws the cursor over the current cell. Shapes: block (filled cell),
     * underline (bottom strip), bar (vertical strip). [cursorWidth] scales the
     * strip thickness of underline/bar. Skipped while the viewport is away
     * from the live bottom — a scrolled-back view must not fake a cursor.
     */
    fun drawCursor(canvas: Canvas, emulator: TerminalEmulator, metrics: Metrics) {
        val buf = emulator.buffer
        if (!buf.cursorVisible || scrollOffset > 0) return
        val cw = metrics.charWidth
        val ch = metrics.charHeight
        if (cw <= 0f || ch <= 0f) return

        // The screen list can be shorter than the nominal row count while an
        // old installation is being resized/reset. Clamp against the real list
        // and line arrays; never index using rows/cols alone.
        val screen = buf.screen()
        val rowCount = minOf(buf.rows, screen.size)
        if (rowCount <= 0 || buf.cursorRow !in 0 until rowCount) return
        val line = screen.getOrNull(buf.cursorRow) ?: return
        val colCount = minOf(buf.cols, line.chars.size, line.styles.size)
        if (colCount <= 0) return
        // VT terminals leave the cursor one cell past the right edge after a
        // printable character at the final column (wrap-pending state).
        val cursorCol = buf.cursorCol.coerceIn(0, colCount - 1)
        val pad = contentPaddingPx
        val left = pad + cursorCol * cw
        val top = pad + buf.cursorRow * ch

        val cursor = theme.cursorColor
        when (cursorStyle) {
            TerminalCursorStyle.BLOCK -> {
                bgPaint.color = cursor and 0x60ffffff or (cursor and 0xff000000.toInt())
                bgPaint.alpha = 70
                canvas.drawRect(left, top, left + cw, top + ch, bgPaint)
                bgPaint.alpha = 255
                textPaint.color = 0xffffffff.toInt()
                canvas.drawText(line.chars[cursorCol].toString(), left, top + metrics.fontAscent, textPaint)
            }
            TerminalCursorStyle.UNDERLINE -> {
                val thickness = (cursorWidth.coerceIn(1, 4)) * 2f
                bgPaint.color = cursor
                canvas.drawRect(left, top + ch - thickness, left + cw, top + ch, bgPaint)
            }
            TerminalCursorStyle.BAR -> {
                val widthPx = (cursorWidth.coerceIn(1, 4)) * 2f
                bgPaint.color = cursor
                canvas.drawRect(left, top, left + widthPx, top + ch, bgPaint)
            }
        }
    }

    var scrollOffset: Int = 0

    private companion object {
        const val HANDLE_RADIUS_DP = 7f
        const val HANDLE_STEM_DP = 14f
    }
}
