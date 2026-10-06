/*
 * Noxs — original implementation.
 * EnvironmentProvider contract (spec §2, §20) + compatibility engine (§5) +
 * storage calculator (§6).
 *
 * The UI and EnvironmentManager talk ONLY to this interface. Providers
 * implement the operations relevant to their environment and return honest
 * metadata — no fake compatibility, no fake sizes.
 */
package com.crossberry.noxs.environments

import com.crossberry.noxs.environments.model.CompatibilityCheck
import com.crossberry.noxs.environments.model.CompatibilityLevel
import com.crossberry.noxs.environments.model.CompatibilityReport
import com.crossberry.noxs.environments.model.EnvironmentCapability
import com.crossberry.noxs.environments.model.EnvironmentVariant
import com.crossberry.noxs.environments.model.StorageCheck
import com.crossberry.noxs.environments.model.StoragePlan

// ----------------------------------------------------------- device profile

/**
 * Snapshot of the device facts the compatibility engine is allowed to see.
 * Injected by the Android layer; JVM tests construct it directly.
 */
data class DeviceProfile(
    val abi: String,
    val supportedAbis: List<String>,
    val sdkInt: Int,
    val availableRamBytes: Long,
    val availableStorageBytes: Long,
    val networkAvailable: Boolean
) {
    val isArm64: Boolean get() = abi == "arm64-v8a"
    val isArm32: Boolean get() = abi == "armeabi-v7a"
    val isX86_64: Boolean get() = abi == "x86_64"

    companion object {
        const val MIN_SDK = 24
    }
}

/** Live probe for the Android side. */
object AndroidDeviceProfile {

    /** Non-context probe (RAM unknown → compatibility shows ⚠ honestly). */
    fun probe(availableStorageBytes: Long, networkAvailable: Boolean): DeviceProfile {
        val abis = android.os.Build.SUPPORTED_ABIS.toList()
        return DeviceProfile(
            abi = abis.firstOrNull() ?: "unknown",
            supportedAbis = abis,
            sdkInt = android.os.Build.VERSION.SDK_INT,
            availableRamBytes = 0L,
            availableStorageBytes = availableStorageBytes,
            networkAvailable = networkAvailable
        )
    }

    /** Context-based probe (ActivityManager memory + ConnectivityManager network). */
    fun probe(context: android.content.Context): DeviceProfile {
        val abis = android.os.Build.SUPPORTED_ABIS.toList()
        val abi = abis.firstOrNull() ?: "unknown"
        val ram = runCatching {
            val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE)
                as android.app.ActivityManager
            val info = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            info.availMem
        }.getOrDefault(0L)
        val network = runCatching {
            val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.getOrDefault(false)
        return DeviceProfile(
            abi = abi,
            supportedAbis = abis,
            sdkInt = android.os.Build.VERSION.SDK_INT,
            availableRamBytes = ram,
            availableStorageBytes = 0L, // filled per-environment from the storage dir
            networkAvailable = network
        )
    }
}

// --------------------------------------------------------- provider contract

/**
 * The single abstraction between Noxs and any Linux environment (spec §20).
 * Operations irrelevant to an environment return `null`/empty honestly.
 */
interface EnvironmentProvider {
    val id: String
    val displayName: String
    val description: String
    val recommended: Boolean get() = false

    fun capabilities(): Set<EnvironmentCapability>
    fun variants(): List<EnvironmentVariant>
    fun checkCompatibility(device: DeviceProfile): CompatibilityReport

    /**
     * Runs the complete installation for [variant] through explicit
     * Noxs-controlled operations (spec §66). Returns true on success.
     */
    suspend fun runInstall(ctx: InstallContext, variant: EnvironmentVariant): Boolean = false

