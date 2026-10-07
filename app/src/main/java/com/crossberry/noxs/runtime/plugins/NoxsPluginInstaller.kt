/*
 * Noxs — original implementation.
 * NoxsPluginInstaller: installs, updates, removes, enables and disables
 * Noxs plugins inside the active Linux environment.
 *
 * Install pipeline (Plugin Store contract §12):
 *   1. verify the registry entry's metadata + compatibility
 *   2. download the release artifact (.noxs-plugin — a gzipped tar)
 *   3. verify the sha256 checksum when the registry carries one
 *   4. extract safely (TarGuard path resolution — traversal never escapes
 *      the plugin directory; only regular files are accepted)
 *   5. re-validate the installed plugin.json (id must match the registry)
 *   6. register: the plugin directory IS the installation record
 *
 * Storage layout (inside the active rootfs):
 *   /home/noxs/.noxs/plugins/<id>/plugin.js|plugin.json|README.md|icon.svg
 *   /home/noxs/.noxs/plugins/<id>/.disabled        (when disabled)
 *
 * A broken plugin can never crash Noxs: every failure surfaces as a
 * short, stable InstallException message — no stack traces reach users.
 */
package com.crossberry.noxs.runtime.plugins

import com.crossberry.noxs.shared.NoxsLog
import com.crossberry.noxs.shared.TarGuard
import com.crossberry.noxs.shared.TarReader
import java.io.File
import java.io.FileInputStream
import java.util.zip.GZIPInputStream

