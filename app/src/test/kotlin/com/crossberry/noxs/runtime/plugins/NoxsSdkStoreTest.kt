/*
 * Noxs — original implementation.
 * JVM tests for NoxsSdkStore: SDK download, sha256 verification, safe
 * extraction, manifest validation against the capability table, registry
 * caching/throttling and the immutability of installed versions.
 * No network: the registry and SDK artifacts are faked.
 */
package com.crossberry.noxs.runtime.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

class NoxsSdkStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ----------------------------------------------------- tar.gz fixtures

    /** Minimal ustar header builder (same approach as the installer tests). */
    private fun tarHeader(name: String, size: Long): ByteArray {
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
        octal(420L, 100, 8)
        octal(0L, 108, 8)
        octal(0L, 116, 8)
        octal(size, 124, 12)
        octal(0L, 136, 12)
        header[156] = '0'.code.toByte()
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
        out.write(tarHeader(name, content.size.toLong()))
        out.write(content)
        out.write(ByteArray((512 - (content.size % 512)) % 512))
        return out.toByteArray()
    }

    private fun gzip(vararg entries: ByteArray): ByteArray {
        val tar = ByteArrayOutputStream()
        entries.forEach { tar.write(it) }
        tar.write(ByteArray(1024))
        val gz = ByteArrayOutputStream()
        GZIPOutputStream(gz).use { it.write(tar.toByteArray()) }
        return gz.toByteArray()
    }

    private fun sdkJson(version: String, apiVersion: String = "1", features: String = "[\"logging\",\"ui\",\"terminal\"]") =
        """
        {"name":"noxs-plugin-sdk","version":"$version","apiVersion":"$apiVersion",
         "entry":"index.js","types":"types.d.ts","status":"stable","features":$features}
        """.trimIndent()

    private fun sdkTar(version: String, manifest: String = sdkJson(version)) = gzip(
        tarEntry("sdk.json", manifest.toByteArray()),
        tarEntry("index.js", "(function(){window.__noxsSdk='$version';})();".toByteArray()),
        tarEntry("types.d.ts", "export {};".toByteArray())
    )

    private fun registryJson(
        versions: List<String> = listOf("0.0.1", "0.0.2"),
        checksumOf: (String) -> String? = { _ -> null }
    ) =
        buildString {
            append("{\"version\":1,\"sdks\":[")
            versions.forEachIndexed { i, v ->
                if (i > 0) append(",")
                append("{\"version\":\"$v\",\"apiVersion\":\"1\",\"status\":\"stable\",")
                append("\"features\":[\"logging\",\"ui\",\"terminal\"],")
                append("\"minimumNoxsVersion\":\"0.11.0\",")
                append("\"artifact\":\"https://artifacts.invalid/noxs-sdk-$v.noxs-sdk\",")
                append("\"checksum\":")
                append(checksumOf(v)?.let { "\"$it\"" } ?: "null")
                append("}")
            }
            append("]}")
        }

    /** Serves the sdk registry for the first call, then artifacts on demand. */
    private class FakeFetcher(private val registry: String) : NoxsPluginRegistry.Fetcher {
        val fetched = mutableListOf<String>()
        val artifacts = mutableMapOf<String, ByteArray>()
        var failNext = false
        override fun get(url: String, timeoutMs: Int): ByteArray {
            if (failNext) {
                failNext = false
                throw java.io.IOException("offline")
            }
            fetched.add(url)
            return when {
                url.endsWith("sdk/registry.json") -> registry.toByteArray()
                else -> artifacts[url] ?: throw java.io.IOException("no artifact for $url")
            }
        }
    }

    private fun newStore(fetcher: FakeFetcher): NoxsSdkStore =
        NoxsSdkStore(tmp.newFolder(), tmp.newFolder(), fetcher)

    // -------------------------------------------------------------- ensure

    @Test
    fun `ensure downloads, verifies and installs a release`() {
        val artifact = sdkTar("0.0.1")
        val fetcher = FakeFetcher(registryJson { v -> if (v == "0.0.1") PluginChecksum.sha256(artifact) else null })
        fetcher.artifacts["https://artifacts.invalid/noxs-sdk-0.0.1.noxs-sdk"] = artifact
        val store = newStore(fetcher)

        val dir = store.ensure("0.0.1")

        assertTrue(store.isInstalled("0.0.1"))
        assertEquals(listOf("0.0.1"), store.installedVersions())
        assertEquals("(function(){window.__noxsSdk='0.0.1';})();", store.entryJs("0.0.1"))
        assertEquals("0.0.1", store.manifest("0.0.1")?.version)
        assertTrue(File(dir, "types.d.ts").isFile)
        assertEquals(2, fetcher.fetched.size)
        assertTrue(fetcher.fetched[0].endsWith("sdk/registry.json"))
        assertEquals("https://artifacts.invalid/noxs-sdk-0.0.1.noxs-sdk", fetcher.fetched[1])
    }

    @Test
    fun `ensure is idempotent for an installed verified version`() {
        val artifact = sdkTar("0.0.1")
        val fetcher = FakeFetcher(registryJson { v -> if (v == "0.0.1") PluginChecksum.sha256(artifact) else null })
        fetcher.artifacts["https://artifacts.invalid/noxs-sdk-0.0.1.noxs-sdk"] = artifact
        val store = newStore(fetcher)
        store.ensure("0.0.1")
        val fetches = fetcher.fetched.size

        store.ensure("0.0.1")

        assertEquals(fetches, fetcher.fetched.size)
    }

    @Test
    fun `a tampered artifact is rejected by its checksum`() {
        val artifact = sdkTar("0.0.1")
        val fetcher = FakeFetcher(registryJson { v -> if (v == "0.0.1") PluginChecksum.sha256(artifact) else null })
        fetcher.artifacts["https://artifacts.invalid/noxs-sdk-0.0.1.noxs-sdk"] = sdkTar("0.0.2") // wrong bytes
        val store = newStore(fetcher)
        try {
            store.ensure("0.0.1")
            throw AssertionError("tampered SDK must be rejected")
        } catch (e: NoxsSdkStore.SdkException) {
            assertTrue(e.message!!.contains("integrity"))
        }
        assertFalse(store.isInstalled("0.0.1"))
    }

    @Test
    fun `a release without a published checksum is refused`() {
        val fetcher = FakeFetcher(registryJson { _ -> null })
        fetcher.artifacts["https://artifacts.invalid/noxs-sdk-0.0.1.noxs-sdk"] = sdkTar("0.0.1")
        val store = newStore(fetcher)
        try {
            store.ensure("0.0.1")
            throw AssertionError("unsigned SDK must be refused")
        } catch (e: NoxsSdkStore.SdkException) {
            assertTrue(e.message!!.contains("without a checksum"))
        }
    }

    @Test
    fun `an unpublished sdk version is reported clearly`() {
        val fetcher = FakeFetcher(registryJson { _ -> null })
        val store = newStore(fetcher)
        try {
            store.ensure("9.9.9")
            throw AssertionError("unknown SDK must fail")
        } catch (e: NoxsSdkStore.SdkException) {
            assertTrue(e.message!!.contains("not published"))
        }
    }

    @Test
    fun `a manifest that contradicts the capability table is rejected`() {
        // sdk.json claims a feature the table does not grant 0.0.1.
        val artifact = sdkTar("0.0.1", sdkJson("0.0.1", features = "[\"logging\",\"ui\",\"terminal\",\"storage\"]"))
        val fetcher = FakeFetcher(registryJson { v -> if (v == "0.0.1") PluginChecksum.sha256(artifact) else null })
        fetcher.artifacts["https://artifacts.invalid/noxs-sdk-0.0.1.noxs-sdk"] = artifact
        val store = newStore(fetcher)
        try {
            store.ensure("0.0.1")
            throw AssertionError("manifest/table mismatch must be rejected")
        } catch (e: NoxsSdkStore.SdkException) {
            assertTrue(e.message!!.contains("compatibility table"))
        }
    }

    @Test
    fun `a network failure surfaces a stable sdk message`() {
        val fetcher = FakeFetcher(registryJson { _ -> "abc" })
        val store = newStore(fetcher)
        try {
            store.ensure("0.0.1")
            throw AssertionError("offline download must fail")
        } catch (e: NoxsSdkStore.SdkException) {
            assertTrue(e.message!!.contains("could not be downloaded") || e.message!!.contains("not published"))
        }
    }

    // ----------------------------------------------------- remote registry

    @Test
    fun `remote registry is cached and force refreshes`() {
        val fetcher = FakeFetcher(registryJson { _ -> null })
        val store = newStore(fetcher)
        store.remoteRegistry()
        val first = fetcher.fetched.size
        store.remoteRegistry() // within the interval → served from cache
        assertEquals(first, fetcher.fetched.size)
        store.remoteRegistry(force = true)
        assertTrue(fetcher.fetched.size > first)
        assertEquals(listOf("0.0.1", "0.0.2"), store.remoteRegistry().map { it.version })
    }

    @Test
    fun `remote registry falls back to cache when offline`() {
        val fetcher = FakeFetcher(registryJson { _ -> null })
        val store = newStore(fetcher)
        store.remoteRegistry()
        fetcher.failNext = true
        val entries = store.remoteRegistry(force = true)
        assertEquals(2, entries.size)
        assertFalse(store.lastRefreshOk)
    }
}
