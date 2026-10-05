package com.crossberry.noxs.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellUtilTest {

    @Test fun `quotes simple values`() {
        assertEquals("'abc'", ShellUtil.quote("abc"))
        assertEquals("''", ShellUtil.quote(""))
    }

    @Test fun `escapes embedded single quotes`() {
        assertEquals("'it'\\''s'", ShellUtil.quote("it's"))
    }

    @Test fun `dangerous metacharacters stay inert`() {
        val evil = "\$(rm -rf /)`ls`;cat/etc/passwd"
        val line = "echo " + ShellUtil.quote(evil)
        // The quoted form must contain the payload as literal data only.
        assertTrue(line.contains("'\$(rm -rf /)"))
        assertFalse(line.contains(Regex("""[^\x27]\$\(rm"""))) // outside quotes nothing raw
    }

    @Test fun `commandLine quotes every argv element`() {
        val line = ShellUtil.commandLine(listOf("proot", "-r", "/path with space", "sh", "-c", "echo hi"))
        assertEquals("'proot' '-r' '/path with space' 'sh' '-c' 'echo hi'", line)
    }

    @Test fun `isSafePathToken rejects control chars`() {
        assertTrue(ShellUtil.isSafePathToken("/var/run/noxs.sock"))
        assertFalse(ShellUtil.isSafePathToken("/var/run/a\nb"))
        assertFalse(ShellUtil.isSafePathToken(""))
        assertFalse(ShellUtil.isSafePathToken("a\u0000b"))
    }
}

class SocketPathValidatorTest {

    @Test fun `allows run and var-run paths`() {
        assertTrue(SocketPathValidator.isAllowedSocketPath("/var/run/noxs.sock"))
        assertTrue(SocketPathValidator.isAllowedSocketPath("/run/noxs/code-server.sock"))
        assertTrue(SocketPathValidator.isAllowedSocketPath("/var/run/noxs/host/noxs.sock"))
        assertTrue(SocketPathValidator.isAllowedSocketPath("/tmp/app.sock"))
    }

    @Test fun `rejects paths outside sandbox runtime dirs`() {
        assertFalse(SocketPathValidator.isAllowedSocketPath("/etc/noxs.sock"))
        assertFalse(SocketPathValidator.isAllowedSocketPath("/system/bin/x.sock"))
        assertFalse(SocketPathValidator.isAllowedSocketPath("/data/data/com.crossberry.noxs/x.sock"))
        assertFalse(SocketPathValidator.isAllowedSocketPath("relative.sock"))
        assertFalse(SocketPathValidator.isAllowedSocketPath("/var/run")) // directory itself
    }

    @Test fun `normalize collapses traversal`() {
        assertEquals("/var/run/noxs.sock", SocketPathValidator.normalize("/var/run/../run/noxs.sock"))
        assertEquals("/run/a", SocketPathValidator.normalize("//run/./a"))
    }

    @Test fun `physical path maps into rootfs`() {
        assertEquals(
            "/data/data/com.crossberry.noxs/files/noxs/rootfs/var/run/noxs/x.sock",
            SocketPathValidator.physicalPath("/data/data/com.crossberry.noxs/files/noxs/rootfs", "/var/run/noxs/x.sock")
        )
    }
}
