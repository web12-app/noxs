package com.crossberry.noxs.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Terminal link normalization: printed server addresses must open in the
 * browser no matter which host the server bound. 0.0.0.0 / :: are the
 * "all interfaces" addresses programs print in listening banners — browsers
 * cannot navigate to them, so they are rewritten to the device loopback.
 */
class TerminalLinksTest {

    @Test fun `unspecified IPv4 host is rewritten to loopback`() {
        assertEquals("http://127.0.0.1:8000", TerminalLinks.normalize("http://0.0.0.0:8000"))
    }

    @Test fun `unspecified IPv6 hosts are rewritten to loopback`() {
        assertEquals("http://127.0.0.1:3000", TerminalLinks.normalize("http://[::]:3000"))
        assertEquals("http://127.0.0.1", TerminalLinks.normalize("http://[0:0:0:0:0:0:0:0]"))
    }

    @Test fun `real loopback and localhost hosts stay untouched`() {
        assertEquals("http://localhost:8080", TerminalLinks.normalize("http://localhost:8080"))
        assertEquals("http://127.0.0.1:8080", TerminalLinks.normalize("http://127.0.0.1:8080"))
        assertEquals("http://[::1]:8080", TerminalLinks.normalize("http://[::1]:8080"))
    }

    @Test fun `external URLs and paths stay untouched`() {
        assertEquals("https://example.com/docs", TerminalLinks.normalize("https://example.com/docs"))
        assertEquals("http://192.168.1.10:9000/", TerminalLinks.normalize("http://192.168.1.10:9000/"))
    }

    @Test fun `ports and paths survive the rewrite`() {
        assertEquals("http://127.0.0.1:8080/app", TerminalLinks.normalize("http://0.0.0.0:8080/app"))
        assertEquals("http://127.0.0.1:3000/x?y=1", TerminalLinks.normalize("http://[::]:3000/x?y=1"))
    }

    @Test fun `non-http text is returned unchanged`() {
        assertEquals("ftp://0.0.0.0", TerminalLinks.normalize("ftp://0.0.0.0"))
        assertEquals("plain text", TerminalLinks.normalize("plain text"))
    }
}