    /**
     * True when this provider can perform a real installation on this device.
     * Providers without an official device image (e.g. Parrot on ARM64) return
     * false with an honest reason instead of faking an install (spec §5, §27).
     */
    fun canInstall(device: DeviceProfile): Boolean =
        checkCompatibility(device).level != CompatibilityLevel.UNSUPPORTED
}

// ------------------------------------------------------- compatibility engine

/**
 * Pure compatibility logic shared by rootfs providers (spec §5): architecture,
 * Android version, RAM, storage and network checks with honest verdicts.
 */
object CompatibilityEngine {

    const val MIN_RAM_BYTES: Long = 512L * 1024 * 1024
    const val RECOMMENDED_RAM_BYTES: Long = 1536L * 1024 * 1024

    fun report(
        device: DeviceProfile,
        requiredStorageBytes: Long,
        supportedAbis: Set<String> = setOf("arm64-v8a"),
        limitedAbis: Set<String> = setOf("armeabi-v7a"),
        minSdk: Int = DeviceProfile.MIN_SDK,
        extraChecks: List<CompatibilityCheck> = emptyList()
    ): CompatibilityReport {
        val checks = mutableListOf<CompatibilityCheck>()

        val archOk = device.abi in supportedAbis
        val archLimited = !archOk && device.abi in limitedAbis
        checks += CompatibilityCheck(
            label = "CPU architecture (${device.abi})",
            passed = if (archLimited) null else archOk,
            detail = when {
                archOk -> "supported"
                archLimited -> "supported with limitations"
                else -> "no official build for this architecture"
            }
        )

        checks += CompatibilityCheck(
            label = "Android ${device.sdkInt}",
            passed = device.sdkInt >= minSdk,
            detail = if (device.sdkInt >= minSdk) "supported" else "requires API $minSdk+"
        )

        val ramOk = device.availableRamBytes <= 0L || device.availableRamBytes >= MIN_RAM_BYTES
        checks += CompatibilityCheck(
            label = "Available RAM",
            passed = if (device.availableRamBytes <= 0L) null else ramOk,
            detail = when {
                device.availableRamBytes <= 0L -> "unknown"
                ramOk && device.availableRamBytes >= RECOMMENDED_RAM_BYTES -> "sufficient"
                ramOk -> "limited but workable"
                else -> "below minimum"
            }
        )

        val storageOk = device.availableStorageBytes <= 0L || device.availableStorageBytes >= requiredStorageBytes
        checks += CompatibilityCheck(
            label = "Storage",
            passed = if (device.availableStorageBytes <= 0L) null else storageOk,
            detail = if (storageOk) "sufficient" else "insufficient for this environment"
        )

        checks += CompatibilityCheck(
            label = "Network",
            passed = if (device.networkAvailable) true else false,
            detail = if (device.networkAvailable) "available" else "required for download"
        )

        checks += extraChecks

        val hardFailures = checks.any { it.passed == false }
        val limited = !hardFailures && (archLimited || checks.any { it.passed == null })
        val level = when {
            hardFailures -> CompatibilityLevel.UNSUPPORTED
            limited -> CompatibilityLevel.LIMITED
            else -> CompatibilityLevel.SUPPORTED
        }
        val reasons = checks.filter { it.passed == false }.map { "${it.label}: ${it.detail}" }
        return CompatibilityReport(level, checks, reasons)
    }
}

// ---------------------------------------------------------- storage planning

/**
 * Pure storage pre-check (spec §6): required = download + temp + extracted +
 * margin. Never begins a large download when there is clearly not enough.
 */
object StorageCalculator {

    fun check(plan: StoragePlan, usableBytes: Long): StorageCheck = StorageCheck(plan, usableBytes)

    /** Suggested cleanup targets shown in the "not enough storage" dialog. */
    fun freeSpaceHints(): List<String> = listOf(
        "Remove unused Noxs environments (Settings → Linux Environment)",
        "Clear cached setup archives (Settings → Clear Temporary Setup Files)",
        "Free device storage or move media off the device"
    )
}
