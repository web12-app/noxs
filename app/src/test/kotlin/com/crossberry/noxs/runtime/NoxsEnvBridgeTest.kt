/*
 * Noxs — original implementation.
 * JVM tests for NoxsEnvBridge: snapshot format, request parsing, response
 * writing, password handling on rejection paths, and stale-file hygiene.
 */
package com.crossberry.noxs.runtime

import com.crossberry.noxs.environments.model.Environment
import com.crossberry.noxs.environments.model.EnvironmentStatus
import com.crossberry.noxs.environments.model.SetupState
import com.crossberry.noxs.environments.model.SetupTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NoxsEnvBridgeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class Hooks {
        var installs: MutableList<Triple<String, String, CharArray?>> = mutableListOf()
        var removes: MutableList<String> = mutableListOf()
        var uses: MutableList<String> = mutableListOf()
    }

    private fun newBridge(): Pair<NoxsEnvBridge, NoxsPaths> {
        val paths = NoxsPaths(tmp.newFolder())
        val bridge = NoxsEnvBridge(paths)
        bridge.ensureControlDirectories()
        return bridge to paths
    }

    private fun env(id: String, status: EnvironmentStatus = EnvironmentStatus.READY) = Environment(
        id = id,
        providerId = id,
        displayName = id.replaceFirstChar { it.uppercase() },
        version = "",
        architecture = "arm64-v8a",
        variant = "v1",
        status = status,
        storagePath = "/data/environments/$id",
        createdAt = 1L,
        updatedAt = 1L
    )

    private fun task(envId: String, state: SetupState, progress: Int, op: String) = SetupTask(
        id = "setup-$envId-1",
        environmentId = envId,
        title = "$envId setup",
        state = state,
        progress = progress,
        currentOperation = op,
        startedAt = 2L,
        updatedAt = 2L
    )

    // ---------------------------------------------------------------- snapshots

    @Test
    fun `snapshot writes registry and provider lines`() {
        val (bridge, paths) = newBridge()
        bridge.writeSnapshot(
            listOf(env("debian"), env("ubuntu", EnvironmentStatus.INSTALLING)),
            "debian",
            listOf(task("ubuntu", SetupState.DOWNLOADING, 42, "Downloading")),
            emptyList()
        )
        val registry = paths.envSnapshot.readText()
        assertTrue("active ready row", registry.contains("debian\tDebian\tready\tactive\t\t-1\t"))
        assertTrue("installing row with progress", registry.contains("ubuntu\tUbuntu\tinstalling\t-\tdownloading\t42\tDownloading"))
        assertEquals("\n", paths.envProvidersSnapshot.readText())
    }

    @Test
    fun `snapshot sanitizes tab and newline characters in fields`() {
        val (bridge, paths) = newBridge()
        bridge.writeSnapshot(
            listOf(env("debian")),
            null,
            listOf(task("debian", SetupState.DOWNLOADING, 10, "bad\t\top\r\nnext")),
            emptyList()
        )
        val line = paths.envSnapshot.readText().trim()
        assertTrue("op sanitized", line.endsWith("bad  op next"))
        assertEquals("exactly 7 columns", 7, line.split("\t").size)
    }

    @Test
    fun `snapshot keeps the newest task per environment`() {
        val (bridge, paths) = newBridge()
        bridge.writeSnapshot(
            listOf(env("ubuntu", EnvironmentStatus.INSTALLING)),
            null,
            listOf(
                task("ubuntu", SetupState.DOWNLOADING, 90, "newest"),
                task("ubuntu", SetupState.CHECKING, 5, "oldest")
            ),
            emptyList()
        )
        assertTrue(paths.envSnapshot.readText().contains("90\tnewest"))
    }

    // ---------------------------------------------------------------- requests

    private fun putRequest(paths: NoxsPaths, id: String, vararg lines: String) {
        File(paths.envRequests, id).writeText(lines.joinToString("") { "$it\n" })
    }

    @Test
    fun `install request reaches the hook with the password and answers OK`() {
        val (bridge, paths) = newBridge()
        val hooks = Hooks()
        bridge.onInstall = { _, providerId, variantId, password ->
            hooks.installs += Triple(providerId, variantId, password)
            true to providerId
        }
        putRequest(paths, "r1", "install", "ubuntu", "noble", "secret1")
        assertEquals(1, bridge.processPendingRequests())
        assertEquals(1, hooks.installs.size)
        assertEquals("ubuntu", hooks.installs[0].first)
        assertEquals("noble", hooks.installs[0].second)
        assertEquals("secret1", hooks.installs[0].third?.concatToString())
        assertEquals("OK\nubuntu\n", File(paths.envResponses, "r1").readText())
        assertFalse("request consumed", File(paths.envRequests, "r1").exists())
    }

    @Test
    fun `failed install hook produces an ERR response`() {
        val (bridge, paths) = newBridge()
        var passwordSeen: CharArray? = null
        bridge.onInstall = { _, _, _, password ->
            passwordSeen = password
            false to "Unknown environment provider: ghost"
        }
        putRequest(paths, "r1", "install", "ghost", "", "pw")
        assertEquals(1, bridge.processPendingRequests())
        assertEquals("ERR\nUnknown environment provider: ghost\n", File(paths.envResponses, "r1").readText())
        // Rejection path zeroes the password array (never left in memory).
        assertTrue(passwordSeen?.all { it == '\u0000' } == true)
    }

    @Test
    fun `remove and use requests route to their hooks`() {
        val (bridge, paths) = newBridge()
        val hooks = Hooks()
        bridge.onRemove = { _, id -> hooks.removes += id; true to "removed" }
        bridge.onUse = { _, id -> hooks.uses += id; false to "'kali' is not ready (status: installing)" }
        putRequest(paths, "a", "remove", "ubuntu")
        putRequest(paths, "b", "use", "kali")
        assertEquals(2, bridge.processPendingRequests())
        assertEquals(listOf("ubuntu"), hooks.removes)
        assertEquals(listOf("kali"), hooks.uses)
        assertEquals("OK\nremoved\n", File(paths.envResponses, "a").readText())
        assertEquals("ERR\n'kali' is not ready (status: installing)\n", File(paths.envResponses, "b").readText())
    }

    @Test
    fun `unknown operation answers ERR without a hook call`() {
        val (bridge, paths) = newBridge()
        putRequest(paths, "x", "reboot", "now")
        assertEquals(0, bridge.processPendingRequests())
        assertEquals("ERR\nUnsupported environment request\n", File(paths.envResponses, "x").readText())
    }

    @Test
    fun `symlinked request files are deleted and never processed`() {
        val (bridge, paths) = newBridge()
        var called = false
        bridge.onUse = { _, _ -> called = true; true to "ok" }
        val outside = File(paths.base, "outside.txt").apply { writeText("use\ndebian\n") }
        java.nio.file.Files.createSymbolicLink(
            File(paths.envRequests, "sneaky").toPath(), outside.toPath()
        )
        assertEquals(0, bridge.processPendingRequests())
        assertFalse(File(paths.envRequests, "sneaky").exists())
        assertFalse(called)
    }

    @Test
    fun `stale responses are cleaned up`() {
        val (bridge, paths) = newBridge()
        val stale = File(paths.envResponses, "old").apply { writeText("OK\n") }
        stale.setLastModified(System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000)
        bridge.processPendingRequests()
        assertFalse("stale response removed", stale.exists())
    }
}
