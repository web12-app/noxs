/*
 * Noxs — original implementation.
 * NoxsUrlGuard: single validation point for every URL that reaches a Noxs
 * web view (spec §15-§20). Both the guest-side `nx ow` bridge and the
 * Android-side web window call this before any content loads.
 *
 * Pure JVM (no Android imports) so the full policy is unit-testable.
 */
package com.crossberry.noxs.runtime

import java.net.URI
import java.net.URISyntaxException

object NoxsUrlGuard {

    const val MAX_URL_LENGTH = 2048

    val ALLOWED_SCHEMES: Set<String> = setOf("http", "https")

    private val BLOCKED_SCHEME_HINTS: Set<String> = setOf(
        "javascript", "file", "content", "data", "intent", "chrome",
        "android-app", "about", "blob", "filesystem", "view-source", "ws", "wss"
    )

    sealed class Decision {
        /** Safe to load; [url] is the normalized form actually passed to the web view. */
        data class Allowed(val url: String) : Decision()

        /** Never load; [reason] is a stable, user-safe code (no input echoed back). */
        data class Rejected(val reason: String) : Decision()
    }

    /**
     * Validate [input] for loading in a Noxs web view.
     *
     * Policy:
     *  - only http:// and https:// schemes are allowed
     *  - a host is required; the URI must parse cleanly (this rejects embedded
     *    control characters, spaces and most scheme-smuggling tricks)
     *  - the scheme token is re-checked against the blocked list AFTER parsing
     *    so crafted inputs like "java\tscript:" can never re-enter
     *  - maximum length cap; no input text is ever echoed into the reason
     */
    fun check(input: String?): Decision {
        if (input.isNullOrBlank()) return Decision.Rejected("INVALID_URL")
        val candidate = input.trim()
        if (candidate.length > MAX_URL_LENGTH) return Decision.Rejected("INVALID_URL")
        if (candidate.any { it.isWhitespace() || it < ' ' || it == '\u007F' }) {
            return Decision.Rejected("INVALID_URL")
        }

        val schemeToken = candidate.substringBefore(":", "").lowercase()
        if (schemeToken in BLOCKED_SCHEME_HINTS) return Decision.Rejected("UNSUPPORTED_SCHEME")

        val uri = try {
            URI(candidate)
        } catch (e: URISyntaxException) {
            return Decision.Rejected("INVALID_URL")
        } catch (e: IllegalArgumentException) {
            return Decision.Rejected("INVALID_URL")
        }

        val scheme = uri.scheme?.lowercase()
        if (scheme == null || scheme !in ALLOWED_SCHEMES) return Decision.Rejected("UNSUPPORTED_SCHEME")
        // Re-check the raw prefix too: a parsed scheme can differ from the
        // literal token when the input mixes case or uses unusual grammar.
        if (schemeToken !in ALLOWED_SCHEMES) return Decision.Rejected("UNSUPPORTED_SCHEME")

        val host = uri.host
        if (host.isNullOrBlank()) return Decision.Rejected("INVALID_URL")
        if (host.any { it.isWhitespace() || it < ' ' }) return Decision.Rejected("INVALID_URL")

        uri.port.takeIf { it != -1 }?.let { if (it !in 1..65535) return Decision.Rejected("INVALID_URL") }

        val normalized = runCatching { uri.toASCIIString() }.getOrNull() ?: return Decision.Rejected("INVALID_URL")
        if (normalized.length > MAX_URL_LENGTH) return Decision.Rejected("INVALID_URL")
        return Decision.Allowed(normalized)
    }

    /** Convenience for call sites that just need a boolean. */
    fun isAllowed(input: String?): Boolean = check(input) is Decision.Allowed

    /**
     * Validate a suggested download filename (spec §24): no path separators,
     * no traversal, no hidden host paths — the caller supplies the directory.
     */
    fun safeDownloadName(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val trimmed = name.trim()
        if (trimmed.length > 255) return null
        if (trimmed.contains('/') || trimmed.contains('\\')) return null
        if (trimmed == "." || trimmed == "..") return null
        if (trimmed.any { it < ' ' || it == '\u007F' }) return null
        if (trimmed.startsWith(".")) return null
        return trimmed
    }
}
