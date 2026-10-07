/*
 * Noxs — original implementation.
 * PluginReadmeRenderer: turns a plugin README.md into sanitized HTML for
 * the Plugin Store details page.
 *
 * Security contract:
 *  - the markdown source is HTML-ESCAPED FIRST, so no raw HTML (script,
 *    iframe, event handlers, ...) can ever reach the output
 *  - only http(s) links survive; every other scheme (javascript:, data:,
 *    file:, intent:, ...) renders as inert text
 *  - images render as links (no remote fetches, no mixed content)
 *  - the receiving WebView runs with JavaScript disabled — this renderer
 *    is the second layer of defense, not the only one
 *
 * Supported markdown: headings, paragraphs, unordered/ordered lists,
 * fenced code blocks, inline code, bold/italic, links, tables.
 */
package com.crossberry.noxs.runtime.plugins

object PluginReadmeRenderer {

    private val SAFE_URL = Regex("(?i)^https?://[A-Za-z0-9._~:/?#@!$&'()*+,;=%\\[\\]-]+$")

    /** Renders markdown to sanitized HTML. Never throws on user content. */
    fun render(markdown: String): String {
        val escaped = escapeHtml(markdown.replace("\r\n", "\n").replace('\r', '\n'))
        val lines = escaped.split('\n')
        val out = StringBuilder()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            when {
                isFence(line) -> {
                    // Consume until the closing fence; content stays escaped.
                    val code = StringBuilder()
                    index++
                    while (index < lines.size && !isFence(lines[index])) {
                        code.append(lines[index]).append('\n')
                        index++
                    }
                    index++ // closing fence (or end of input)
                    out.append("<pre><code>").append(code).append("</code></pre>\n")
                }
                isTableHeader(lines, index) -> {
                    index = renderTable(lines, index, out)
                }
                headingLevel(line) in 1..6 -> {
                    val level = headingLevel(line)
                    out.append("<h").append(level).append('>')
                        .append(inline(line.substringAfter('#', "").trim()))
                        .append("</h").append(level).append(">\n")
                    index++
                }
                isListItem(line) -> {
                    index = renderList(lines, index, out)
                }
                line.isBlank() -> index++
                else -> {
                    // Paragraph: consecutive non-blank, non-structural lines.
                    val paragraph = StringBuilder()
                    while (index < lines.size &&
                        lines[index].isNotBlank() &&
                        headingLevel(lines[index]) == 0 &&
                        !isListItem(lines[index]) &&
                        !isFence(lines[index]) &&
                        !isTableHeader(lines, index)
                    ) {
                        paragraph.append(lines[index]).append(' ')
                        index++
                    }
                    out.append("<p>").append(inline(paragraph.toString().trim())).append("</p>\n")
                }
            }
        }
        return out.toString().trimEnd('\n')
    }

    // ------------------------------------------------------------- blocks

    private fun isFence(line: String): Boolean = line.trimStart().startsWith("```")

    private fun headingLevel(line: String): Int {
        val trimmed = line.trimStart()
        var level = 0
        while (level < trimmed.length && trimmed[level] == '#') level++
        return if (level in 1..6 && level < trimmed.length && trimmed[level] == ' ') level else 0
    }

    private fun isListItem(line: String): Boolean =
        Regex("^\\s*([-*+]|\\d+\\.)\\s+\\S").containsMatchIn(line)

    private fun isTableHeader(lines: List<String>, index: Int): Boolean {
        if (index + 1 >= lines.size) return false
        val header = lines[index]
        val separator = lines[index + 1].trim()
        if (!header.contains('|') || header.trimStart().startsWith("```")) return false
        return Regex("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?$").matches(separator)
    }

    private fun renderTable(lines: List<String>, start: Int, out: StringBuilder): Int {
        fun cells(line: String): List<String> =
            line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

        val header = cells(lines[start])
        out.append("<table><thead><tr>")
        header.forEach { out.append("<th>").append(inline(it)).append("</th>") }
        out.append("</tr></thead><tbody>")
        var index = start + 2
        while (index < lines.size && lines[index].contains('|') && lines[index].isNotBlank()) {
            out.append("<tr>")
            cells(lines[index]).forEach { out.append("<td>").append(inline(it)).append("</td>") }
            out.append("</tr>")
            index++
        }
        out.append("</tbody></table>\n")
        return index
    }

    private fun renderList(lines: List<String>, start: Int, out: StringBuilder): Int {
        val ordered = Regex("^\\s*\\d+\\.\\s").containsMatchIn(lines[start])
        val tag = if (ordered) "ol" else "ul"
        out.append('<').append(tag).append('>')
        var index = start
        while (index < lines.size && isListItem(lines[index])) {
            val item = lines[index].trim().replace(Regex("^([-*+]|\\d+\\.)\\s+"), "")
            out.append("<li>").append(inline(item)).append("</li>")
            index++
        }
        out.append("</").append(tag).append(">\n")
        return index
    }

    // -------------------------------------------------------------- inline

    private fun inline(text: String): String {
        var result = text
        // Inline code first; its content is already escaped, so no tag can hide inside.
        result = Regex("`([^`]+)`").replace(result) { match ->
            "<code>" + match.groupValues[1] + "</code>"
        }
        // Images BEFORE links (otherwise the link rule eats the image syntax).
        // Images are never fetched — a link to the source is enough.
        result = Regex("!\\[([^\\]]*)\\]\\(([^)\\s]+)\\)").replace(result) { match ->
            val alt = match.groupValues[1]
            val url = match.groupValues[2]
            if (SAFE_URL.containsMatchIn(url)) "(image: <a href=\"" + url + "\">" + alt + "</a>)" else alt
        }
        // Links: only http(s) URLs survive. Everything else stays visible text.
        result = Regex("\\[([^\\]]+)\\]\\(([^)\\s]+)\\)").replace(result) { match ->
            val label = match.groupValues[1]
            val url = match.groupValues[2].replace("&#x20;", "")
            if (SAFE_URL.containsMatchIn(url)) {
                "<a href=\"" + url + "\">" + label + "</a>"
            } else {
                label
            }
        }
        // Bold / italic.
        result = Regex("\\*\\*([^*]+)\\*\\*").replace(result) { "<b>${it.groupValues[1]}</b>" }
        result = Regex("(?<![a-zA-Z0-9*])\\*([^*\\s][^*]*)\\*(?![a-zA-Z0-9*])")
            .replace(result) { "<i>${it.groupValues[1]}</i>" }
        return result
    }

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}
