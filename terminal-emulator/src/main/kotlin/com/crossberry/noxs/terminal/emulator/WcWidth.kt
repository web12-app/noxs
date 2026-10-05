/*
 * Noxs terminal-emulator — original implementation.
 * Compact wcwidth: East-Asian Wide/Fullwidth + zero-width combining ranges.
 * Derived from public Unicode width data conventions (the ranges themselves
 * are facts about Unicode, not copyrightable expression).
 */
package com.crossberry.noxs.terminal.emulator

object WcWidth {

    // Zero-width: combining marks and format characters (common ranges).
    private val ZERO_WIDTH = intArrayOf(
        0x0300, 0x036f, 0x0483, 0x0489, 0x0591, 0x05bd, 0x0610, 0x061a,
        0x064b, 0x065f, 0x0670, 0x0670, 0x06d6, 0x06dc, 0x0711, 0x0711,
        0x0730, 0x074a, 0x07a6, 0x07b0, 0x0816, 0x0819, 0x08e3, 0x0903,
        0x093a, 0x093a, 0x0951, 0x0957, 0x0e31, 0x0e31, 0x0e34, 0x0e3a,
        0x0e47, 0x0e4e, 0x200b, 0x200f, 0x202a, 0x202e, 0x2060, 0x2064,
        0xfe00, 0xfe0f, 0xfe20, 0xfe2f
    )

    // East Asian Wide / Fullwidth (common BMP + emoji planes).
    private val WIDE = intArrayOf(
        0x1100, 0x115f, 0x2e80, 0x303e, 0x3041, 0x33ff, 0x3400, 0x4dbf,
        0x4e00, 0x9fff, 0xa000, 0xa4cf, 0xa960, 0xa97f, 0xac00, 0xd7a3,
        0xf900, 0xfaff, 0xfe10, 0xfe19, 0xfe30, 0xfe6f, 0xff00, 0xff60,
        0xffe0, 0xffe6, 0x1f300, 0x1f64f, 0x1f900, 0x1f9ff, 0x20000, 0x2fffd,
        0x30000, 0x3fffd
    )

    fun width(codePoint: Int): Int {
        if (codePoint == 0) return 0
        if (codePoint < 0x20 || (codePoint in 0x7f..0x9f)) return 0
        if (isInRange(codePoint, ZERO_WIDTH)) return 0
        if (isInRange(codePoint, WIDE)) return 2
        return 1
    }

    private fun isInRange(cp: Int, ranges: IntArray): Boolean {
        var i = 0
        while (i + 1 < ranges.size) {
            if (cp >= ranges[i] && cp <= ranges[i + 1]) return true
            i += 2
        }
        return false
    }
}
