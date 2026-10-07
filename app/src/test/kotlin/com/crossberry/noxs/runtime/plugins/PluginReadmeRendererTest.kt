/*
 * Noxs — original implementation.
 * JVM tests for PluginReadmeRenderer: markdown subset coverage and, above
 * all, the sanitization contract — raw HTML, scripts and dangerous URL
 * schemes can never reach the rendered output.
 */
package com.crossberry.noxs.runtime.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginReadmeRendererTest {

    @Test
    fun `renders headings, paragraphs, lists and code`() {
        val markdown = """
            # Hello
            Some paragraph with **bold** and *italic* and `inline`.

            - one
            - two

            1. first
            2. second

            ```sh
            nx plug install hello
            ```

            ## Section two
        """.trimIndent()
        val html = PluginReadmeRenderer.render(markdown)
        assertTrue(html.contains("<h1>Hello</h1>"))
        assertTrue(html.contains("<h2>Section two</h2>"))
        assertTrue(html.contains("<p>Some paragraph with <b>bold</b> and <i>italic</i> and <code>inline</code>.</p>"))
        assertTrue(html.contains("<ul><li>one</li><li>two</li></ul>"))
        assertTrue(html.contains("<ol><li>first</li><li>second</li></ol>"))
        assertTrue(html.contains("<pre><code>nx plug install hello\n</code></pre>"))
    }

    @Test
    fun `renders tables`() {
        val html = PluginReadmeRenderer.render(
            """
            | File | Purpose |
            | ---- | ------- |
            | plugin.json | Metadata |
            | icon.svg | Logo |
            """.trimIndent()
        )
        assertTrue(html.contains("<table><thead><tr><th>File</th><th>Purpose</th></tr></thead>"))
        assertTrue(html.contains("<tr><td>plugin.json</td><td>Metadata</td></tr>"))
    }

    @Test
    fun `http links survive`() {
        val html = PluginReadmeRenderer.render("[Noxs](https://github.com/web12-app/noxs)")
        assertTrue(html.contains("""<a href="https://github.com/web12-app/noxs">Noxs</a>"""))
    }

    @Test
    fun `script tags are escaped away`() {
        val markdown = "# Title\n<script>alert(1)</script>\n\n![img](javascript:alert(2))\n\n[click](javascript:alert(3))"
        val html = PluginReadmeRenderer.render(markdown)
        assertFalse(html.contains("<script>"))
        assertFalse(html.contains("<img"))
        assertFalse(html.contains("href=\"javascript:"))
        // The raw text is still visible (escaped), never executable.
        assertTrue(html.contains("&lt;script&gt;"))
        assertTrue(html.contains("click"))
    }

    @Test
    fun `event handler injection in link labels is neutralized`() {
        val html = PluginReadmeRenderer.render("[a\" onmouseover=\"alert(1)](https://example.invalid/x)")
        // The label is inside an <a> as TEXT (quotes were escaped up front);
        // no unescaped quote can break out of an attribute.
        assertFalse(html.contains("\" onmouseover=\""))
    }

    @Test
    fun `data and file schemes are dropped`() {
        val html = PluginReadmeRenderer.render("[a](data:text/html;base64,AAA) [b](file:///etc/passwd)")
        assertFalse(html.contains("href=\"data:"))
        assertFalse(html.contains("href=\"file:"))
    }

    @Test
    fun `code block content is escaped`() {
        val html = PluginReadmeRenderer.render("```\n<b>&raw</b>\n```")
        assertTrue(html.contains("&lt;b&gt;&amp;raw&lt;/b&gt;"))
        assertFalse(html.contains("<b>&raw</b>"))
    }

    @Test
    fun `empty input renders empty`() {
        assertEquals("", PluginReadmeRenderer.render(""))
        assertEquals("", PluginReadmeRenderer.render("\n\n"))
    }
}
