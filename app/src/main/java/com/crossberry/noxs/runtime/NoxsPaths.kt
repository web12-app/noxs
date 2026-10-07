/*
 * Noxs — original implementation.
 * App-private filesystem layout. Everything lives under the app sandbox;
 * Android's real system filesystem is never modified.
 */
package com.crossberry.noxs.runtime

import android.content.Context
import com.crossberry.noxs.shared.NoxsConstants
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
    val storageControl: File = File(run, "storage")
    val storageRequests: File = File(storageControl, "requests")
    val storageResponses: File = File(storageControl, "responses")
    val storagePayloads: File = File(storageControl, "payloads")
    val storageResponsePayloads: File = File(storageControl, "response-payloads")
    val webControl: File = File(run, "web")
    val webRequests: File = File(webControl, "requests")
    val webResponses: File = File(webControl, "responses")
    // Guest→Android `nx env` bridge (environment list / install / remove / use)
    val envControl: File = File(run, "env")
    val envRequests: File = File(envControl, "requests")
    val envResponses: File = File(envControl, "responses")
    val envSnapshot: File = File(envControl, "registry.txt")
    val envProvidersSnapshot: File = File(envControl, "providers.txt")
    // Guest→Android `nx plug` bridge (Noxs Plugin Store)
    val pluginControl: File = File(run, "plugin")
    val pluginRequests: File = File(pluginControl, "requests")
    val pluginResponses: File = File(pluginControl, "responses")
    val pluginCatalogSnapshot: File = File(pluginControl, "catalog.txt")
    val pluginInstalledSnapshot: File = File(pluginControl, "plugins.txt")
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
    val prootLoaderBinary: File
        get() = nativeLibDir?.let { File(it, "libproot-loader.so") } ?: File(bin, "proot-loader")
    val prootLoader32Binary: File
        get() = nativeLibDir?.let { File(it, "libproot-loader32.so") } ?: File(bin, "proot-loader32")
    val installMarker: File = File(base, NoxsConstants.INSTALL_COMPLETE_MARKER)
    val aptReadyMarker: File = File(base, "apt-bootstrap-v1.ready")

    // Rootfs-internal virtual paths (physical files under rootfs/)
    val rootfsEtc: File get() = File(rootfs, "etc")
    val rootfsRun: File get() = File(rootfs, NoxsConstants.RUN_DIR)
    val rootfsVarRun: File get() = File(rootfs, NoxsConstants.VAR_RUN_DIR)
    val rootfsNoxsRun: File get() = File(rootfs, NoxsConstants.NOXS_RUN_DIR)
    val rootfsHostRun: File get() = File(rootfs, NoxsConstants.HOST_RUN_DIR)
    val rootfsHomeNoxs: File get() = File(rootfs, NoxsConstants.DEFAULT_USER_HOME)
    /** Plugin Store installations live inside the active environment. */
    val rootfsPluginsDir: File get() = File(rootfsHomeNoxs, ".noxs/plugins")
    val rootfsServices: File get() = File(rootfs, NoxsConstants.NOXS_SERVICE_DIR)
    val noxsResourcesConf: File = File(rootfs, "etc/noxs/resources.conf")

    fun ensureBaseDirs(): Boolean =
        listOf(base, bin, run, storageRequests, storageResponses, storagePayloads, storageResponsePayloads, webRequests, webResponses, envRequests, envResponses, pluginRequests, pluginResponses, cache, logs, tmp)
            .all { it.isDirectory || it.mkdirs() }

    fun isInstalled(): Boolean = installMarker.isFile && prootBinary.isFile && rootfs.isDirectory

    fun dirSize(dir: File): Long = try {
        dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    } catch (e: Exception) {
        0L
    }
}
