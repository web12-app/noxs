/*
 * Noxs — original implementation.
 * Runtime capability model for the Noxs↔Android boundary. Values are honestly
 * reported to the user (Security/Diagnostics screens); Noxs never attempts to
 * bypass or hide Android restrictions.
 */
package com.noxs.linux.shared

enum class Support { SUPPORTED, UNSUPPORTED, DETECTED, RESTRICTED, UNKNOWN }

data class NoxsCapabilities(
    val arch: String,
    val ptySupported: Boolean,
    val unixSocketsSupported: Boolean,
    val namespaces: Support,
    val rootAvailable: Boolean,
    val storageConfigured: Boolean,
    val backgroundExecution: Support,
    val selinuxEnforcing: Boolean,
    val androidSdkInt: Int,
    val detail: Map<String, String>
) {
    fun toDisplayLines(): List<Pair<String, String>> = listOf(
        "Architecture" to arch,
        "PTY" to if (ptySupported) "supported" else "fallback (pipes)",
        "Unix sockets" to if (unixSocketsSupported) "supported" else "unsupported",
        "Namespaces" to when (namespaces) {
            Support.DETECTED -> "detected"
            Support.UNSUPPORTED -> "unsupported (sandboxed)"
            else -> "unknown"
        },
        "Root" to if (rootAvailable) "detected (optional integration layer only)" else "not available — Noxs stays inside its sandbox",
        "Storage access" to if (storageConfigured) "configured (app-private)" else "not configured",
        "Background execution" to when (backgroundExecution) {
            Support.SUPPORTED -> "supported"
            Support.RESTRICTED -> "restricted by Android/OEM"
            else -> "unknown"
        },
        "SELinux" to if (selinuxEnforcing) "enforcing (respected)" else "permissive/unknown (respected)",
        "Android API" to androidSdkInt.toString()
    )

    companion object {
        /** Architecture priority: ARM64 first, per Noxs targeting. */
        fun detectArch(supportedAbis: Array<String>): String {
            val priority = listOf("arm64-v8a", "x86_64", "armeabi-v7a")
            for (abi in priority) {
                if (supportedAbis.contains(abi)) return abi
            }
            return supportedAbis.firstOrNull() ?: "arm64-v8a"
        }
    }
}
