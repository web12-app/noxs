/*
 * Noxs — original implementation.
 * Honest Docker classification: Android/proot restrictions are runtime
 * limitations, never "installation corrupted". Real output samples from
 * dockerd / docker CLI are used as test fixtures.
 */
package com.crossberry.noxs.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoxsDockerCompatTest {

    @Test fun `iptables nft failure is a networking limitation`() {
        val verdict = NoxsDockerCompat.classifyFailure(
            "iptables: Failed to initialize nft: Permission denied (you must be root)"
        )
        assertEquals(NoxsDockerCompat.Limitation.NETWORKING, verdict.limitation)
        assertTrue(verdict.reason.contains("Android", ignoreCase = true))
    }

    @Test fun `nftables not permitted is a networking limitation`() {
        val verdict = NoxsDockerCompat.classifyFailure(
            "could not insert rule 'DOCKER': nftables: operation not permitted"
        )
        assertEquals(NoxsDockerCompat.Limitation.NETWORKING, verdict.limitation)
    }

    @Test fun `memory controller missing is a cgroup limitation`() {
        val verdict = NoxsDockerCompat.classifyFailure(
            "Error starting daemon: Unable to find memory controller"
        )
        assertEquals(NoxsDockerCompat.Limitation.CGROUPS, verdict.limitation)
    }

    @Test fun `cpu controller missing is a cgroup limitation`() {
        val verdict = NoxsDockerCompat.classifyFailure(
            "Error starting daemon: Unable to find cpu controller"
        )
        assertEquals(NoxsDockerCompat.Limitation.CGROUPS, verdict.limitation)
    }

    @Test fun `pids and cpuset controllers are cgroup limitations`() {
        assertEquals(
            NoxsDockerCompat.Limitation.CGROUPS,
            NoxsDockerCompat.classifyFailure("unable to find pids controller").limitation
        )
        assertEquals(
            NoxsDockerCompat.Limitation.CGROUPS,
            NoxsDockerCompat.classifyFailure("unable to find cpuset controller").limitation
        )
        assertEquals(
            NoxsDockerCompat.Limitation.CGROUPS,
            NoxsDockerCompat.classifyFailure("unable to find io controller").limitation
        )
    }

    @Test fun `cgroup controllers permission denied is a cgroup limitation`() {
        val verdict = NoxsDockerCompat.classifyFailure(
            "open /sys/fs/cgroup/cgroup.controllers: permission denied"
        )
        assertEquals(NoxsDockerCompat.Limitation.CGROUPS, verdict.limitation)
    }

    @Test fun `overlay mount denial is a storage limitation`() {
        val verdict = NoxsDockerCompat.classifyFailure(
            "error creating overlay mount to /var/lib/docker/overlay2: operation not permitted"
        )
        assertEquals(NoxsDockerCompat.Limitation.STORAGE, verdict.limitation)
    }

    @Test fun `generic operation not permitted is a kernel limitation`() {
        val verdict = NoxsDockerCompat.classifyFailure("unshare(CLONE_NEWNS): Operation not permitted")
        assertEquals(NoxsDockerCompat.Limitation.KERNEL, verdict.limitation)
    }

    @Test fun `unknown errors keep the real message`() {
        val verdict = NoxsDockerCompat.classifyFailure("docker: Error response from daemon: driver failure")
        assertEquals(NoxsDockerCompat.Limitation.UNKNOWN, verdict.limitation)
        assertTrue(verdict.reason.contains("driver failure"))
    }

    @Test fun `daemon not running marker is detected`() {
        assertTrue(
            NoxsDockerCompat.isDaemonNotRunning(
                "Cannot connect to the Docker daemon at unix:///var/run/docker.sock. Is the docker daemon running?"
            )
        )
        assertFalse(NoxsDockerCompat.isDaemonNotRunning("Server Version: 24.0.7"))
    }

    @Test fun `storage driver is parsed from real docker info output`() {
        val output = """
            Client:
             Version: 24.0.7
            
            Server:
             Storage Driver: overlay2
              Backing Filesystem: extfs
             Containers: 0
        """.trimIndent()
        assertEquals("overlay2", NoxsDockerCompat.parseStorageDriver(output))
    }

    @Test fun `missing storage driver line parses to null`() {
        assertNull(NoxsDockerCompat.parseStorageDriver("Cannot connect to the Docker daemon"))
    }

    @Test fun `driver preference order matches the spec`() {
        assertEquals(0, NoxsDockerCompat.driverRank("overlay2"))
        assertEquals(1, NoxsDockerCompat.driverRank("fuse-overlayfs"))
        assertEquals(2, NoxsDockerCompat.driverRank("vfs"))
        assertEquals(Int.MAX_VALUE, NoxsDockerCompat.driverRank("btrfs"))
        assertEquals(Int.MAX_VALUE, NoxsDockerCompat.driverRank(null))
    }

    @Test fun `vfs fallback is offered only for better drivers`() {
        assertEquals("--storage-driver=vfs", NoxsDockerCompat.storageFallbackFlag("overlay2"))
        assertEquals("--storage-driver=vfs", NoxsDockerCompat.storageFallbackFlag("fuse-overlayfs"))
        assertNull(NoxsDockerCompat.storageFallbackFlag("vfs"))
        assertNull(NoxsDockerCompat.storageFallbackFlag(null))
    }

    @Test fun `compat daemon flags never enable privileged operations`() {
        val flags = NoxsDockerCompat.compatDaemonFlags()
        assertEquals(listOf("--iptables=false", "--bridge=none"), flags)
    }

    @Test fun `state file round trip keeps only safe facts`() {
        val text = NoxsDockerCompat.serializeState(
            NoxsDockerCompat.State.COMPATIBILITY, "compatibility", daemonRunning = true
        )
        assertFalse(text.contains("password", ignoreCase = true))
        val parsed = NoxsDockerCompat.parseState(text)
        assertEquals(NoxsDockerCompat.State.COMPATIBILITY, parsed.state)
        assertEquals("compatibility", parsed.mode)
        assertTrue(parsed.daemonRunning)
    }

    @Test fun `all four final states parse from their keys`() {
        listOf(
            NoxsDockerCompat.State.READY,
            NoxsDockerCompat.State.COMPATIBILITY,
            NoxsDockerCompat.State.INSTALLED_DAEMON_UNAVAILABLE,
            NoxsDockerCompat.State.INSTALL_FAILED
        ).forEach { state ->
            assertEquals(state, NoxsDockerCompat.parseState("state=${state.key}\n").state)
        }
    }

    @Test fun `summary lines state the daemon and bridge honestly`() {
        val lines = NoxsDockerCompat.summaryLines(
            daemonAvailable = true,
            networkingAvailable = false,
            storageDriver = "vfs",
            containersSupported = false
        )
        assertTrue(lines.contains("Docker daemon: available"))
        assertTrue(lines.contains("Docker bridge networking: unavailable"))
        assertTrue(lines.contains("Docker storage: available (vfs)"))
        assertTrue(lines.any { it.contains("Container execution: unsupported") })
    }

    @Test fun `check row marks limitations as warnings`() {
        assertEquals("[✓]", NoxsDockerCompat.CheckRow("a", true).mark())
        assertEquals("[✗]", NoxsDockerCompat.CheckRow("a", false).mark())
        assertEquals("[!]", NoxsDockerCompat.CheckRow("a", false, limited = true).mark())
        assertEquals("[!]", NoxsDockerCompat.CheckRow("a", null).mark())
    }
}
