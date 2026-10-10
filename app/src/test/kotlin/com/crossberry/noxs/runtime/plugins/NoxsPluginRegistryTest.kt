/*
 * Noxs — original implementation.
 * JVM tests for the Plugin Store registry: shared search semantics, cache
 * adoption and rejection of broken payloads (all offline, no network).
 */
package com.crossberry.noxs.runtime.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NoxsPluginRegistryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun registry(): NoxsPluginRegistry =
        NoxsPluginRegistry(tmp.newFolder(), fetcher = { _: String, _: Int -> throw AssertionError("no network in tests") })

    private fun entry(
        id: String,
        name: String = id,
        description: String = "$id description",
        category: String? = "Utilities",
        keywords: List<String> = emptyList()
    ) = RegistryEntry(
        id = id, name = name, version = "1.0.0", description = description,
        logo = "icon.svg", readme = "README.md",
        logoUrl = null, readmeUrl = null, release = "1.0.0",
        permissions = listOf("ui"), category = category, keywords = keywords,
        minimumNoxsVersion = "0.11.0", artifact = "https://a/$id", checksum = null,
        releaseTag = null, updatedAt = null
    )

    @Test
    fun `search matches name, id, description, category and keywords`() {
        val entries = listOf(
            entry("hello", name = "Hello", description = "Example Noxs plugin", category = "Utilities"),
            entry("term-tools", name = "Terminal Tools", description = "Terminal utilities", category = "Development", keywords = listOf("shell")),
            entry("sysmon", name = "System Monitor", description = "Process watcher", category = "Utilities", keywords = listOf("cpu", "shell"))
        )
        val registry = registry()
        assertEquals(3, registry.search(entries, "").size)
        // "hell" matches hello (id/name) AND both keyword "shell" entries.
        assertEquals(
            listOf("hello", "term-tools", "sysmon"),
            registry.search(entries, "hell").map { it.id }
        )
        assertEquals(listOf("term-tools"), registry.search(entries, "TERMINAL too").map { it.id })
        assertEquals(listOf("sysmon"), registry.search(entries, "cpu").map { it.id })
        assertEquals(listOf("term-tools", "sysmon"), registry.search(entries, "shell").map { it.id })
        assertEquals(listOf("term-tools"), registry.search(entries, "Development").map { it.id })
        assertTrue(registry.search(entries, "nothing-matches").isEmpty())
    }

    @Test
    fun `adopt caches registry text atomically and entries survive reload`() {
        val cacheDir = tmp.newFolder()
        val registry = NoxsPluginRegistry(cacheDir, fetcher = { _: String, _: Int -> throw AssertionError("no network in tests") })
        registry.adopt("""{"version":1,"plugins":[{"id":"hello","name":"Hello","version":"1.0.0","description":"Example","permissions":["ui"]}]}""")
        assertEquals(listOf("hello"), registry.entries(refresh = false).map { it.id })
        // The cache file lives in the cache dir.
        assertTrue(java.io.File(cacheDir, "registry.json").isFile)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `adopt rejects unsupported registry format`() {
        registry().adopt("""{"version":99,"plugins":[]}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `adopt rejects broken json`() {
        registry().adopt("{ broken")
    }

    @Test
    fun `entries with empty cache return empty list`() {
        assertTrue(registry().entries(refresh = false).isEmpty())
    }

    @Test
    fun `categories are distinct and sorted`() {
        val entries = listOf(
            entry("a", category = "Utilities"),
            entry("b", category = "Development"),
            entry("c", category = "Utilities"),
            entry("d", category = null)
        )
        assertEquals(listOf("Development", "Utilities"), registry().categories(entries))
    }

    @Test
    fun `sha256 produces the expected digest`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            PluginChecksum.sha256(ByteArray(0))
        )
    }

    // ------------------------------------------------- refresh throttling

    private class CountingFetcher(vararg payloads: String) : NoxsPluginRegistry.Fetcher {
        var fetches = 0
        private val queue = ArrayDeque(payloads.toList())
        override fun get(url: String, timeoutMs: Int): ByteArray {
            fetches += 1
            return (queue.removeFirstOrNull()
                ?: throw java.io.IOException("offline")).toByteArray()
        }
    }

    private fun registryPayload() =
        """{"version":1,"plugins":[{"id":"hello","name":"Hello","version":"1.0.0",
            "description":"Example","permissions":["ui"]}]}"""

    @Test
    fun `entries refresh is throttled to the configured interval`() {
        val fetcher = CountingFetcher(registryPayload())
        val registry = NoxsPluginRegistry(tmp.newFolder(), fetcher, minRefreshIntervalMs = 60_000L)

        assertTrue(registry.entries(refresh = true).isNotEmpty())
        assertEquals(1, fetcher.fetches)
        assertTrue(registry.lastRefreshOk)

        // Inside the interval: served from cache, no network.
        assertEquals(listOf("hello"), registry.entries(refresh = true).map { it.id })
        assertEquals(1, fetcher.fetches)

        // Force (the store's manual refresh) bypasses the throttle.
        assertEquals(listOf("hello"), registry.entries(refresh = true, force = true).map { it.id })
        assertEquals(2, fetcher.fetches)
    }

    @Test
    fun `a failed refresh keeps the cached registry and reports offline`() {
        val fetcher = CountingFetcher(registryPayload())
        val registry = NoxsPluginRegistry(tmp.newFolder(), fetcher, minRefreshIntervalMs = 0L)
        assertTrue(registry.entries(refresh = true).isNotEmpty())

        val offline = NoxsPluginRegistry(tmp.newFolder().also { dir ->
            // Copy the populated cache into a second registry with a failing fetcher.
            java.io.File(dir, "registry.json").writeText(registryPayload(), Charsets.UTF_8)
        }, fetcher = { _: String, _: Int -> throw java.io.IOException("offline") })

        assertEquals(listOf("hello"), offline.entries(refresh = true).map { it.id })
        assertFalse(offline.lastRefreshOk)
    }
}
