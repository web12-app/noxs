/*
 * Noxs — original implementation.
 * JVM tests for the Noxs Plugin SDK compatibility layer: the capability
 * table, the SDK selection algorithm (version ranges, API generations,
 * feature requirements, installed-release preference) and every Plugin
 * Store compatibility state (spec §7).
 */
package com.crossberry.noxs.runtime.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginSdkCatalogTest {

    private fun entry(
        id: String = "demo",
        version: String = "1.0.0",
        minimumNoxsVersion: String? = "0.11.0",
        sdkVersion: String? = "0.0.1",
        minimumSdkVersion: String? = null,
        maximumSdkVersion: String? = null,
        apiFeatures: List<String> = emptyList()
    ) = RegistryEntry(
        id = id, name = "Demo", version = version,
        description = "demo plugin", logo = null, readme = null,
        logoUrl = null, readmeUrl = null, release = version,
        permissions = listOf("ui"), category = "Utilities", keywords = emptyList(),
        minimumNoxsVersion = minimumNoxsVersion, artifact = null, checksum = null,
        releaseTag = null, updatedAt = null,
        sdkVersion = sdkVersion, minimumSdkVersion = minimumSdkVersion,
        maximumSdkVersion = maximumSdkVersion, apiFeatures = apiFeatures
    )

    // ------------------------------------------------------- capability table

    @Test
    fun `capability table pins both sdk releases`() {
        assertEquals(2, PluginSdkCatalog.RELEASES.size)
        val v1 = PluginSdkCatalog.known("0.0.1")!!
        assertEquals("1", v1.apiVersion)
        assertEquals(setOf("logging", "ui", "terminal"), v1.features)
        val v2 = PluginSdkCatalog.known("0.0.2")!!
        assertEquals("1", v2.apiVersion)
        assertTrue("storage" in v2.features)
    }

    @Test
    fun `supported releases respect the minimum noxs version`() {
        assertEquals(listOf("0.0.1"), PluginSdkCatalog.supported("0.12.0").map { it.version })
        assertEquals(listOf("0.0.1", "0.0.2"), PluginSdkCatalog.supported("0.13.0").map { it.version })
        assertTrue(PluginSdkCatalog.supported("0.10.0").isEmpty())
        assertFalse(PluginSdkCatalog.featureAvailable("storage", "0.12.0"))
        assertTrue(PluginSdkCatalog.featureAvailable("storage", "0.13.0"))
        assertTrue(PluginSdkCatalog.featureAvailable("logging", "0.12.0"))
    }

    // -------------------------------------------------------------- resolve

    @Test
    fun `legacy plugin resolves to the initial stable sdk`() {
        val plan = PluginSdkCatalog.resolve(SdkRequirements.from(entry(sdkVersion = null)), "0.11.0")
        assertTrue(plan is SdkPlan.Supported)
        assertEquals("0.0.1", (plan as SdkPlan.Supported).selected.version)
    }

    @Test
    fun `newest compatible release is selected when nothing installed`() {
        val plan = PluginSdkCatalog.resolve(SdkRequirements.from(entry()), "0.13.0")
        assertEquals("0.0.2", (plan as SdkPlan.Supported).selected.version)
    }

    @Test
    fun `an installed release is preferred over a newer download`() {
        val plan = PluginSdkCatalog.resolve(
            SdkRequirements.from(entry()), "0.13.0", installedVersions = setOf("0.0.1")
        )
        assertTrue((plan as SdkPlan.Supported).installedLocally)
        assertEquals("0.0.1", plan.selected.version)
    }

    @Test
    fun `maximumSdkVersion is an exclusive upper bound`() {
        val plan = PluginSdkCatalog.resolve(
            SdkRequirements.from(entry(maximumSdkVersion = "0.0.2")), "0.13.0"
        )
        assertEquals("0.0.1", (plan as SdkPlan.Supported).selected.version)
    }

    @Test
    fun `minimumSdkVersion raises the floor and can require a newer app`() {
        val plan = PluginSdkCatalog.resolve(
            SdkRequirements.from(entry(minimumSdkVersion = "0.0.2")), "0.12.0"
        )
        assertTrue(plan is SdkPlan.NeedsNewerApp)
        assertEquals("0.13.0", (plan as SdkPlan.NeedsNewerApp).requiredNoxs)
    }

    @Test
    fun `an empty sdk range is rejected`() {
        val plan = PluginSdkCatalog.resolve(
            SdkRequirements.from(entry(minimumSdkVersion = "0.0.2", maximumSdkVersion = "0.0.2")),
            "0.13.0"
        )
        assertTrue(plan is SdkPlan.Incompatible)
    }

    @Test
    fun `api generation mismatch falls back to a newer app`() {
        // A hypothetical future SDK generation this table does not carry.
        val plan = PluginSdkCatalog.resolve(
            SdkRequirements.from(entry(sdkVersion = "1.0.0")), "0.13.0"
        )
        assertTrue(plan is SdkPlan.NeedsNewerApp)
    }

    @Test
    fun `unsatisfiable sdk version is incompatible`() {
        val plan = PluginSdkCatalog.resolve(
            SdkRequirements.from(entry(minimumSdkVersion = "9.9.9")), "0.13.0"
        )
        assertTrue(plan is SdkPlan.Incompatible)
    }

    @Test
    fun `a required feature can demand a newer noxs`() {
        val plan = PluginSdkCatalog.resolve(
            SdkRequirements.from(entry(apiFeatures = listOf("storage"))), "0.12.0"
        )
        assertTrue(plan is SdkPlan.NeedsNewerApp)
        assertEquals("0.13.0", (plan as SdkPlan.NeedsNewerApp).requiredNoxs)
    }

    @Test
    fun `a known feature inside the supported range selects the provider`() {
        val plan = PluginSdkCatalog.resolve(
            SdkRequirements.from(entry(apiFeatures = listOf("storage"))), "0.13.0"
        )
        assertEquals("0.0.2", (plan as SdkPlan.Supported).selected.version)
    }

    // ---------------------------------------------------------------- compat

    @Test
    fun `compat is compatible for an installable plugin`() {
        val info = PluginSdkCatalog.compat(
            entry(), installed = null, appVersion = "0.13.0",
            installedSdkVersions = emptySet(), blockedRecord = false, updateAvailable = false
        )
        assertEquals(PluginCompatState.COMPATIBLE, info.state)
        assertEquals("0.0.2", info.sdkVersion)
    }

    @Test
    fun `installed plugin without its sdk locally is sdk_missing`() {
        val info = PluginSdkCatalog.compat(
            entry(sdkVersion = "0.0.2"), installed = null, appVersion = "0.13.0",
            installedSdkVersions = emptySet(), blockedRecord = false, updateAvailable = false
        )
        // Not installed: the store treats the missing SDK as a download step.
        assertEquals(PluginCompatState.COMPATIBLE, info.state)
        val installed = installedPlugin("0.0.2")
        val info2 = PluginSdkCatalog.compat(
            entry(sdkVersion = "0.0.2"), installed = installed, appVersion = "0.13.0",
            installedSdkVersions = setOf("0.0.1"), blockedRecord = false, updateAvailable = false
        )
        assertEquals(PluginCompatState.SDK_MISSING, info2.state)
    }

    @Test
    fun `older app version surfaces app_update_required with versions`() {
        val info = PluginSdkCatalog.compat(
            entry(minimumNoxsVersion = "0.14.0"), installed = null, appVersion = "0.13.0",
            installedSdkVersions = setOf("0.0.1", "0.0.2"), blockedRecord = false, updateAvailable = false
        )
        assertEquals(PluginCompatState.APP_UPDATE_REQUIRED, info.state)
        assertEquals("0.14.0", info.requiredNoxs)
        assertTrue(info.reason!!.contains("Update Noxs"))
    }

    @Test
    fun `update available is reported only for genuinely newer registry versions`() {
        val installed = installedPlugin("1.0.0")
        val same = PluginSdkCatalog.compat(
            entry(version = "1.0.0"), installed = installed, appVersion = "0.13.0",
            installedSdkVersions = setOf("0.0.1", "0.0.2"), blockedRecord = false, updateAvailable = false
        )
        assertEquals(PluginCompatState.COMPATIBLE, same.state)
        val newer = PluginSdkCatalog.compat(
            entry(version = "1.1.0"), installed = installed, appVersion = "0.13.0",
            installedSdkVersions = setOf("0.0.1", "0.0.2"), blockedRecord = false, updateAvailable = true
        )
        assertEquals(PluginCompatState.PLUGIN_UPDATE_AVAILABLE, newer.state)
    }

    @Test
    fun `invalid sdk requirements are blocked`() {
        val bad = PluginSdkCatalog.compat(
            entry(minimumSdkVersion = "0.1.0"), installed = null, appVersion = "0.13.0",
            installedSdkVersions = emptySet(), blockedRecord = false, updateAvailable = false
        )
        // minimumSdkVersion above sdkVersion is contradictory.
        assertEquals(PluginCompatState.BLOCKED, bad.state)
        val tampered = PluginSdkCatalog.compat(
            entry(), installed = null, appVersion = "0.13.0",
            installedSdkVersions = emptySet(), blockedRecord = true, updateAvailable = false
        )
        assertEquals(PluginCompatState.BLOCKED, tampered.state)
    }

    @Test
    fun `state codes are stable lowercase`() {
        assertEquals("sdk_missing", PluginCompatState.SDK_MISSING.code)
        assertEquals("app_update_required", PluginCompatState.APP_UPDATE_REQUIRED.code)
    }

    @Test
    fun `newestSemver picks the larger version`() {
        assertEquals("0.14.0", PluginSdkCatalog.newestSemver("0.13.0", "0.14.0"))
        assertEquals("0.14.0", PluginSdkCatalog.newestSemver("0.14.0", "0.13.0"))
        assertEquals("0.13.0", PluginSdkCatalog.newestSemver("0.13.0", null))
        assertNull(PluginSdkCatalog.newestSemver(null, null))
    }

    private fun installedPlugin(sdkVersion: String): InstalledPlugin {
        val dir = java.io.File(
            System.getProperty("java.io.tmpdir"),
            "noxs-sdk-cat-test/${sdkVersion}-${System.nanoTime()}"
        )
        dir.mkdirs()
        dir.deleteOnExit()
        val meta = PluginJson.parseMeta(
            """{"id":"demo","name":"Demo","version":"1.0.0","description":"demo","main":"plugin.js",
                "permissions":["ui"],"minimumNoxsVersion":"0.11.0","sdkVersion":"$sdkVersion"}"""
        )
        return InstalledPlugin(dir, meta, enabled = true)
    }
}
