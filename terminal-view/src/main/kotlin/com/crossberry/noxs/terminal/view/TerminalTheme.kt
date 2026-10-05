/*
 * Noxs terminal-view — original implementation.
 * Terminal color themes. The Noxs console look stays the default; the others
 * are honest dark/contrast variants (no light theme that would break ANSI
 * readability, except the true paper theme which remaps defaults only).
 */
package com.crossberry.noxs.terminal.view

import android.graphics.Typeface

/**
 * A terminal palette. [defaultFg]/[defaultBg] are used for default-styled
 * cells; ANSI colors always win, so every theme stays terminal-correct.
 */
data class TerminalTheme(
    val key: String,
    val label: String,
    val defaultFg: Int,
    val defaultBg: Int,
    val selectionColor: Int,
    val cursorColor: Int,
    val linkColor: Int
) {
    companion object {
        /** Classic Noxs console: near-black with soft light text. */
        val NOXS_DARK = TerminalTheme(
            key = "noxs_dark", label = "Noxs Dark",
            defaultFg = 0xffe6e6e6.toInt(), defaultBg = 0xff101010.toInt(),
            selectionColor = 0x667f7f7f, cursorColor = 0xffe2e2e2.toInt(),
            linkColor = 0xffbdbdbd.toInt()
        )

        /** Pure black console (OLED-friendly). */
        val BLACK = TerminalTheme(
            key = "black", label = "Black",
            defaultFg = 0xffe8e8e8.toInt(), defaultBg = 0xff000000.toInt(),
            selectionColor = 0x5c5c8a8a, cursorColor = 0xfff2f2f2.toInt(),
            linkColor = 0xff9ece6a.toInt()
        )

        /** Higher contrast than the default, helpful in daylight. */
        val HIGH_CONTRAST = TerminalTheme(
            key = "high_contrast", label = "High contrast",
            defaultFg = 0xffffffff.toInt(), defaultBg = 0xff0a0a0f.toInt(),
            selectionColor = 0x88ffffff.toInt(), cursorColor = 0xffffffff.toInt(),
            linkColor = 0xffffffff.toInt()
        )

        /** Warm paper-toned dark variant, low glare at night. */
        val PAPER = TerminalTheme(
            key = "paper", label = "Paper",
            defaultFg = 0xffe2d9c8.toInt(), defaultBg = 0xff1a1713.toInt(),
            selectionColor = 0x66c8b490, cursorColor = 0xffe2d9c8.toInt(),
            linkColor = 0xffc8b490.toInt()
        )

        val ALL = listOf(NOXS_DARK, BLACK, HIGH_CONTRAST, PAPER)

        fun byKey(key: String): TerminalTheme =
            ALL.firstOrNull { it.key == key } ?: NOXS_DARK
    }
}

/** Font families supported by the renderer (all monospaced metrics). */
enum class TerminalFontFamily(val key: String, val label: String, val typeface: Typeface) {
    MONOSPACE("monospace", "Monospace", Typeface.MONOSPACE),
    SANS("sans", "System sans", Typeface.SANS_SERIF),
    SERIF("serif", "Serif", Typeface.SERIF);

    companion object {
        fun byKey(key: String): TerminalFontFamily =
            entries.firstOrNull { it.key == key } ?: MONOSPACE
    }
}

/** Cursor shapes, standard VT conventions. */
enum class TerminalCursorStyle(val key: String, val label: String) {
    BLOCK("block", "Block"),
    UNDERLINE("underline", "Underline"),
    BAR("bar", "Bar");

    companion object {
        fun byKey(key: String): TerminalCursorStyle =
            entries.firstOrNull { it.key == key } ?: BLOCK
    }
}
