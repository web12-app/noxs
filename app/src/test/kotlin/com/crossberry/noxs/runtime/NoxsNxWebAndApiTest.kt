package com.crossberry.noxs.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Noxs native browser bridge (`nx ow`) + @noxs/nx-api SDK installation
 * tests (Noxs platform spec §15-§20, §3-§14).
 */
class NoxsNxWebAndApiTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private fun install(): NoxsPaths {
        val paths = NoxsPaths(temporary.newFolder("rootfs"))
        RootfsConfigurator.ensureNxPackageSystem(paths)
        return paths
    }

    private fun installed(paths: NoxsPaths, rel: String): String =
        File(paths.rootfs, rel).readText(Charsets.UTF_8)

    // ------------------------------------------------------------- nx ow

    @Test fun `web bridge module is installed and executable`() {
        val paths = install()
        val webLib = File(paths.rootfs, "usr/local/lib/noxs-pkg/web-lib.sh")
        assertTrue(webLib.isFile)
        assertTrue(webLib.canExecute())
    }

    @Test fun `nx dispatcher wires the ow command`() {
        val paths = install()
        val nx = installed(paths, "usr/local/bin/nx")
        assertTrue("ow) case missing", nx.contains("ow)"))
        assertTrue(nx.contains("web-lib.sh"))
        assertTrue(nx.contains("nx_ow_cmd"))
    }

    @Test fun `web bridge accepts only http and https`() {
        val paths = install()
        val webLib = installed(paths, "usr/local/lib/noxs-pkg/web-lib.sh")
        assertTrue(webLib.contains("http://*|https://*"))
        assertFalse("no section sign may leak", webLib.contains('\u00a7'))
    }

    @Test fun `web bridge writes requests without shell evaluation`() {
        val paths = install()
        val webLib = installed(paths, "usr/local/lib/noxs-pkg/web-lib.sh")
        // The URL must be printf'd, never evaluated or interpolated into a command.
        assertTrue(webLib.contains("printf 'open\\n%s\\n' \"\$url\""))
        // Control characters are rejected before anything is sent.
        assertTrue(webLib.contains("nx_url_ok"))
    }

    // ----------------------------------------------------------- nx-api

    @Test fun `nx-api SDK is installed with package metadata`() {
        val paths = install()
        val apiDir = File(paths.rootfs, "usr/local/lib/noxs/nx-api")
        assertTrue(File(apiDir, "nx-api.js").isFile)
        assertTrue(File(apiDir, "package.json").isFile)
        assertTrue(File(apiDir, "README.md").isFile)
    }

    @Test fun `nx-api exposes only permission-controlled modules`() {
        val paths = install()
        val api = installed(paths, "usr/local/lib/noxs/nx-api/nx-api.js")
        for (module in listOf("window", "web", "terminal", "logs", "events", "package", "permissions", "system", "storage")) {
            assertTrue("module missing: $module", api.contains("$module:"))
        }
        // Every call goes through the validated bridge envelope.
        assertTrue(api.contains("NoxsBridge"))
        assertTrue(api.contains("packageId"))
        assertTrue(api.contains("requestId"))
        assertFalse(api.contains('\u00a7'))
        // The SDK surfaces provider-agnostic error codes from responses.
        assertTrue(api.contains("error.code"))
    }

    @Test fun `package json keeps the official name and attribution`() {
        val paths = install()
        val packageJson = installed(paths, "usr/local/lib/noxs/nx-api/package.json")
        assertTrue(packageJson.contains("\"@noxs/nx-api\""))
        assertTrue(packageJson.contains("Crossberry"))
        assertTrue(packageJson.contains("web12-app"))
    }

    // ---------------------------------------------------------- web guard

    @Test fun `android side url guard and web bridge agree with the guest policy`() {
        // Guest accepts http/https only; NoxsUrlGuard is the same policy.
        assertEquals(
            NoxsUrlGuard.check("https://example.com") is NoxsUrlGuard.Decision.Allowed,
            true
        )
        assertEquals(
            NoxsUrlGuard.check("javascript:alert(1)") is NoxsUrlGuard.Decision.Rejected,
            true
        )
    }

    @Test fun `web window bridge validates requests before opening`() {
        val paths = NoxsPaths(temporary.newFolder("bridge"))
        paths.ensureBaseDirs()
        val opened = mutableListOf<Pair<String, String>>()
        val bridge = NoxsWebWindowBridge(paths)
        bridge.onOpenWebWindow = { id, url -> opened.add(id to url) }

        fun request(id: String, operation: String, url: String) {
            File(paths.webRequests, id).writeText("$operation\n$url\n", Charsets.UTF_8)
        }

        request("r1", "open", "https://example.com")
        request("r2", "open", "javascript:alert(1)")
        request("r3", "delete", "https://example.com")
        // Only the one valid open is counted as handled; the rest are answered.
        assertEquals(1, bridge.processPendingRequests())

        assertEquals(1, opened.size)
        assertEquals("https://example.com", opened[0].second)
        // Guest receives an OK, a generic rejection and a rejection.
        assertTrue(File(paths.webResponses, "r1").readText().startsWith("OK"))
        assertTrue(File(paths.webResponses, "r2").readText().startsWith("ERR"))
        assertTrue(File(paths.webResponses, "r3").readText().startsWith("ERR"))
        // Requests are consumed.
        assertEquals(0, paths.webRequests.listFiles()?.size ?: -1)
    }

    @Test fun `web window bridge rejects symlinked request files`() {
        val paths = NoxsPaths(temporary.newFolder("bridge2"))
        paths.ensureBaseDirs()
        val bridge = NoxsWebWindowBridge(paths)
        val target = temporary.newFile("outside")
        val link = File(paths.webRequests, "evil")
        java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath())
        assertEquals(0, bridge.processPendingRequests())
        assertFalse(link.exists())
    }
}
