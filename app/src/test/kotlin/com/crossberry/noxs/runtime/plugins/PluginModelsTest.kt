/*
 * Noxs — original implementation.
 * JVM tests for the Plugin Store model layer: plugin.json/registry parsing,
 * the validation gate, semver ordering and compatibility checks.
 */
package com.crossberry.noxs.runtime.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginModelsTest {

    private val helloJson = """
        {
          "id": "hello",
          "name": "Hello",
          "version": "1.0.0",
          "description": "Example Noxs plugin",
          "logo": "icon.svg",
          "readme": "README.md",
          "main": "plugin.js",
          "permissions": ["ui"],
          "commands": ["hello"],
          "minimumNoxsVersion": "0.11.0",
          "author": "CrossberryWeb",
          "license": "MIT",
          "category": "Utilities"
        }
    """.trimIndent()

    @Test
    fun `parses full plugin metadata`() {
        val meta = PluginJson.parseMeta(helloJson)
        assertEquals("hello", meta.id)
        assertEquals("Hello", meta.name)
        assertEquals("1.0.0", meta.version)
        assertEquals("Example Noxs plugin", meta.description)
        assertEquals("plugin.js", meta.main)
        assertEquals(listOf("ui"), meta.permissions)
        assertEquals("README.md", meta.readme)
        assertEquals(listOf("hello"), meta.commands)
        assertEquals("0.11.0", meta.minimumNoxsVersion)
        assertEquals("CrossberryWeb", meta.author)
        assertEquals("Utilities", meta.category)
    }

    @Test
    fun `readme defaults to README-md`() {
        val meta = PluginJson.parseMeta("""{"id":"a1","name":"A","version":"1.0.0","description":"test","main":"plugin.js","permissions":["ui"],"minimumNoxsVersion":"0.11.0"}""")
        assertEquals("README.md", meta.readme)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `broken json is rejected`() {
        PluginJson.parseMeta("{ not json ")
    }

    @Test
    fun `validation accepts the hello metadata`() {
        assertTrue(PluginValidation.valid(PluginJson.parseMeta(helloJson)))
    }

    @Test
    fun `validation reports every broken field`() {
        val meta = PluginJson.parseMeta(
            """{"id":"BAD","name":"","version":"one","description":"","main":"main.js","permissions":["root"],"minimumNoxsVersion":"x"}"""
        )
        val problems = PluginValidation.validate(meta)
        assertTrue(problems.any { it.startsWith("id:") })
        assertTrue(problems.any { it.startsWith("name:") })
        assertTrue(problems.any { it.startsWith("version:") })
        assertTrue(problems.any { it.startsWith("description:") })
        assertTrue(problems.any { it.startsWith("main:") })
        assertTrue(problems.any { it.startsWith("permissions: unknown") })
        assertTrue(problems.any { it.startsWith("minimumNoxsVersion:") })
    }

    @Test
    fun `permissions catalog is enforced`() {
        assertTrue(PluginPermissions.KNOWN.contains("terminal"))
        assertFalse(PluginPermissions.KNOWN.contains("root"))
    }

    @Test
    fun `semver ordering`() {
        assertTrue(PluginSemver.parse("1.2.3")!! > PluginSemver.parse("1.2.2")!!)
        assertTrue(PluginSemver.parse("2.0.0")!! > PluginSemver.parse("1.9.9")!!)
        assertTrue(PluginSemver.parse("1.0.0")!! == PluginSemver.parse("1.0.0")!!)
        assertNull(PluginSemver.parse("1.0"))
        assertNull(PluginSemver.parse("01.0.0"))
        assertNull(PluginSemver.parse(null))
    }

    @Test
    fun `compatibility compares minimum noxs version`() {
        assertTrue(PluginValidation.compatible("0.11.0", "0.11.0"))
        assertTrue(PluginValidation.compatible("0.11.0", "1.0.0"))
        assertFalse(PluginValidation.compatible("0.11.0", "0.10.0"))
        // Missing/invalid requirement never blocks.
        assertTrue(PluginValidation.compatible(null, "0.1.0"))
        assertTrue(PluginValidation.compatible("nonsense", "0.1.0"))
    }

    @Test
    fun `registry parsing extracts entries`() {
        val registry = """
            {"version": 1, "plugins": [
              {"id": "hello", "name": "Hello", "version": "1.0.0",
               "description": "Example Noxs plugin", "logo": "icon.svg",
               "readme": "README.md", "release": "1.0.0",
               "permissions": ["ui"], "category": "Utilities",
               "logoUrl": "https://example.invalid/icon.svg",
               "readmeUrl": "https://example.invalid/README.md",
               "artifact": "https://example.invalid/hello-1.0.0.noxs-plugin",
               "checksum": "abc123", "keywords": ["demo"]}
            ]}
        """.trimIndent()
        val (format, entries) = PluginJson.parseRegistry(registry)
        assertEquals(1, format)
        assertEquals(1, entries.size)
        val hello = entries.first()
        assertEquals("hello", hello.id)
        assertEquals("abc123", hello.checksum)
        assertEquals(listOf("demo"), hello.keywords)
        assertTrue(hello.artifact!!.startsWith("https://"))
    }

    @Test
    fun `registry entries without id are dropped`() {
        val (format, entries) = PluginJson.parseRegistry("""{"version":1,"plugins":[{"nope":1},{"id":"ok"}]}""")
        assertEquals(1, format)
        assertEquals(listOf("ok"), entries.map { it.id })
    }

    // ------------------------------------------------ Noxs Plugin SDK fields

    @Test
    fun `manifest without sdk fields defaults to the initial stable sdk`() {
        val meta = PluginJson.parseMeta(
            """{"id":"a1","name":"A","version":"1.0.0","description":"test","main":"plugin.js","permissions":["ui"],"minimumNoxsVersion":"0.11.0"}"""
        )
        assertEquals("0.0.1", meta.sdkVersion)
        assertNull(meta.minimumSdkVersion)
        assertNull(meta.maximumSdkVersion)
        assertEquals(emptyList<String>(), meta.apiFeatures)
        assertTrue(PluginValidation.valid(meta))
    }

    @Test
    fun `sdk fields are parsed from the manifest`() {
        val meta = PluginJson.parseMeta(
            """
            {"id":"a1","name":"A","version":"1.0.0","description":"test","main":"plugin.js",
             "permissions":["ui","storage"],"minimumNoxsVersion":"0.13.0",
             "sdkVersion":"0.0.2","minimumSdkVersion":"0.0.1","maximumSdkVersion":"0.1.0",
             "apiFeatures":["logging","storage"]}
            """.trimIndent()
        )
        assertEquals("0.0.2", meta.sdkVersion)
        assertEquals("0.0.1", meta.minimumSdkVersion)
        assertEquals("0.1.0", meta.maximumSdkVersion)
        assertEquals(listOf("logging", "storage"), meta.apiFeatures)
        assertTrue(PluginValidation.valid(meta))
    }

    @Test
    fun `registry entries carry sdk fields with legacy defaults`() {
        val (format, entries) = PluginJson.parseRegistry(
            """{"version":1,"plugins":[
                 {"id":"a1","name":"A","version":"1.0.0","description":"t","permissions":["ui"],
                  "sdkVersion":"0.0.2","minimumSdkVersion":"0.0.1","apiFeatures":["ui"]},
                 {"id":"b2","name":"B","version":"1.0.0","description":"t","permissions":["ui"]}]}"""
        )
        assertEquals(1, format)
        assertEquals("0.0.2", entries[0].sdkVersion)
        assertEquals(listOf("ui"), entries[0].apiFeatures)
        assertNull(entries[1].sdkVersion)
    }

    @Test
    fun `contradictory sdk requirements are rejected`() {
        fun metaWith(vararg pairs: String): PluginMeta = PluginJson.parseMeta(
            """
            {"id":"a1","name":"A","version":"1.0.0","description":"test","main":"plugin.js",
             "permissions":["ui"],"minimumNoxsVersion":"0.11.0",${pairs.joinToString(",")}}
            """.trimIndent()
        )
        // minimumSdkVersion above sdkVersion.
        assertTrue(
            PluginValidation.validate(
                metaWith("\"sdkVersion\":\"0.0.2\",\"minimumSdkVersion\":\"0.1.0\"")
            ).isNotEmpty()
        )
        // maximum below minimum.
        assertTrue(
            PluginValidation.validate(
                metaWith("\"sdkVersion\":\"0.0.2\",\"minimumSdkVersion\":\"0.0.1\",\"maximumSdkVersion\":\"0.0.1\"")
            ).isNotEmpty()
        )
        // Unknown feature.
        assertTrue(
            PluginValidation.validate(metaWith("\"apiFeatures\":[\"teleport\"]")).isNotEmpty()
        )
        // Broken sdkVersion.
        assertTrue(
            PluginValidation.validate(metaWith("\"sdkVersion\":\"latest\"")).isNotEmpty()
        )
    }
}
