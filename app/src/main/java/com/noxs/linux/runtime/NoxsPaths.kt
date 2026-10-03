/*
 * Noxs — original implementation.
 * App-private filesystem layout. Everything lives under the app sandbox;
 * Android's real system filesystem is never modified.
 */
package com.noxs.linux.runtime

import android.content.Context
import com.noxs.linux.shared.NoxsConstants
import java.io.File

class NoxsPaths(baseDir: File, val nativeLibDir: File? = null) {

    /**
     * Android constructor — rooted in the app-private filesDir; native libs
     * resolve to [Context#getApplicationInfo].nativeLibraryDir, the ONLY
     * location Android allows execve() from on Android 10+ (W^X / SELinux:
     * exec of files in app data storage is denied since targetSdk 29).
     */
    constructor(context: Context) : this(
        File(context.filesDir, NoxsConstants.ANDROID_BASE_DIR),
        File(context.applicationInfo.nativeLibraryDir)
    )

    val base: File = baseDir
    val rootfs: File = File(base, NoxsConstants.ANDROID_ROOTFS_DIR)
    val bin: File = File(base, NoxsConstants.ANDROID_BIN_DIR)
    val run: File = File(base, NoxsConstants.ANDROID_RUN_DIR)
    val cache: File = File(base, NoxsConstants.ANDROID_CACHE_DIR)
    val logs: File = File(base, NoxsConstants.ANDROID_LOGS_DIR)
    val tmp: File = File(base, NoxsConstants.ANDROID_TMP_DIR)

    /**
     * proot ships inside the APK as jniLibs (libproot.so) so the OS extracts
     * it into nativeLibraryDir where execve is permitted. JVM tests (no
     * native dir) fall back to the bin/ layout.
     */
    val prootBinary: File
        get() = nativeLibDir?.let { File(it, "libproot.so") } ?: File(bin, "proot")
    val installMarker: File = File(base, NoxsConstants.INSTALL_COMPLETE_MARKER)

    // Rootfs-internal virtual paths (physical files under rootfs/)
    val rootfsEtc: File get() = File(rootfs, "etc")
    val rootfsRun: File get() = File(rootfs, NoxsConstants.RUN_DIR)
    val rootfsVarRun: File get() = File(rootfs, NoxsConstants.VAR_RUN_DIR)
    val rootfsNoxsRun: File get() = File(rootfs, NoxsConstants.NOXS_RUN_DIR)
    val rootfsHostRun: File get() = File(rootfs, NoxsConstants.HOST_RUN_DIR)
    val rootfsHomeNoxs: File get() = File(rootfs, NoxsConstants.DEFAULT_USER_HOME)
    val rootfsServices: File get() = File(rootfs, NoxsConstants.NOXS_SERVICE_DIR)
    val noxsResourcesConf: File = File(rootfs, "etc/noxs/resources.conf")

    fun ensureBaseDirs(): Boolean =
        listOf(base, bin, run, cache, logs, tmp).all { it.isDirectory || it.mkdirs() }

    fun isInstalled(): Boolean = installMarker.isFile && prootBinary.isFile && rootfs.isDirectory

    fun dirSize(dir: File): Long = try {
        dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    } catch (e: Exception) {
        0L
    }
}
