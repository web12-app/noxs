/*
 * Noxs terminal-view — original implementation.
 * Canvas-based renderer for the Noxs terminal buffer.
 */
package com.noxs.linux.terminal.view

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.noxs.linux.shared.NoxsConstants
import com.noxs.linux.terminal.emulator.TerminalBuffer
import com.noxs.linux.terminal.emulator.TerminalColors
import com.noxs.linux.terminal.emulator.TerminalEmulator
import com.noxs.linux.terminal.emulator.TextStyle

class TerminalRenderer {

    data class Metrics(val charWidth: Float, val charHeight: Float, val fontAscent: Float)

    private val textPaint = Paint().apply {
        typeface = Typeface.MONOSPACE
        isAntiAlias = true
    }
    private val bgPaint = Paint().apply { style = Paint.Style.FILL }
    private val underlinePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val defaultFg = 0xffe6e6e6.toInt()
    private val defaultBg = 0xff10141a.toInt()
    private val cursorColor = 0xff3ddc84.toInt()

    var fontSizeSp: Float = 14f
    var densityScale: Float = 1f

    fun measure(): Metrics {
        textPaint.textSize = fontSizeSp * densityScale
        val fm = textPaint.fontMetrics
        val cw = textPaint.measureText("W")
        return Metrics(cw, fm.descent - fm.ascent, -fm.ascent)
    }

    fun render(
        canvas: Canvas,
        emulator: TerminalEmulator,
        metrics: Metrics,
        scrollRows: Int,
        focused: Boolean,
        density: Float
    ) {
        val buf = emulator.buffer
        canvas.drawColor(defaultBg)

        val cw = metrics.charWidth
        val ch = metrics.charHeight
        if (cw <= 0f || ch <= 0f) return

        val screen = buf.screen()
        val screenRows = minOf(buf.rows, screen.size)
        val visibleRows = (canvas.height / ch).toInt().coerceAtLeast(0).coerceAtMost(screenRows)

        for (visRow in 0 until visibleRows) {
            val (line, screenRow) = resolveRow(buf, screen, visRow, scrollRows) ?: continue
            val top = visRow * ch
            renderRow(canvas, line, top, cw, ch, metrics, screenRow == buf.cursorRow && scrollRows == 0 && focused, density)
        }
    }

    private fun resolveRow(
        buf: TerminalBuffer,
        screen: List<TerminalBuffer.Line>,
        visRow: Int,
        scrollRows: Int
    ): Pair<TerminalBuffer.Line, Int>? {
        // scrollRows > 0 means the user scrolled up into scrollback
        return if (scrollRows > 0) {
            val line = buf.scrollbackLine(scrollRows - visRow - 1)
                ?: buf.scrollbackLine(scrollRows - 1)
            line?.let { it to -1 }
        } else {
            if (visRow !in 0 until minOf(buf.rows, screen.size)) return null
            screen.getOrNull(visRow)?.let { it to visRow }
        }
    }

    private fun renderRow(
        canvas: Canvas,
        line: TerminalBuffer.Line,
        top: Float,
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
            var end = col + 1
            while (end < cols && line.styles[end] == style) end++

            val fg = TerminalColors.colorOf(style, true, defaultFg, defaultBg)
            val bg = TerminalColors.colorOf(style, false, defaultFg, defaultBg)
            val flags = TextStyle.flags(style)
            val reverse = flags and TextStyle.FLAG_REVERSE != 0

            var drawFg = if (flags and TextStyle.FLAG_INVISIBLE != 0) bg else fg
            var drawBg = bg
            if (reverse) { val t = drawFg; drawFg = drawBg; drawBg = t }

            if (drawBg != defaultBg) {
                bgPaint.color = drawBg
                canvas.drawRect(col * cw, top, end * cw, top + ch, bgPaint)
            }

            // cursor cell highlight
            if (isCursorRow) {
                // cursor position provided by view layer via cursorCol
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
                canvas.drawText(sb.toString(), col * cw, top + metrics.fontAscent, textPaint)
            }

            if (flags and TextStyle.FLAG_UNDERLINE != 0) {
                underlinePaint.color = drawFg
                underlinePaint.strokeWidth = 1.5f * density
                canvas.drawLine(col * cw, top + ch - 2 * density, end * cw, top + ch - 2 * density, underlinePaint)
            }
            if (flags and TextStyle.FLAG_STRIKETHROUGH != 0) {
                underlinePaint.color = drawFg
                underlinePaint.strokeWidth = 1.5f * density
                val midY = top + ch * 0.55f
                canvas.drawLine(col * cw, midY, end * cw, midY, underlinePaint)
            }

            col = end
        }
    }

    /** Draws the cursor block over the current cell (called after rows). */
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
        val left = cursorCol * cw
        val top = buf.cursorRow * ch

        bgPaint.color = cursorColor and 0x60ffffff or (cursorColor and 0xff000000.toInt())
        bgPaint.alpha = 70
        canvas.drawRect(left, top, left + cw, top + ch, bgPaint)
        bgPaint.alpha = 255
        // Re-draw the character under the cursor inverted for visibility.
        textPaint.color = 0xffffffff.toInt()
        canvas.drawText(line.chars[cursorCol].toString(), left, top + metrics.fontAscent, textPaint)
    }

    var scrollOffset: Int = 0
}
