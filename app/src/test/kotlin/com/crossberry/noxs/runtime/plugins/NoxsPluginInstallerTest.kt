/*
 * Noxs — original implementation.
 * JVM tests for NoxsPluginInstaller: safe extraction of .noxs-plugin
 * archives (gzipped tar), checksum verification, staged validation,
 * enable/disable lifecycle and update detection. No network: artifacts and
 * registry fetches are faked.
 */
package com.crossberry.noxs.runtime.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

class NoxsPluginInstallerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ----------------------------------------------------- tar.gz fixtures

    /** Minimal ustar header builder (same approach as RootfsExtractorTest). */
    private fun tarHeader(name: String, size: Long, typeFlag: Byte, mode: Int = 420 /* octal 0644 */): ByteArray {
        val header = ByteArray(512)
        fun put(s: String, off: Int, len: Int) {
            val bytes = s.toByteArray(Charsets.US_ASCII)
            System.arraycopy(bytes, 0, header, off, minOf(bytes.size, len))
        }
        fun octal(v: Long, off: Int, len: Int) {
            val text = java.lang.Long.toOctalString(v)
            val padded = "0".repeat(len - 1 - text.length) + text + "\u0000"
            System.arraycopy(padded.toByteArray(Charsets.US_ASCII), 0, header, off, len)
        }
        put(name, 0, 100)
        octal(mode.toLong(), 100, 8)
        octal(0L, 108, 8)          // uid
        octal(0L, 116, 8)          // gid
        octal(size, 124, 12)
        octal(0L, 136, 12)         // mtime
        header[156] = typeFlag
        // Checksum: spaces while computing, then the octal value.
        for (i in 148 until 156) header[i] = ' '.code.toByte()
        var checksum = 0L
        for (b in header) checksum += b.toLong() and 0xFFL
        val checksumText = java.lang.Long.toOctalString(checksum)
        val paddedChecksum = "0".repeat(6 - checksumText.length) + checksumText + "\u0000 "
        System.arraycopy(paddedChecksum.toByteArray(Charsets.US_ASCII), 0, header, 148, 8)
        return header
    }

    private fun tarEntry(name: String, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tarHeader(name, content.size.toLong(), '0'.code.toByte()))
        out.write(content)
        val padding = (512 - (content.size % 512)) % 512
        out.write(ByteArray(padding))
        return out.toByteArray()
    }

    private fun gzip(vararg entries: ByteArray): ByteArray {
        val tar = ByteArrayOutputStream()
        entries.forEach { tar.write(it) }
        tar.write(ByteArray(1024)) // end-of-archive blocks
        val gz = ByteArrayOutputStream()
        GZIPOutputStream(gz).use { it.write(tar.toByteArray()) }
        return gz.toByteArray()
    }

    private fun fakeTarBytes(version: String = "1.0.0"): ByteArray {
        val pluginJson = """
            {"id":"hello","name":"Hello","version":"$version",
             "description":"Example Noxs plugin","main":"plugin.js",
             "permissions":["ui"],"minimumNoxsVersion":"0.11.0"}
        """.trimIndent().toByteArray()
        return gzip(
            tarEntry("plugin.js", "globalThis.__NOXS_PLUGIN__={};".toByteArray()),
            tarEntry("plugin.json", pluginJson),
            tarEntry("README.md", "# Hello".toByteArray()),
            tarEntry("icon.svg", "<svg/>".toByteArray())
        )
    }

    private fun entry(checksum: String? = null, artifact: String = "https://artifacts.invalid/hello.noxs-plugin") =
        RegistryEntry(
            id = "hello", name = "Hello", version = "1.0.0",
            description = "Example Noxs plugin", logo = "icon.svg", readme = "README.md",
            logoUrl = null, readmeUrl = null, release = "1.0.0",
            permissions = listOf("ui"), category = "Utilities", keywords = emptyList(),
            minimumNoxsVersion = "0.11.0", artifact = artifact, checksum = checksum,
            releaseTag = null, updatedAt = null
        )

    /** Serves queued payloads in order (one per fetch). */
    private class FakeFetcher(vararg payloads: ByteArray) : NoxsPluginRegistry.Fetcher {
        private val queue = ArrayDeque(payloads.toList())
        override fun get(url: String, timeoutMs: Int): ByteArray {
            if (!url.startsWith("https://")) throw AssertionError("non-https fetch attempted")
            return queue.removeFirstOrNull() ?: throw AssertionError("unexpected extra fetch")
        }
    }

    private fun installer(
        vararg artifacts: ByteArray
    ): Pair<NoxsPluginInstaller, File> {
        val home = tmp.newFolder()
        val root = File(home, ".noxs/plugins")
        val payloads = if (artifacts.isEmpty()) arrayOf(fakeTarBytes()) else artifacts
        return NoxsPluginInstaller(root, "0.11.0", FakeFetcher(*payloads)) to root
    }

    // --------------------------------------------------------------- tests

    @Test
    fun `installs a valid artifact and registers it`() {
        val (installer, root) = installer()
        val installed = installer.install(entry())
        assertEquals("hello", installed.meta.id)
        assertTrue(installed.enabled)
        assertTrue(File(root, "hello/plugin.js").isFile)
        assertTrue(File(root, "hello/plugin.json").isFile)
        assertTrue(File(root, "hello/README.md").isFile)
        assertTrue(File(root, "hello/icon.svg").isFile)
        // Listing sees exactly the installed plugin.
        assertEquals(listOf("hello"), installer.listInstalled().map { it.meta.id })
    }

    @Test
    fun `wrong checksum aborts the install`() {
        val (installer, root) = installer()
        val bad = try {
            installer.install(entry(checksum = "deadbeef"))
            null
        } catch (e: NoxsPluginInstaller.InstallException) {
            e
        }
        assertNotNull(bad)
        assertEquals("The plugin package failed its integrity check", bad!!.message)
        assertFalse(File(root, "hello").exists())
    }

    @Test
    fun `correct checksum installs`() {
        val (installer, _) = installer()
        val checksum = PluginChecksum.sha256(fakeTarBytes())
        installer.install(entry(checksum = checksum))
        assertEquals(listOf("hello"), installer.listInstalled().map { it.meta.id })
    }

    @Test
    fun `version mismatch between archive and registry is refused`() {
        // The registry says 1.2.0 but the packaged plugin.json says 1.0.0.
        val (installer, root) = installer(fakeTarBytes(version = "1.0.0"))
        val error = try {
            installer.install(entry(checksum = null).copy(version = "1.2.0", artifact = "https://a/x"))
            null
        } catch (e: NoxsPluginInstaller.InstallException) {
            e
        }
        assertNotNull(error)
        assertFalse(File(root, "hello").exists())
    }

    @Test
    fun `traversal entries are rejected`() {
        val evil = gzip(
            tarEntry("plugin.js", "x".toByteArray()),
            tarEntry("../escape.js", "x".toByteArray()),
            tarEntry(
                "plugin.json",
                """{"id":"hello","name":"Hello","version":"1.0.0","description":"d","main":"plugin.js","permissions":["ui"],"minimumNoxsVersion":"0.11.0"}""".toByteArray()
            )
        )
        val (installer, root) = installer(evil)
        val error = try {
            installer.install(entry())
            null
        } catch (e: NoxsPluginInstaller.InstallException) {
            e
        }
        assertNotNull(error)
        // Nothing escaped the plugin root and nothing was installed.
        assertFalse(File(root.parentFile, "escape.js").exists())
        assertFalse(File(root, "hello").exists())
    }

    @Test
    fun `metadata mismatch between archive and registry is refused`() {
        val other = gzip(
            tarEntry("plugin.js", "x".toByteArray()),
            tarEntry(
                "plugin.json",
                """{"id":"impostor","name":"Impostor","version":"1.0.0","description":"d","main":"plugin.js","permissions":["ui"],"minimumNoxsVersion":"0.11.0"}""".toByteArray()
            )
        )
        val (installer, _) = installer(other)
        val error = try {
            installer.install(entry())
            null
        } catch (e: NoxsPluginInstaller.InstallException) {
            e
        }
        assertEquals("The plugin package does not match its registry entry", error!!.message)
    }

    @Test
    fun `incompatible minimum noxs version is refused before download`() {
        val (installer, _) = installer()
        val error = try {
            installer.install(entry().copy(minimumNoxsVersion = "9.9.9"))
            null
        } catch (e: NoxsPluginInstaller.InstallException) {
            e
        }
        assertNotNull(error)
        assertTrue(error!!.message!!.contains("9.9.9"))
    }

    @Test
    fun `missing artifact url is refused`() {
        val (installer, _) = installer()
        val error = try {
            installer.install(entry(artifact = ""))
            null
        } catch (e: NoxsPluginInstaller.InstallException) {
            e
        }
        assertNotNull(error)
    }

    @Test
    fun `uninstall removes the plugin directory`() {
        val (installer, root) = installer()
        installer.install(entry())
        installer.uninstall("hello")
        assertFalse(File(root, "hello").exists())
        assertNull(installer.installed("hello"))
        val error = try {
            installer.uninstall("hello")
            null
        } catch (e: NoxsPluginInstaller.InstallException) {
            e
        }
        assertNotNull(error)
    }

    @Test
    fun `disable and enable toggle the marker`() {
        val (installer, root) = installer()
        installer.install(entry())
        installer.disable("hello")
        assertFalse(installer.installed("hello")!!.enabled)
        assertTrue(File(root, "hello/.disabled").isFile)
        installer.enable("hello")
        assertTrue(installer.installed("hello")!!.enabled)
        assertFalse(File(root, "hello/.disabled").exists())
    }

    @Test
    fun `update detection and preserved disabled state`() {
        // First fetch serves the 1.0.0 artifact, the update fetch the 1.1.0 one.
        val (installer, _) = installer(fakeTarBytes("1.0.0"), fakeTarBytes("1.1.0"))
        installer.install(entry())
        val current = installer.installed("hello")!!
        val same = entry().copy(version = "1.0.0")
        val newer = entry().copy(version = "1.1.0")
        assertFalse(installer.updateAvailable(same, current))
        assertTrue(installer.updateAvailable(newer, current))

        installer.disable("hello")
        installer.update(newer, installer.installed("hello")!!)
        val updated = installer.installed("hello")!!
        assertEquals("1.1.0", updated.meta.version)
        assertFalse(updated.enabled)
    }

    @Test
    fun `invalid plugin id is refused everywhere`() {
        val (installer, _) = installer()
        val error = try {
            installer.uninstall("../evil")
            null
        } catch (e: NoxsPluginInstaller.InstallException) {
            e
        }
        assertNotNull(error)
    }
}
