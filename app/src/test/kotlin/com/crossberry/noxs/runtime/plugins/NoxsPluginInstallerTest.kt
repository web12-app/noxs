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

    /** Fixture mirroring the Code plugin: terminal permission + bin/code. */
    private fun codeTarBytes(version: String = "1.0.0", withBin: Boolean = true): ByteArray {
        val pluginJson = """
            {"id":"code","name":"Code","version":"$version",
             "description":"Open files with Spck Editor","main":"plugin.js",
             "permissions":["ui","terminal","network"],"minimumNoxsVersion":"0.12.0"}
        """.trimIndent().toByteArray()
        val entries = mutableListOf(
            tarEntry("plugin.js", "globalThis.__NOXS_PLUGIN__={};".toByteArray()),
            tarEntry("plugin.json", pluginJson),
            tarEntry("README.md", "# Code".toByteArray()),
            tarEntry("icon.svg", "<svg/>".toByteArray())
        )
        if (withBin) {
            entries += tarEntry("bin/code", "#!/bin/sh\necho from-spck\n".toByteArray())
        }
        return gzip(*entries.toTypedArray())
    }

    private fun codeEntry(version: String = "1.0.0") = RegistryEntry(
        id = "code", name = "Code", version = version,
        description = "Open files with Spck Editor", logo = "icon.svg", readme = "README.md",
        logoUrl = null, readmeUrl = null, release = version,
        permissions = listOf("ui", "terminal", "network"), category = "Developer Tools",
        keywords = emptyList(), minimumNoxsVersion = "0.12.0",
        artifact = "https://artifacts.invalid/code.noxs-plugin", checksum = null,
        releaseTag = null, updatedAt = null
    )

    private fun installer(
        vararg artifacts: ByteArray,
        binDir: File? = null,
        appVersion: String = "0.11.0"
    ): Pair<NoxsPluginInstaller, File> {
        val home = tmp.newFolder()
        val root = File(home, ".noxs/plugins")
        val payloads = if (artifacts.isEmpty()) arrayOf(fakeTarBytes()) else artifacts
        return NoxsPluginInstaller(root, appVersion, FakeFetcher(*payloads), binDir) to root
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

    // ------------------------------------------------- guest command shims

    @Test
    fun `terminal plugin installs its bin script as a guest command`() {
        val bin = tmp.newFolder("guest-bin")
        val (installer, root) = installer(codeTarBytes(), binDir = bin, appVersion = "0.12.0")
        installer.install(codeEntry())
        val shim = File(bin, "code")
        assertTrue(shim.isFile)
        assertTrue(shim.canExecute())
        val text = shim.readText()
        assertTrue(text.startsWith("#!/bin/sh"))
        assertTrue(text.contains("# Noxs plugin command shim (code)"))
        assertEquals(
            listOf("code"),
            File(root, "code/.bin-manifest").readLines().filter { it.isNotBlank() }
        )
    }

    @Test
    fun `bin scripts are skipped without the terminal permission`() {
        val bin = tmp.newFolder("guest-bin-ui")
        // The INSTALLED plugin.json is the permission source of truth — the
        // package itself must declare no terminal permission for the gate to
        // apply (the registry entry cannot revoke packaged grants).
        val tar = gzip(
            tarEntry("plugin.js", "x".toByteArray()),
            tarEntry(
                "plugin.json",
                """{"id":"code","name":"Code","version":"1.0.0","description":"d","main":"plugin.js","permissions":["ui"],"minimumNoxsVersion":"0.12.0"}""".toByteArray()
            ),
            tarEntry("README.md", "# Code".toByteArray()),
            tarEntry("bin/code", "#!/bin/sh\necho hi\n".toByteArray())
        )
        val (installer, _) = installer(tar, binDir = bin, appVersion = "0.12.0")
        installer.install(codeEntry().copy(permissions = listOf("ui")))
        assertFalse(File(bin, "code").exists())
    }

    @Test
    fun `bin script without shebang is not installed`() {
        val bin = tmp.newFolder("guest-bin-noshebang")
        val tar = gzip(
            tarEntry("plugin.js", "x".toByteArray()),
            tarEntry(
                "plugin.json",
                """{"id":"code","name":"Code","version":"1.0.0","description":"d","main":"plugin.js","permissions":["ui","terminal"],"minimumNoxsVersion":"0.12.0"}""".toByteArray()
            ),
            tarEntry("README.md", "# Code".toByteArray()),
            tarEntry("bin/code", "echo no shebang\n".toByteArray())
        )
        val (installer, _) = installer(tar, binDir = bin, appVersion = "0.12.0")
        installer.install(codeEntry())
        assertFalse(File(bin, "code").exists())
    }

    @Test
    fun `uninstall removes the guest command`() {
        val bin = tmp.newFolder("guest-bin-rm")
        val (installer, _) = installer(codeTarBytes(), binDir = bin, appVersion = "0.12.0")
        installer.install(codeEntry())
        assertTrue(File(bin, "code").isFile)
        installer.uninstall("code")
        assertFalse(File(bin, "code").exists())
    }

    @Test
    fun `disable removes and enable restores guest commands`() {
        val bin = tmp.newFolder("guest-bin-toggle")
        val (installer, _) = installer(codeTarBytes(), binDir = bin, appVersion = "0.12.0")
        installer.install(codeEntry())
        installer.disable("code")
        assertFalse(File(bin, "code").exists())
        installer.enable("code")
        assertTrue(File(bin, "code").isFile)
    }

    @Test
    fun `guest commands never clobber foreign files`() {
        val bin = tmp.newFolder("guest-bin-foreign")
        val foreign = "#!/bin/sh\necho mine\n"
        File(bin, "code").writeText(foreign)
        val (installer, root) = installer(codeTarBytes(), binDir = bin, appVersion = "0.12.0")
        installer.install(codeEntry())
        assertEquals(foreign, File(bin, "code").readText())
        // A skipped command is not tracked as installed either.
        assertFalse(File(root, "code/.bin-manifest").exists())
    }

    @Test
    fun `update refreshes the guest command set`() {
        val bin = tmp.newFolder("guest-bin-update")
        val (installer, _) = installer(
            codeTarBytes("1.0.0"), codeTarBytes("1.1.0", withBin = false),
            binDir = bin, appVersion = "0.12.0"
        )
        installer.install(codeEntry("1.0.0"))
        assertTrue(File(bin, "code").isFile)
        installer.update(codeEntry("1.1.0"), installer.installed("code")!!)
        assertFalse(File(bin, "code").exists())
    }

    // ------------------------------------------------ Noxs Plugin SDK gates

    /** Plugin tar whose packaged plugin.json declares [sdkVersion]. */
    private fun sdkTarBytes(version: String = "1.0.0", sdkVersion: String): ByteArray {
        val pluginJson = """
            {"id":"hello","name":"Hello","version":"$version",
             "description":"Example Noxs plugin","main":"plugin.js",
             "permissions":["ui"],"minimumNoxsVersion":"0.11.0",
             "sdkVersion":"$sdkVersion"}
        """.trimIndent().toByteArray()
        return gzip(
            tarEntry("plugin.js", "globalThis.__NOXS_PLUGIN__={};".toByteArray()),
            tarEntry("plugin.json", pluginJson),
            tarEntry("README.md", "# Hello".toByteArray()),
            tarEntry("icon.svg", "<svg/>".toByteArray())
        )
    }

    @Test
    fun `a plugin requiring a newer sdk is refused before download`() {
        val (installer, root) = installer(appVersion = "0.12.0")
        val error = try {
            installer.install(entry().copy(minimumSdkVersion = "0.0.2"))
            null
        } catch (e: NoxsPluginInstaller.InstallException) {
            e
        }
        assertNotNull(error)
        assertTrue(error!!.message!!.contains("Update Noxs"))
        assertFalse(File(root, "hello").exists())
    }

    @Test
    fun `contradictory sdk metadata is refused as invalid`() {
        val (installer, _) = installer(appVersion = "0.13.0")
        val error = try {
            installer.install(entry().copy(minimumSdkVersion = "0.1.0"))
            null
        } catch (e: NoxsPluginInstaller.InstallException) {
            e
        }
        assertNotNull(error)
        assertEquals("Registry metadata for this plugin is invalid", error!!.message)
    }

    @Test
    fun `sdk fields must match between registry and package`() {
        // Registry says SDK 0.0.2, the artifact was built for 0.0.1.
        val (installer, root) = installer(sdkTarBytes(sdkVersion = "0.0.1"), appVersion = "0.13.0")
        val error = try {
            installer.install(entry().copy(sdkVersion = "0.0.2"))
            null
        } catch (e: NoxsPluginInstaller.InstallException) {
            e
        }
        assertNotNull(error)
        assertEquals("The plugin package does not match its registry entry", error!!.message)
        assertFalse(File(root, "hello").exists())
    }

    @Test
    fun `a package declaring its sdk version installs cleanly`() {
        val (installer, _) = installer(sdkTarBytes(sdkVersion = "0.0.1"), appVersion = "0.13.0")
        val installed = installer.install(entry().copy(sdkVersion = "0.0.1"))
        assertEquals("0.0.1", installed.meta.sdkVersion)
        assertEquals(listOf("hello"), installer.listInstalled().map { it.meta.id })
    }

    @Test
    fun `hasInvalidRecord surfaces broken installs for the store`() {
        val (installer, root) = installer(appVersion = "0.13.0")
        assertFalse(installer.hasInvalidRecord("hello"))
        installer.install(entry())
        assertFalse(installer.hasInvalidRecord("hello"))
        // Tamper with the installed metadata — listInstalled hides it, but
        // the store must see the plugin as BLOCKED, not uninstalled.
        File(root, "hello/plugin.json").writeText("{ broken", Charsets.UTF_8)
        assertTrue(installer.listInstalled().isEmpty())
        assertTrue(installer.hasInvalidRecord("hello"))
        assertFalse(installer.hasInvalidRecord("missing"))
    }
}
