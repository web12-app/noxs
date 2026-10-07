/*
 * Noxs — original implementation.
 * JVM tests for NoxsPluginBridge: request parsing, response writing, id
 * validation on every mutation path, snapshot format and stale-file hygiene.
 */
package com.crossberry.noxs.runtime.plugins

import com.crossberry.noxs.runtime.NoxsPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NoxsPluginBridgeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newBridge(): Pair<NoxsPluginBridge, NoxsPaths> {
        val paths = NoxsPaths(tmp.newFolder())
        val bridge = NoxsPluginBridge(paths)
        bridge.ensureControlDirectories()
        return bridge to paths
    }

    private fun NoxsPaths.request(requestId: String, content: String) {
        File(pluginRequests, requestId).writeText(content, Charsets.UTF_8)
    }

    private fun NoxsPaths.response(requestId: String): String =
        File(pluginResponses, requestId).readText(Charsets.UTF_8)

    @Test
    fun `install request reaches the hook and responds OK`() {
        val (bridge, paths) = newBridge()
        val seen = mutableListOf<String>()
        bridge.onInstall = { _, pluginId -> seen.add(pluginId); true to "done" }
        paths.request("req-1", "install\nhello\n")
        assertEquals(1, bridge.processPendingRequests())
        assertEquals(listOf("hello"), seen)
        assertEquals("OK\ndone\n", paths.response("req-1"))
    }

    @Test
    fun `invalid plugin id is refused without touching the hook`() {
        val (bridge, paths) = newBridge()
        var called = false
        bridge.onInstall = { _, _ -> called = true; true to "done" }
        paths.request("req-2", "install\n../evil\n")
        bridge.processPendingRequests()
        assertFalse(called)
        assertEquals("ERR\nInvalid plugin id\n", paths.response("req-2"))
    }

    @Test
    fun `open request invokes the store hook`() {
        val (bridge, paths) = newBridge()
        var opened = false
        bridge.onOpenStore = { opened = true }
        paths.request("req-3", "open\n\n")
        bridge.processPendingRequests()
        assertTrue(opened)
        assertEquals("OK\nstore opened\n", paths.response("req-3"))
    }

    @Test
    fun `hook failures become stable error responses`() {
        val (bridge, paths) = newBridge()
        bridge.onUninstall = { _, _ -> throw IllegalStateException("internal path leak") }
        paths.request("req-4", "uninstall\nhello\n")
        bridge.processPendingRequests()
        assertEquals("ERR\nThe request could not be completed\n", paths.response("req-4"))
    }

    @Test
    fun `unsupported operation is rejected`() {
        val (bridge, paths) = newBridge()
        paths.request("req-5", "launch-missiles\n\n")
        bridge.processPendingRequests()
        assertEquals("ERR\nUnsupported plugin request\n", paths.response("req-5"))
    }

    @Test
    fun `request with malformed id is deleted without response`() {
        val (bridge, paths) = newBridge()
        File(paths.pluginRequests, "not a valid id!").writeText("open\n\n", Charsets.UTF_8)
        assertEquals(0, bridge.processPendingRequests())
        // The request file is gone, no response file appeared.
        assertTrue(paths.pluginRequests.listFiles().isNullOrEmpty())
        assertTrue(paths.pluginResponses.listFiles().isNullOrEmpty())
    }

    @Test
    fun `symlinked request is deleted`() {
        val (bridge, paths) = newBridge()
        val outside = File(tmp.newFolder(), "outside")
        outside.writeText("open\n\n", Charsets.UTF_8)
        val link = File(paths.pluginRequests, "req-6")
        java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertEquals(0, bridge.processPendingRequests())
        // The symlink itself is deleted; the outside target file is untouched.
        assertFalse(link.exists())
        assertTrue(outside.isFile)
    }

    @Test
    fun `snapshots write catalog and installed files`() {
        val (bridge, paths) = newBridge()
        val card = NoxsPluginManager.StoreCard(
            entry = RegistryEntry(
                id = "hello", name = "Hello", version = "1.0.0",
                description = "Example Noxs plugin", logo = null, readme = null,
                logoUrl = null, readmeUrl = null, release = "1.0.0",
                permissions = listOf("ui"), category = "Utilities",
                keywords = listOf("demo", "starter"), minimumNoxsVersion = null,
                artifact = null, checksum = null, releaseTag = null, updatedAt = null
            ),
            installed = null,
            updateAvailable = false
        )
        bridge.writeSnapshots(listOf(card), emptyList())
        val catalog = paths.pluginCatalogSnapshot.readText(Charsets.UTF_8)
        assertTrue(catalog.startsWith("hello\tHello\t1.0.0\tUtilities\tdemo,starter\tExample Noxs plugin\tavailable\t-"))
        assertTrue(catalog.endsWith("\n"))

        val meta = PluginJson.parseMeta(
            """{"id":"hello","name":"Hello","version":"1.0.0","description":"Example Noxs plugin","main":"plugin.js","permissions":["ui"],"minimumNoxsVersion":"0.11.0"}"""
        )
        val installedPlugin = InstalledPlugin(
            dir = File(paths.rootfsHomeNoxs, ".noxs/plugins/hello"),
            meta = meta,
            enabled = false
        )
        bridge.writeSnapshots(listOf(card), listOf(installedPlugin))
        val installedText = paths.pluginInstalledSnapshot.readText(Charsets.UTF_8)
        assertEquals("hello\tHello\t1.0.0\tdisabled\n", installedText)
    }

    @Test
    fun `cleanupStale removes old transfers`() {
        val (bridge, paths) = newBridge()
        val old = File(paths.pluginRequests, "req-old")
        old.writeText("open\n\n", Charsets.UTF_8)
        old.setLastModified(System.currentTimeMillis() - 7L * 60L * 60L * 1000L)
        bridge.cleanupStale()
        assertFalse(old.exists())
    }
}
