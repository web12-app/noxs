/*
 * Noxs — original implementation.
 * JVM tests for the plugin local-state layer: PluginStateStore (the
 * plugin-state.json install records), PluginSeenStore (store "New" badges)
 * and PluginStorageFile (the bounded plugin-scoped key/value store behind
 * the SDK "storage" feature).
 */
package com.crossberry.noxs.runtime.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PluginLocalStateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ----------------------------------------------------- PluginStateStore

    @Test
    fun `state records survive a store rebuild`() {
        val home = tmp.newFolder()
        val store = PluginStateStore(home)
        store.record("hello", "1.0.0", "0.0.1", "abc123")
        store.record("code", "1.0.1", "0.0.2", null)

        val reloaded = PluginStateStore(home)
        assertEquals(
            PluginStateRecord("1.0.0", "0.0.1", "abc123"),
            reloaded.get("hello")
        )
        assertEquals(
            PluginStateRecord("1.0.1", "0.0.2", null),
            reloaded.get("code")
        )
        assertEquals(setOf("hello", "code"), reloaded.all().keys)
        assertTrue(PluginStateStore(home).get("hello")!!.sdkVersion == "0.0.1")
        assertEquals("plugin-state.json", File(home, "plugin-state.json").name)
    }

    @Test
    fun `state remove deletes exactly one record`() {
        val home = tmp.newFolder()
        val store = PluginStateStore(home)
        store.record("hello", "1.0.0", "0.0.1", "abc")
        store.record("code", "1.0.0", "0.0.1", "def")
        store.remove("hello")
        assertNull(store.get("hello"))
        assertEquals("0.0.1", store.get("code")!!.sdkVersion)
        // Removing an unknown plugin is a no-op.
        store.remove("ghost")
        assertEquals(setOf("code"), store.all().keys)
    }

    @Test
    fun `state rejects invalid plugin ids`() {
        val home = tmp.newFolder()
        val store = PluginStateStore(home)
        store.record("../escape", "1.0.0", "0.0.1", null)
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `state values with quotes and newlines round-trip`() {
        val home = tmp.newFolder()
        val store = PluginStateStore(home)
        val trickyChecksum = "a\"b\\c\nd\te"
        store.record("hello", "1.0.0+\"x\"", "0.0.1", trickyChecksum)
        assertEquals(trickyChecksum, PluginStateStore(home).get("hello")!!.sdkChecksum)
    }

    // ------------------------------------------------------ PluginSeenStore

    @Test
    fun `seen store marks and persists`() {
        val cache = tmp.newFolder()
        val store = PluginSeenStore(cache)
        assertTrue(store.isNew("hello"))
        store.markSeen(listOf("hello", "code"))
        assertFalse(store.isNew("hello"))
        assertTrue(store.isNew("youtube-downloader"))
        assertFalse(PluginSeenStore(cache).isNew("code"))
    }

    @Test
    fun `seen store ignores invalid ids`() {
        val cache = tmp.newFolder()
        val store = PluginSeenStore(cache)
        store.markSeen(listOf("BAD ID", "ok-id"))
        assertEquals(setOf("ok-id"), store.seen())
    }

    // ---------------------------------------------------- PluginStorageFile

    @Test
    fun `storage round-trips keys and values`() {
        val file = File(tmp.newFolder(), ".storage.json")
        assertTrue(PluginStorageFile.store(file, mapOf("counter" to "42", "name" to "Noxs")))
        assertEquals(mapOf("counter" to "42", "name" to "Noxs"), PluginStorageFile.load(file))
    }

    @Test
    fun `storage escapes quotes, backslashes and newlines`() {
        val file = File(tmp.newFolder(), ".storage.json")
        val value = "line1\n\"quoted\"\\slash\ttab"
        assertTrue(PluginStorageFile.store(file, mapOf("key" to value)))
        assertEquals(mapOf("key" to value), PluginStorageFile.load(file))
    }

    @Test
    fun `storage rejects oversize entries and files`() {
        val file = File(tmp.newFolder(), ".storage.json")
        val hugeValue = "x".repeat(PluginStorageFile.MAX_VALUE_BYTES + 1)
        assertFalse(PluginStorageFile.store(file, mapOf("k" to hugeValue)))
        val hugeKey = "k".repeat(PluginStorageFile.MAX_KEY_LENGTH + 1)
        assertFalse(PluginStorageFile.store(file, mapOf(hugeKey to "v")))
        assertFalse(PluginStorageFile.store(file, mapOf("" to "v")))
        val many = (1..(PluginStorageFile.MAX_ENTRIES + 1)).associate { "$it" to "v" }
        assertFalse(PluginStorageFile.store(file, many))
    }

    @Test
    fun `storage load tolerates missing and broken files`() {
        assertTrue(PluginStorageFile.load(File(tmp.root, "missing.json")).isEmpty())
        val broken = File(tmp.newFolder(), ".storage.json")
        broken.writeText("{ not json")
        assertTrue(PluginStorageFile.load(broken).isEmpty())
    }

    @Test
    fun `storage rejects a file that would exceed its bound`() {
        val file = File(tmp.newFolder(), ".storage.json")
        // Many 1KB values stay under MAX_ENTRIES but exceed MAX_FILE_BYTES.
        val data = (1..PluginStorageFile.MAX_ENTRIES).associate { "k$it" to "v".repeat(1024) }
        assertFalse(PluginStorageFile.store(file, data))
        assertFalse(file.exists())
    }
}
