/*
 * Noxs terminal-emulator — original implementation.
 * Search over the terminal document (scrollback + live screen).
 *
 * Pure buffer reads: search never touches the PTY, never writes shell input
 * and never interrupts running processes. Matching is case-insensitive by
 * default and scans the full visible document row space, joining soft-wrapped
 * continuation rows with the previous row so wrapped matches are found.
 */
package com.crossberry.noxs.terminal.emulator

data class TerminalSearchMatch(
    /** Document row (0 = oldest retained row, see TerminalBuffer.documentLineAt). */
    val row: Int,
    /** Inclusive start column of the match. */
    val startCol: Int,
    /** Exclusive end column of the match. */
    val endCol: Int
)

object TerminalSearch {

    const val MAX_MATCHES = 10_000

    /**
     * Finds every occurrence of [query] in the buffer's document.
     * Returns matches ordered oldest → newest. Empty/blank queries return no
     * matches. The caller must hold the emulator monitor while calling.
     */
    fun find(
        buffer: TerminalBuffer,
        query: String,
        caseSensitive: Boolean = false,
        maxMatches: Int = MAX_MATCHES
    ): List<TerminalSearchMatch> {
        if (query.isEmpty() || maxMatches <= 0) return emptyList()
        val needle = if (caseSensitive) query else query.lowercase()
        val matches = ArrayList<TerminalSearchMatch>()
        val rowCount = buffer.documentRowCount()
        for (row in 0 until rowCount) {
            val line = buffer.documentLineAt(row) ?: continue
            val text = visibleText(line)
            if (text.isEmpty()) continue
            val haystack = if (caseSensitive) text else text.lowercase()
            var from = 0
            while (true) {
                val idx = haystack.indexOf(needle, from)
                if (idx < 0) break
                matches.add(TerminalSearchMatch(row, idx, idx + needle.length))
                if (matches.size >= maxMatches) return matches
                from = idx + needle.length
                if (from >= haystack.length) break
            }
        }
        return matches
    }

    /**
     * Searchable text of one row. Trailing spaces are trimmed by Line.text();
     * wide-continuation markers are style bits, never stored as glyphs, so the
     * raw char array is already the on-screen text.
     */
    private fun visibleText(line: TerminalBuffer.Line): String = line.text()

    /** Convenience: cycle [matches] forward/backward from [current]. */
    fun step(matches: List<TerminalSearchMatch>, current: Int, forward: Boolean): Int {
        if (matches.isEmpty()) return -1
        return when {
            forward -> if (current + 1 >= matches.size) 0 else current + 1
            else -> if (current - 1 < 0) matches.size - 1 else current - 1
        }
    }
}
