/*
 * Noxs — original implementation.
 * Plain-text URL detection over terminal buffer text.
 *
 * Programs routinely print local server addresses ("http://localhost:8080",
 * "http://127.0.0.1:3000") without OSC 8 hyperlink escapes. This helper makes
 * those genuinely-printed URLs tappable: it only ever returns text that is
 * really present on screen — no fabricated addresses, no auto-browsing.
 */
package com.crossberry.noxs.terminal.emulator

object TerminalTextLinks {

    /** Scheme'd URLs: everything up to the first whitespace. */
    private val SCHEMED_URL = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)

    /**
     * Scheme-less loopback addresses with a port (optionally a path). Only
     * hosts that unambiguously mean "this device" are matched, so random
     * words never become links.
     */
    private val LOOPBACK_URL = Regex(
        """(?<![\w@.])(?:localhost|127\.0\.0\.1|\[::1\]|::1|0\.0\.0\.0)(?::\d{1,5})(?:/[^\s]*)?""",
        RegexOption.IGNORE_CASE
    )

    /** Characters that routinely trail a printed URL but are not part of it. */
    private val TRAILING_PUNCTUATION = ".,;:!?)>]}\"'»"

    /**
     * Returns the URL that spans [textIndex] (an index into [text]), or null
     * when the position is not inside one. Scheme'd links win over loopback
     * shorthand when both match the same span.
     */
    fun findUrlAt(text: String, textIndex: Int): String? {
        if (textIndex < 0 || textIndex > text.length) return null
        spanAt(text, textIndex)?.let { return it.second }
        return null
    }

    /** Same as [findUrlAt] but also returns the matched character span. */
    fun findUrlSpanAt(text: String, textIndex: Int): Pair<IntRange, String>? {
        if (textIndex < 0 || textIndex > text.length) return null
        return spanAt(text, textIndex)
    }

    /** All URLs present on one line, left to right (used by tests/tools). */
    fun findAll(text: String): List<String> {
        val out = mutableListOf<String>()
        SCHEMED_URL.findAll(text).forEach { out += trimUrl(it.value) }
        LOOPBACK_URL.findAll(text).forEach { match ->
            // Skip loopback shorthand already covered by a scheme'd URL.
            val covered = SCHEMED_URL.findAll(text).any {
                match.range.first >= it.range.first && match.range.last <= it.range.last
            }
            if (!covered) out += trimUrl(match.value)
        }
        return out.distinct()
    }

    private fun spanAt(text: String, textIndex: Int): Pair<IntRange, String>? {
        val matches = mutableListOf<Pair<IntRange, String>>()
        SCHEMED_URL.findAll(text).forEach { matches += it.range to trimUrl(it.value) }
        LOOPBACK_URL.findAll(text).forEach { match ->
            val covered = matches.any { (range, _) ->
                match.range.first >= range.first && match.range.last <= range.last
            }
            if (!covered) matches += match.range to trimUrl(match.value)
        }
        for ((range, url) in matches) {
            if (url.isEmpty()) continue
            if (textIndex in range) return range to url
        }
        return null
    }

    private fun trimUrl(raw: String): String {
        var end = raw.length
        while (end > 0 && TRAILING_PUNCTUATION.contains(raw[end - 1])) end--
        // Keep a trailing slash trimmed only when it is pure punctuation noise.
        return raw.substring(0, end)
    }

    /**
     * Maps a terminal cell column to an index into the line's text, honoring
     * wide characters (a wide glyph occupies two cells but one char).
     */
    fun cellColumnToTextIndex(chars: CharArray, styles: LongArray, column: Int): Int {
        var index = 0
        val limit = column.coerceAtMost(chars.size)
        for (cell in 0 until limit) {
            if (cell < styles.size && TextStyle.isWideCont(styles[cell])) continue
            index++
        }
        return index
    }
}
