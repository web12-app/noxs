package com.crossberry.noxs.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** NoxsUrlGuard policy (Noxs platform spec §18): the full matrix. */
class NoxsUrlGuardTest {

    private fun allowed(url: String): Boolean = NoxsUrlGuard.check(url) is NoxsUrlGuard.Decision.Allowed

    @Test fun `http and https are allowed`() {
        assertTrue(allowed("https://example.com"))
        assertTrue(allowed("http://example.com/path?q=1"))
        assertTrue(allowed("http://localhost:8080/index.html"))
        assertTrue(allowed("https://192.168.1.10:3000/api"))
    }

    @Test fun `dangerous schemes are rejected`() {
        assertFalse(allowed("javascript:alert(1)"))
        assertFalse(allowed("file:///etc/passwd"))
        assertFalse(allowed("data:text/html,<script>1</script>"))
        assertFalse(allowed("content://settings"))
        assertFalse(allowed("intent://x#Intent;scheme=http;end"))
        assertFalse(allowed("chrome://flags"))
        assertFalse(allowed("android-app://com.example"))
        assertFalse(allowed("about:blank"))
        assertFalse(allowed("blob:https://example.com/x"))
        assertFalse(allowed("ws://example.com"))
    }

    @Test fun `crafted input cannot smuggle a scheme`() {
        assertFalse(allowed("java\tscript:alert(1)"))
        assertFalse(allowed("java\nscript:alert(1)"))
        assertFalse(allowed(" JavaScript:x"))
        assertFalse(allowed("  https://example.com  ")) // trimmed, still allowed
        assertTrue(allowed("HTTPS://EXAMPLE.COM/PATH"))
    }

    @Test fun `structural garbage is rejected`() {
        assertFalse(NoxsUrlGuard.check(null) is NoxsUrlGuard.Decision.Allowed)
        assertFalse(allowed(""))
        assertFalse(allowed("   "))
        assertFalse(allowed("https://"))
        assertFalse(allowed("example.com")) // no scheme
        assertFalse(allowed("a".repeat(2049)))
    }

    @Test fun `allowed urls are normalized`() {
        val decision = NoxsUrlGuard.check("https://example.com")
        assertEquals("https://example.com", (decision as NoxsUrlGuard.Decision.Allowed).url)
    }

    @Test fun `download names never traverse`() {
        assertEquals("release.tar.xz", NoxsUrlGuard.safeDownloadName("release.tar.xz"))
        assertEquals(null, NoxsUrlGuard.safeDownloadName("../evil"))
        assertEquals(null, NoxsUrlGuard.safeDownloadName("/etc/shadow"))
        assertEquals(null, NoxsUrlGuard.safeDownloadName(".."))
        assertEquals(null, NoxsUrlGuard.safeDownloadName(".hidden"))
        assertEquals(null, NoxsUrlGuard.safeDownloadName("bad\u0000name"))
        assertEquals(null, NoxsUrlGuard.safeDownloadName(null))
    }
}
