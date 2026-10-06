/*
 * Noxs — original implementation.
 * Terminal link helpers: make printed server addresses actually usable
 * outside the terminal. Programs routinely bind to 0.0.0.0 / :: and print
 * those hosts in "listening on" banners; browsers cannot navigate to the
 * unspecified address, so opening the link as-is fails even though the
 * server is perfectly reachable on the device loopback. Normalizing the
 * host to 127.0.0.1 makes "global" access from the browser work while the
 * server keeps listening on all interfaces.
 */
package com.crossberry.noxs.ui

object TerminalLinks {

    /**
     * http(s)://HOST[:PORT][/path] where HOST is a bare hostname, an IPv4
     * literal, or a bracketed IPv6 literal.
     */
    private val HTTP_URL = Regex(
        "^(https?://)([^/:\\[\\]\\s]+|\\[[^\\]]+\\])(:\\d+)?(/.*)?$",
        setOf(RegexOption.IGNORE_CASE)
    )

    /** Hosts that mean "all interfaces" and must be rewritten for browsers. */
    private val UNSPECIFIED_HOSTS = setOf("0.0.0.0", "::", "[::]", "[0:0:0:0:0:0:0:0]")

    /**
     * Rewrite loopback-openable forms of the unspecified address to
     * 127.0.0.1. Everything else (real hostnames, 127.0.0.1, localhost,
     * [::1], LAN IPs) is returned unchanged.
     */
    fun normalize(uri: String): String {
        val m = HTTP_URL.find(uri.trim()) ?: return uri
        val scheme = m.groupValues[1]
        val host = m.groupValues[2]
        val port = m.groupValues[3]
        val rest = m.groupValues[4]
        val rewritten = if (host.lowercase() in UNSPECIFIED_HOSTS) "127.0.0.1" else host
        return scheme + rewritten + port + rest
    }
}
