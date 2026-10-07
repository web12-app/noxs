/*
 * Noxs — original implementation.
 * JVM tests for the Plugin Store registry: shared search semantics, cache
 * adoption and rejection of broken payloads (all offline, no network).
 */
package com.crossberry.noxs.runtime.plugins

import org.junit.Assert.assertEquals
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
        category: String = "Utilities",
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
        assertEquals(listOf("hello"), registry.search(entries, "hell").map { it.id })
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
}