class NoxsPluginInstaller(
    private val pluginsRoot: File,
    private val appVersion: String,
    private val fetcher: NoxsPluginRegistry.Fetcher = HttpsFetcher()
) {

    class InstallException(message: String) : Exception(message)

    private val lock = Any()

    val root: File get() = pluginsRoot

    fun pluginsDir(id: String): File = File(pluginsRoot, id)

    // -------------------------------------------------------------- listing

    fun listInstalled(): List<InstalledPlugin> = synchronized(lock) {
        if (!pluginsRoot.isDirectory) return emptyList()
        pluginsRoot.listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { dir ->
                val metaFile = File(dir, META_NAME)
                if (!metaFile.isFile) return@mapNotNull null
                val meta = runCatching { PluginJson.parseMetaFile(metaFile) }.getOrNull()
                    ?: return@mapNotNull null
                if (!PluginValidation.valid(meta)) return@mapNotNull null
                InstalledPlugin(dir, meta, isEnabled(dir))
            }
            .sortedBy { it.meta.id }
    }

    fun installed(id: String): InstalledPlugin? = listInstalled().firstOrNull { it.meta.id == id }

    fun isEnabled(dir: File): Boolean = !File(dir, DISABLED_MARKER).isFile

    fun isInstalled(id: String): Boolean = installed(id) != null

    /** Update available when the registry version is newer semver-wise. */
    fun updateAvailable(entry: RegistryEntry, current: InstalledPlugin): Boolean {
        val registryVersion = PluginSemver.parse(entry.version) ?: return false
        val installedVersion = PluginSemver.parse(current.meta.version) ?: return true
        return registryVersion > installedVersion
    }

    // ------------------------------------------------------------- install

    /**
     * Installs (or replaces) a plugin from a registry entry.
     * Synchronous — call off the main thread; artifacts are small and the
     * store surfaces progress through [onProgress] percentages.
     */
    fun install(entry: RegistryEntry, onProgress: (Int) -> Unit = {}): InstalledPlugin = synchronized(lock) {
        // 1. metadata + compatibility gate (registry entries are untrusted)
        val entryMeta = PluginMeta(
            id = entry.id, name = entry.name, version = entry.version,
            description = entry.description, main = PluginJson.RUNTIME_ENTRY,
            permissions = entry.permissions.ifEmpty { listOf(PluginPermissions.UI) },
            minimumNoxsVersion = entry.minimumNoxsVersion ?: "0.0.0",
            logo = entry.logo, readme = entry.readme,
            author = null, license = null, category = entry.category,
            commands = emptyList(), keywords = entry.keywords,
            homepage = null, repository = null
        )
        if (PluginValidation.validate(entryMeta).isNotEmpty()) {
            throw InstallException("Registry metadata for this plugin is invalid")
        }
        if (!PluginValidation.compatible(entry.minimumNoxsVersion, appVersion)) {
            throw InstallException("This plugin needs Noxs ${entry.minimumNoxsVersion} or newer")
        }
        val artifact = entry.artifact?.takeIf { it.startsWith("https://") }
            ?: throw InstallException("No release artifact is published for this plugin yet")

        // 2-3. download + integrity check
        onProgress(20)
        val bytes = try {
            fetcher.get(artifact, ARTIFACT_TIMEOUT_MS)
        } catch (e: Exception) {
            NoxsLog.w("PluginInstaller", "artifact fetch failed: ${e.javaClass.simpleName}")
            throw InstallException("The plugin package could not be downloaded")
        }
        if (bytes.isEmpty() || bytes.size > MAX_ARTIFACT_BYTES) {
            throw InstallException("The plugin package has an invalid size")
        }
        val checksum = entry.checksum?.lowercase()
        if (!checksum.isNullOrBlank() && PluginChecksum.sha256(bytes) != checksum) {
            throw InstallException("The plugin package failed its integrity check")
        }
        onProgress(60)

        // 4-5. extract into staging, validate, then swap into place.
        val target = pluginsDir(entry.id)
        val staging = File(pluginsRoot, ".staging-${entry.id}-${System.nanoTime()}")
        if (!staging.mkdirs()) throw InstallException("Plugin storage is not writable")
        try {
            extractArtifact(bytes, staging)
            validateStaged(staging, entry.id, entry.version)
            onProgress(85)
            if (target.exists()) target.deleteRecursively()
            if (!staging.renameTo(target)) {
                staging.copyRecursively(target, overwrite = true)
                staging.deleteRecursively()
            }
        } catch (e: InstallException) {
            throw e
        } catch (e: Exception) {
            NoxsLog.w("PluginInstaller", "extract failed: ${e.javaClass.simpleName}")
            throw InstallException("The plugin package could not be unpacked")
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
        onProgress(100)
        installed(entry.id) ?: throw InstallException("The plugin could not be registered")
    }

    /** Updates a plugin to its registry version (preserving enabled state). */
    fun update(entry: RegistryEntry, current: InstalledPlugin, onProgress: (Int) -> Unit = {}): InstalledPlugin {
        if (!updateAvailable(entry, current)) return current
        val wasEnabled = current.enabled
        val result = install(entry, onProgress)
        if (!wasEnabled) disable(result.meta.id)
        return installed(result.meta.id) ?: result
    }

    // ----------------------------------------------------------- lifecycle

    fun uninstall(id: String) {
        if (!PluginJson.validId(id)) throw InstallException("Invalid plugin id")
        val dir = pluginsDir(id)
        if (!dir.isDirectory) throw InstallException("Plugin is not installed")
        if (!dir.deleteRecursively()) {
            NoxsLog.w("PluginInstaller", "partial uninstall of $id")
        }
    }

    fun enable(id: String) {
        File(requireInstalledDir(id), DISABLED_MARKER).delete()
    }

    fun disable(id: String) {
        File(requireInstalledDir(id), DISABLED_MARKER).writeText("disabled\n", Charsets.UTF_8)
    }

    private fun requireInstalledDir(id: String): File {
        if (!PluginJson.validId(id)) throw InstallException("Invalid plugin id")
        val dir = pluginsDir(id)
        if (!dir.isDirectory || !File(dir, META_NAME).isFile) {
            throw InstallException("Plugin is not installed")
        }
        return dir
    }

    // ------------------------------------------------------------ internals

    /**
     * Safe extraction of the .noxs-plugin gzipped tar. Every entry is
     * resolved through TarGuard; only regular files are accepted (plugins
     * are data, not system images).
     */
    private fun extractArtifact(bytes: ByteArray, into: File) {
        val payload = File(into, PAYLOAD_NAME)
        payload.writeBytes(bytes)
        try {
            GZIPInputStream(FileInputStream(payload)).use { gzip ->
                TarReader(gzip).readAll { entry, stream ->
                    when (entry.type) {
                        TarReader.Type.REGULAR -> {
                            val dest = TarGuard.safeResolve(into, entry.name)
                            dest.parentFile?.mkdirs()
                            dest.outputStream().use { out -> stream.copyTo(out) }
                        }
                        // Directories are implicit; anything else is rejected.
                        TarReader.Type.DIR -> Unit
                        else -> throw InstallException("The plugin package contains unsupported entries")
                    }
                }
            }
        } finally {
            payload.delete()
        }
    }

    /** The staged tree must carry a valid plugin.json matching the registry. */
    private fun validateStaged(staged: File, expectedId: String, expectedVersion: String) {
        val metaFile = File(staged, META_NAME)
        if (!metaFile.isFile) throw InstallException("The plugin package has no plugin.json")
        val meta = try {
            PluginJson.parseMetaFile(metaFile)
        } catch (e: IllegalArgumentException) {
            throw InstallException("The plugin package has invalid metadata")
        }
        if (PluginValidation.validate(meta).isNotEmpty()) {
            throw InstallException("The plugin package has invalid metadata")
        }
        if (meta.id != expectedId) {
            throw InstallException("The plugin package does not match its registry entry")
        }
        // A package whose version differs from the registry (stale or
        // tampered artifact) must not silently install as a different release.
        if (meta.version != expectedVersion) {
            throw InstallException("The plugin package does not match its registry entry")
        }
        if (!File(staged, PluginJson.RUNTIME_ENTRY).isFile) {
            throw InstallException("The plugin package has no runtime entry")
        }
    }

    companion object {
        const val DISABLED_MARKER = ".disabled"
        const val META_NAME = "plugin.json"
        const val PAYLOAD_NAME = ".payload.tar"
        const val ARTIFACT_TIMEOUT_MS = 60_000
        const val MAX_ARTIFACT_BYTES = 32L * 1024L * 1024L
    }
}
