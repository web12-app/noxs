/*
 * Noxs — original implementation.
 * Width-aware console text for the setup screen (pure Kotlin, JVM-testable).
 *
 * Root-cause fix for the "stuck repeating [ Noxs ] Installing or repairing
 * ca-certificate" report: the live operation line was rendered without any
 * terminal-width awareness (57 visible columns for the longest names) while
 * phone terminals are typically 34–60 columns wide. The emulator's autowrap
 * pushed the overflow onto a new row, and because carriage return only moves
 * to column 0 of the LAST (wrapped) row, every 100 ms tick of the rewrite
 * loop spilled a brand-new screen row carrying the same message — the console
 * visibly flooded with one repeated status line for as long as the underlying
 * apt/dpkg step ran.
 *
 * Every live line is now clamped to the terminal's real column count, with a
 * compact elapsed-only fallback for extremely narrow terminals. The full,
 * untruncated operation name always remains available in the toolbar via
 * NoxsSetupSession.liveOp.
 */
package com.crossberry.noxs.runtime

object SetupConsoleText {

    /** Visible width of the standard "[ Noxs ] " prefix including its gap. */
    const val PREFIX: String = "[ Noxs ]"
    const val PREFIX_VISIBLE_WIDTH: Int = 9 // "[ Noxs ]"(8) + " "(1)

    /** Fixed per-line cost around the name: frame + gaps + elapsed. */
    const val OP_LINE_FIXED_OVERHEAD: Int = 18 // prefix(9) + frame(1) + " "(1) + "  "(2) + elapsed(5)

    /** Below this width the full form cannot fit; the compact form is used. */
    const val COMPACT_MIN_WIDTH: Int = 24

    /**
     * The live operation line split into a colorizable prefix and a plain
     * body whose visible length never exceeds [width] columns:
     *  - full form (width >= [COMPACT_MIN_WIDTH]): prefix "[ Noxs ]" and
     *    body "frame name…  elapsed" with the name clamped to the budget;
     *  - compact form (very narrow terminals): no prefix, body
     *    "frame elapsed" — still an in-place, non-wrapping rewrite.
     */
    fun opLine(name: String, frame: Char, elapsed: String, width: Int): Pair<String, String> {
        if (width < COMPACT_MIN_WIDTH) return "" to compactLine(frame, elapsed, width)
        val fitted = fitOpName(name, width)
        return PREFIX to "$frame $fitted  $elapsed"
    }

    /**
     * Clamps an operation name so the full live line fits [width] columns.
     * Never returns a name longer than the budget; uses an ellipsis when
     * truncating with room, and hard-truncates at very small budgets.
     */
    fun fitOpName(name: String, width: Int): String {
        if (width <= 0) return ""
        val budget = (width - OP_LINE_FIXED_OVERHEAD).coerceAtLeast(1)
        if (name.length <= budget) return name
        return if (budget >= 2) name.take(budget - 1) + "…" else name.take(budget)
    }

    /** Frame + elapsed only — fits any width >= 8 columns. */
    fun compactLine(frame: Char, elapsed: String, width: Int): String {
        val text = "$frame $elapsed"
        return if (text.length <= width) text else text.take(width.coerceAtLeast(0))
    }

    /**
     * Padding for in-place rewrite of a live line (download progress and
     * similar). The result never exceeds [width] columns, so the rewrite can
     * never wrap and flood the console.
     */
    fun livePadding(plainLength: Int, width: Int): Int =
        (width - 1 - plainLength).coerceAtLeast(0)

    /** Real terminal width for the setup console with a safe fallback. */
    fun safeWidth(raw: Int): Int = if (raw in 8..500) raw else 80
}
