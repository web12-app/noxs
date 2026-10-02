/*
 * Noxs terminal-emulator — original clean-room VT/xterm implementation.
 *
 * Style packing: 64-bit long per cell.
 *   bits 63..40 : flags (bold, italic, underline, dim, blink, reverse, hidden, strike)
 *   bits 39..16 : foreground (24-bit RGB or palette index in low 8 bits when FOREGROUND_PALETTE)
 *   bits 15.. 0 : hmm — this comment block documents the actual layout below.
 *
 * Actual layout (simple and fast):
 *   [fg24:24][bg24:24][flags:8][cs:8]  → stored as fg << 40 | bg << 16 | flags << 8 | charset
 * Palette indices are expanded to RGB at render time by TerminalColors.
 */
package com.noxs.linux.terminal.emulator

object TextStyle {
    const val CHARSET_MASK = 0xff
    const val FLAGS_MASK = 0xff
    const val COLOR_MASK = 0xffffff

    // Attribute flags (bit 0..7 of the flags byte)
    const val FLAG_BOLD = 1
    const val FLAG_ITALIC = 1 shl 1
    const val FLAG_UNDERLINE = 1 shl 2
    const val FLAG_DIM = 1 shl 3
    const val FLAG_BLINK = 1 shl 4
    const val FLAG_REVERSE = 1 shl 5
    const val FLAG_INVISIBLE = 1 shl 6
    const val FLAG_STRIKETHROUGH = 1 shl 7

    const val DEFAULT_FG_INDEX = 256 // resolved to theme foreground at render time
    const val DEFAULT_BG_INDEX = 257 // resolved to theme background at render time

    /**
     * Wide-character continuation cell marker. Stored in the charset byte
     * (255) so it never collides with fg/bg color bits; renderers skip these
     * cells because the wide glyph is drawn at its start cell.
     */
    const val CHARSET_WIDE_CONT = 0xFF

    fun isWideCont(style: Long): Boolean =
        (style and CHARSET_MASK.toLong()).toInt() == CHARSET_WIDE_CONT

    fun wideContOf(style: Long): Long =
        (style and CHARSET_MASK.inv().toLong()) or CHARSET_WIDE_CONT.toLong()

    fun defaultStyle(): Long = encode(DEFAULT_FG_INDEX, DEFAULT_BG_INDEX, 0)

    fun encode(fg: Int, bg: Int, flags: Int, charset: Int = 0): Long =
        ((fg and COLOR_MASK).toLong() shl 40) or
            ((bg and COLOR_MASK).toLong() shl 16) or
            ((flags and FLAGS_MASK).toLong() shl 8) or
            (charset and CHARSET_MASK).toLong()

    fun fg(style: Long): Int = ((style ushr 40) and COLOR_MASK.toLong()).toInt()
    fun bg(style: Long): Int = ((style ushr 16) and COLOR_MASK.toLong()).toInt()
    fun flags(style: Long): Int = ((style ushr 8) and FLAGS_MASK.toLong()).toInt()
    fun charset(style: Long): Int = (style and CHARSET_MASK.toLong()).toInt()

    const val DEFAULT: Long = 0L // legacy zero style — renderer treats as defaultStyle()
}

/** 256-color palette (xterm-compatible defaults) + truecolor passthrough. */
object TerminalColors {

    val ANSI: IntArray = intArrayOf(
        0xff000000.toInt(), 0xffcd0000.toInt(), 0xff00cd00.toInt(), 0xffcdcd00.toInt(),
        0xff0000ee.toInt(), 0xffcd00cd.toInt(), 0xff00cdcd.toInt(), 0xffe5e5e5.toInt(),
        0xff7f7f7f.toInt(), 0xffff0000.toInt(), 0xff00ff00.toInt(), 0xffffff00.toInt(),
        0xff5c5cff.toInt(), 0xffff00ff.toInt(), 0xff00ffff.toInt(), 0xffffffff.toInt()
    )

    val PALETTE: IntArray = IntArray(256).also { p ->
        ANSI.copyInto(p)
        // 6x6x6 color cube
        val steps = intArrayOf(0, 95, 135, 175, 215, 255)
        var i = 16
        for (r in 0..5) for (g in 0..5) for (b in 0..5) {
            p[i++] = -0x1000000 or (steps[r] shl 16) or (steps[g] shl 8) or steps[b]
        }
        // Grayscale ramp
        for (g in 0..23) {
            val v = 8 + g * 10
            p[i++] = -0x1000000 or (v shl 16) or (v shl 8) or v
        }
    }

    fun colorOf(style: Long, isForeground: Boolean, defaultFg: Int, defaultBg: Int): Int {
        val raw = if (isForeground) TextStyle.fg(style) else TextStyle.bg(style)
        return when {
            raw == TextStyle.DEFAULT_FG_INDEX -> defaultFg
            raw == TextStyle.DEFAULT_BG_INDEX -> defaultBg
            raw in 0..255 -> PALETTE[raw]
            else -> raw
        }
    }
}
