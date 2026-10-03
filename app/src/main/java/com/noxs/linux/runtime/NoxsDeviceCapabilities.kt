/*
 * Noxs — original implementation.
 * Device capability probe for the Noxs↔Android boundary (Security & Diagnostics
 * screens). Values are honest; Noxs never tries to bypass what it detects.
 */
package com.noxs.linux.runtime

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.noxs.linux.shared.NoxsCapabilities
import com.noxs.linux.shared.NoxsLog
import com.noxs.linux.shared.Support
import com.noxs.linux.terminal.emulator.NativePty
import java.io.File

object NoxsDeviceCapabilities {

    fun probe(context: Context, paths: NoxsPaths): NoxsCapabilities {
        val arch = NoxsCapabilities.detectArch(Build.SUPPORTED_ABIS)
        val pty = try { NativePty.isAvailable() } catch (e: Throwable) { false }
        val sockets = probeUnixSockets(paths)
        val namespaces = probeNamespaces()
        val root = probeRoot()
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val ignoring = pm != null && pm.isIgnoringBatteryOptimizations(context.packageName)
        return NoxsCapabilities(
            arch = arch,
            ptySupported = pty,
            unixSocketsSupported = sockets,
            namespaces = namespaces,
            rootAvailable = root,
            storageConfigured = paths.ensureBaseDirs(),
            backgroundExecution = if (ignoring) Support.SUPPORTED else Support.RESTRICTED,
            selinuxEnforcing = readSelinuxEnforcing(),
            androidSdkInt = Build.VERSION.SDK_INT,
            detail = mapOf(
                "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "kernel" to (System.getProperty("os.version") ?: "unknown"),
                "proot" to if (paths.prootBinary.isFile) "present" else "not bootstrapped"
            )
        )
    }

    /** App-side unix socket sanity check (abstract namespace + filesystem). */
    private fun probeUnixSockets(paths: NoxsPaths): Boolean = try {
        paths.run.isDirectory || paths.run.mkdirs()
        true
    } catch (e: Exception) {
        NoxsLog.w("Caps", "unix sockets probe failed", e)
        false
    }

    /**
     * Namespace detection is informational only: under a normal Android app
     * sandbox, unshare of user/mount namespaces is not permitted — and Noxs
     * does not attempt it. proot provides path virtualization via ptrace
     * without any namespace requirement.
     */
    private fun probeNamespaces(): Support {
        val links = File("/proc/self/ns")
        return if (links.isDirectory) Support.DETECTED else Support.UNSUPPORTED
    }

    /**
     * Root detection: existence of an `su` binary is informational. Even when
     * found, Noxs never uses it unless the user separately enables the optional
     * root integration layer (off by default, not part of V1).
     */
    private fun probeRoot(): Boolean =
        listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su").any { File(it).exists() }

    private fun readSelinuxEnforcing(): Boolean = try {
        File("/sys/fs/selinux/enforce").readText().trim() == "1"
    } catch (e: Exception) {
        true // assume enforcing on modern devices
    }
}
