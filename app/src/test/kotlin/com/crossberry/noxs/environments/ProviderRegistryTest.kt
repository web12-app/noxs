package com.crossberry.noxs.environments

import com.crossberry.noxs.environments.model.CompatibilityLevel
import com.crossberry.noxs.environments.model.Environment
import com.crossberry.noxs.environments.model.EnvironmentCapability
import com.crossberry.noxs.environments.model.EnvironmentStatus
import com.crossberry.noxs.environments.model.EnvJson
import com.crossberry.noxs.environments.providers.DebianProvider
import com.crossberry.noxs.environments.providers.ProviderRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Provider discovery for the SINGLE-environment Noxs: Debian 12 (bookworm)
 * is the one and only provider. The multi-OS registry (Ubuntu, Kali, Arch,
 * Parrot, Termux) was removed — one environment, one install path.
 */
class ProviderRegistryTest {

    private fun device(abi: String = "arm64-v8a", storage: Long = 16L * 1024 * 1024 * 1024) =
        DeviceProfile(abi, listOf(abi), 29, 4L * 1024 * 1024 * 1024, storage, true)

    @Test fun `exactly one environment is discovered - debian`() {
        val providers = ProviderRegistry.createAll(termuxInstalled = false)
        assertEquals(listOf("debian"), providers.map { it.id })
    }

    @Test fun `debian is the recommended default and is supported on arm64`() {
        val debian = ProviderRegistry.createAll(false).first { it.id == "debian" }
        assertTrue(debian.recommended)
        assertEquals(CompatibilityLevel.SUPPORTED, debian.checkCompatibility(device()).level)
    }

    @Test fun `debian ships a single bookworm variant marked default`() {
        val variants = DebianProvider().variants()
        assertEquals(listOf("bookworm"), variants.map { it.id })
        assertTrue(variants.single().isDefault)
        assertTrue(variants.single().downloadBytesEstimate > 0L)
    }

    @Test fun `debian capabilities cover the full feature set`() {
        val caps = DebianProvider().capabilities()
        assertTrue(caps.contains(EnvironmentCapability.TERMINAL))
        assertTrue(caps.contains(EnvironmentCapability.SUDO))
        assertTrue(caps.contains(EnvironmentCapability.PACKAGE_MANAGER))
        assertTrue(caps.contains(EnvironmentCapability.CODE_SERVER))
    }

    @Test fun `findById still resolves the debian provider`() {
        val providers = ProviderRegistry.createAll(false)
        assertEquals("debian", ProviderRegistry.findById(providers, "debian")?.id)
        assertEquals(null, ProviderRegistry.findById(providers, "ubuntu"))
    }

    @Test fun `apt adapter is the only package manager translation`() {
        val apt = AptAdapter()
        assertEquals(
            listOf("/usr/bin/apt-get", "install", "--yes", "--no-install-recommends", "git"),
            apt.install("git")
        )
        assertEquals(null, PackageManagerAdapters.forKind(com.crossberry.noxs.environments.model.PackageManagerKind.PACMAN))
        assertEquals("python3", PackageManagerAdapters.optionalComponentPackage("Python", com.crossberry.noxs.environments.model.PackageManagerKind.APT))
    }

    @Test fun `storage requirement adds download temp extracted and margin`() {
        val required = StorageRequirements.forVariant(48_389_910L, 190_000_000L)
        assertTrue(required > 190_000_000L)
    }

    @Test fun `environment json round trip survives single-env registry`() {
        val env = Environment(
            id = "debian", providerId = "debian", displayName = "Debian 12",
            version = "", architecture = "arm64-v8a", variant = "bookworm",
            status = EnvironmentStatus.READY, storagePath = "/data/noxs/environments/debian",
            createdAt = 1L, updatedAt = 2L
        )
        val restored = Environment.fromJson(env.toJson())
        assertNotNull(restored)
        assertEquals(env, restored)
    }

    @Test fun `envjson writer escapes control characters`() {
        val text = EnvJson.write(mapOf("a" to "quote\" backslash\\ newline\n"))
        val parsed = EnvJson.readObject(text)
        assertEquals("quote\" backslash\\ newline\n", parsed["a"])
    }
}
