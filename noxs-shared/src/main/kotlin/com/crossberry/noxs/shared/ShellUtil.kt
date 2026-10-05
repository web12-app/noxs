/*
 * Noxs — original implementation.
 * POSIX shell quoting helpers. Every external value interpolated into a
 * command line MUST pass through [ShellUtil.quote].
 */
package com.crossberry.noxs.shared

object ShellUtil {

    /** Single-quote a value for POSIX shells (''' → '\'' escaping). */
    fun quote(value: String): String {
        if (value.isEmpty()) return "''"
        val sb = StringBuilder("'")
        for (c in value) {
            if (c == '\'') sb.append("'\\''") else sb.append(c)
        }
        return sb.append('\'').toString()
    }

    fun quoteAll(values: List<String>): List<String> = values.map { quote(it) }

    fun joinQuoted(values: List<String>): String = quoteAll(values).joinToString(" ")

    /**
     * Build a command line: each element is an argv entry; the result is safe
     * to hand to `sh -c` without injection risk.
     */
    fun commandLine(argv: List<String>): String = joinQuoted(argv)

    /**
     * Defensive validation for a single path token used in generated scripts.
     * Rejects control characters, NUL and embedded newlines.
     */
    fun isSafePathToken(path: String): Boolean {
        if (path.isEmpty()) return false
        if (path.contains('\u0000')) return false
        if (path.any { it < ' ' || it == '\n' || it == '\r' }) return false
        return true
    }
}
