/*
 * Noxs — original implementation.
 * NoxsSdkStore: downloads, verifies and stores published Noxs Plugin SDK
 * releases, and keeps the plugin state records next to them.
 *
 * Layout inside the active rootfs home (extends the existing plugin dirs,
 * spec §5 — the plugin directories themselves are untouched):
 *
 *   ~/.noxs/
 *     plugins/<id>/plugin.json|plugin.js        (existing, unchanged)
 *     sdk/<version>/sdk.json|index.js|types.d.ts
 *     plugin-state.json
 *
 * Download pipeline (every step mandatory — nothing runs unverified):
 *   1. read the published sdk/registry.json (cached, throttled)
 *   2. locate the release entry for the required SDK version
 *   3. download the .noxs-sdk artifact (HTTPS only, size-bounded)
 *   4. verify the sha256 checksum published in the registry
 *   5. extract safely (TarGuard — traversal can never escape)
 *   6. validate sdk.json: version match + catalog equality (apiVersion and
 *      features must match the app's capability table — a manifest that
 *      claims more than the table is rejected, never trusted)
 *   7. atomically adopt sdk/<version>/ and record the digest in
 *      plugin-state.json on the next plugin install/update
 *
 * Published SDK versions are immutable: an existing verified sdk/<version>
 * directory is never re-downloaded or silently replaced.
 *
 * PluginStateStore persists each plugin's exact SDK version and integrity
 * information (plugin-state.json). PluginSeenStore remembers which store
 * entries the user has already seen (the "New" badge). PluginStorageFile is
 * the bounded, plugin-scoped key/value store behind the SDK "storage"
 * feature (host-implemented, permission-checked in NoxsPluginRuntime).
 *
 * Pure JVM: parsing via the shared MiniJson parser, no Android imports.
 */
package com.crossberry.noxs.runtime.plugins

import com.crossberry.noxs.shared.MiniJson
import com.crossberry.noxs.shared.NoxsLog
import com.crossberry.noxs.shared.TarGuard
import com.crossberry.noxs.shared.TarReader
import java.io.File
import java.io.FileInputStream
import java.util.zip.GZIPInputStream

// ------------------------------------------------------------------ SdkJson

object SdkJson {

    const val MANIFEST_NAME = "sdk.json"
    const val ENTRY_NAME = "index.js"
    const val TYPES_NAME = "types.d.ts"
    const val SDK_PACKAGE_NAME = "noxs-plugin-sdk"
    const val REGISTRY_FORMAT = 1
    val STATUSES = listOf("stable", "deprecated")

    private fun map(text: String): Map<String, Any?> = try {
        (MiniJson.parse(text) as? Map<*, *>)?.mapKeys { it.key.toString() }
            ?: throw IllegalArgumentException("Expected a JSON object")
    } catch (e: MiniJson.JsonException) {
        throw IllegalArgumentException("Invalid JSON: ${e.message}")
    }

    private fun str(map: Map<String, Any?>, key: String): String? = map[key] as? String

    private fun strings(map: Map<String, Any?>, key: String): List<String> =
        (map[key] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()

    /** Parses sdk.json content; throws IllegalArgumentException when broken. */
    fun parseManifest(text: String): SdkManifest {
        val map = map(text)
        return SdkManifest(
            name = str(map, "name") ?: "",
            version = str(map, "version") ?: "",
            apiVersion = str(map, "apiVersion") ?: "",
            entry = str(map, "entry") ?: "",
            types = str(map, "types"),
            status = str(map, "status") ?: "",
            features = strings(map, "features")
        )
    }

    fun parseManifestFile(file: File): SdkManifest = parseManifest(
        runCatching { file.readText(Charsets.UTF_8) }
            .getOrElse { throw IllegalArgumentException("sdk.json unreadable") }
    )

    /**
     * Manifest validation (mirrors the noxs-plugins sdk validator).
     * [expectedVersion] pins the manifest to its release directory.
     */
    fun validate(manifest: SdkManifest, expectedVersion: String? = null): List<String> {
        val problems = mutableListOf<String>()
        if (manifest.name != SDK_PACKAGE_NAME) {
            problems += "name: must be \"$SDK_PACKAGE_NAME\""
        }
        if (PluginSemver.parse(manifest.version) == null) {
            problems += "version: must be MAJOR.MINOR.PATCH"
        }
        if (expectedVersion != null && manifest.version != expectedVersion) {
            problems += "version: must match the release directory ($expectedVersion)"
        }
        if (manifest.apiVersion.isBlank()) problems += "apiVersion: must identify the API generation"
        if (manifest.entry != ENTRY_NAME) problems += "entry: must be \"$ENTRY_NAME\""
        if (manifest.status !in STATUSES) {
            problems += "status: must be one of ${STATUSES.joinToString(", ")}"
        }
        if (manifest.features.isEmpty()) {
            problems += "features: must list at least one API feature"
        }
        manifest.features.filterNot { it in SdkFeatures.ALL }
            .forEach { problems += "features: unknown SDK feature \"$it\"" }
        return problems
    }

    /** Parses the published sdk/registry.json → (format, releases). */
    fun parseRegistry(text: String): Pair<Int, List<SdkRemoteEntry>> {
        val root = map(text)
        val format = (root["version"] as? Number)?.toInt() ?: 0
        val releases = (root["sdks"] as? List<*>).orEmpty().mapNotNull { item ->
            val sdk = (item as? Map<*, *>)?.mapKeys { it.key.toString() } ?: return@mapNotNull null
            val version = str(sdk, "version") ?: return@mapNotNull null
            if (PluginSemver.parse(version) == null) return@mapNotNull null
            SdkRemoteEntry(
                version = version,
                apiVersion = str(sdk, "apiVersion") ?: "",
                status = str(sdk, "status") ?: "stable",
                features = strings(sdk, "features"),
                minimumNoxsVersion = str(sdk, "minimumNoxsVersion"),
                artifact = str(sdk, "artifact"),
                checksum = str(sdk, "checksum")
            )
        }
        return format to releases
    }
}

// ------------------------------------------------------------- NoxsSdkStore

class NoxsSdkStore(
    private val sdkRoot: File,
    private val cacheDir: File,
    private val fetcher: NoxsPluginRegistry.Fetcher = HttpsFetcher()
) {

    class SdkException(message: String) : Exception(message)

    private val lock = Any()
    private val registryCache = File(cacheDir, "sdk-registry.json")
    private val registryFetchStamp = File(cacheDir, ".sdk-registry-last-fetch")

    var lastRefreshOk: Boolean = false
        private set

    // ------------------------------------------------------------ installed

    fun installedVersions(): List<String> = synchronized(lock) {
        sdkRoot.listFiles().orEmpty()
            .filter { it.isDirectory && PluginSemver.parse(it.name) != null }
            .filter { File(it, SdkJson.MANIFEST_NAME).isFile }
            .map { it.name }
            .sortedBy { PluginSemver.parse(it) ?: PluginSemver.ZERO }
    }

    fun isInstalled(version: String): Boolean {
        if (PluginSemver.parse(version) == null) return false
        val dir = versionDir(version)
        if (!dir.isDirectory) return false
        val manifest = runCatching {
            SdkJson.parseManifestFile(File(dir, SdkJson.MANIFEST_NAME))
        }.getOrNull() ?: return false
        return manifest.version == version && File(dir, SdkJson.ENTRY_NAME).isFile
    }

    fun manifest(version: String): SdkManifest? =
        runCatching { SdkJson.parseManifestFile(File(versionDir(version), SdkJson.MANIFEST_NAME)) }
            .getOrNull()?.takeIf { it.version == version }

    /** The SDK index.js source injected before plugin code. */
    fun entryJs(version: String): String? =
        runCatching { File(versionDir(version), SdkJson.ENTRY_NAME).readText(Charsets.UTF_8) }
            .getOrNull()

    // ------------------------------------------------------------ registry

    /**
     * Published SDK releases: fresh when possible, last-good cache
     * otherwise. Throttled to one network fetch per [minIntervalMs] unless
     * [force] is set.
     */
    fun remoteRegistry(force: Boolean = false, minIntervalMs: Long = REFRESH_INTERVAL_MS): List<SdkRemoteEntry> {
        return synchronized(lock) {
            val shouldFetch = force ||
                runCatching { registryFetchStamp.lastModified() }
                    .getOrDefault(0L) <= System.currentTimeMillis() - minIntervalMs ||
                !registryCache.isFile
            if (shouldFetch) {
                lastRefreshOk = runCatching { refreshRegistryLocked() }
                    .onFailure {
                        NoxsLog.w(TAG, "sdk registry refresh failed: ${it.javaClass.simpleName}")
                    }
                    .isSuccess
            } else {
                lastRefreshOk = true
            }
            readRegistryCache()
        }
    }

    private fun refreshRegistryLocked() {
        val bytes = fetcher.get(SDK_REGISTRY_URL, NoxsPluginRegistry.FETCH_TIMEOUT_MS)
        if (bytes.isEmpty() || bytes.size > NoxsPluginRegistry.MAX_REGISTRY_BYTES) {
            throw NoxsPluginRegistry.RegistryException("sdk registry payload invalid")
        }
        val text = String(bytes, Charsets.UTF_8)
        SdkJson.parseRegistry(text) // reject broken payloads before caching
        cacheDir.mkdirs()
        registryCache.writeText(text, Charsets.UTF_8)
        registryFetchStamp.writeText(System.currentTimeMillis().toString(), Charsets.UTF_8)
    }

    private fun readRegistryCache(): List<SdkRemoteEntry> {
        if (!registryCache.isFile) return emptyList()
        return runCatching {
            val (format, releases) = SdkJson.parseRegistry(registryCache.readText(Charsets.UTF_8))
            if (format != SdkJson.REGISTRY_FORMAT) throw NoxsPluginRegistry.RegistryException("sdk registry format $format")
            releases
        }.getOrElse { emptyList() }
    }

    // -------------------------------------------------------------- ensure

    /**
     * Makes sure the verified SDK [version] exists locally, downloading it
     * from the published registry when missing. Throws [SdkException] with
     * a user-stable message when the release cannot be provided.
     */
    fun ensure(version: String, forceCheck: Boolean = false, onProgress: (Int) -> Unit = {}): File {
        if (PluginSemver.parse(version) == null) {
            throw SdkException("The requested Noxs Plugin SDK version is invalid")
        }
        synchronized(lock) {
            if (!forceCheck && isInstalled(version)) return versionDir(version)
            val remote = remoteRegistry().firstOrNull { it.version == version }
                ?: throw SdkException("Noxs Plugin SDK $version is not published for this Noxs release")
            val artifact = remote.artifact?.takeIf { it.startsWith("https://") }
                ?: throw SdkException("Noxs Plugin SDK $version has no published release artifact")
            val checksum = remote.checksum?.lowercase()?.takeIf { it.isNotBlank() }
                ?: throw SdkException("Noxs Plugin SDK $version was published without a checksum")

            onProgress(20)
            val bytes = try {
                fetcher.get(artifact, ARTIFACT_TIMEOUT_MS)
            } catch (e: Exception) {
                NoxsLog.w(TAG, "sdk fetch failed: ${e.javaClass.simpleName}")
                throw SdkException("The Noxs Plugin SDK could not be downloaded")
            }
            if (bytes.isEmpty() || bytes.size > MAX_SDK_BYTES) {
                throw SdkException("The Noxs Plugin SDK download has an invalid size")
            }
            if (PluginChecksum.sha256(bytes) != checksum) {
                throw SdkException("The Noxs Plugin SDK failed its integrity check")
            }
            onProgress(60)

            val staging = File(sdkRoot, ".staging-$version-${System.nanoTime()}")
            if (!staging.mkdirs()) throw SdkException("Noxs Plugin SDK storage is not writable")
            try {
                extract(bytes, staging)
                validateStaged(staging, version)
                onProgress(85)
                val target = versionDir(version)
                if (target.exists()) target.deleteRecursively()
                if (!staging.renameTo(target)) {
                    staging.copyRecursively(target, overwrite = true)
                    staging.deleteRecursively()
                }
                NoxsLog.i(TAG, "sdk installed: $version")
            } catch (e: SdkException) {
                throw e
            } catch (e: Exception) {
                NoxsLog.w(TAG, "sdk extract failed: ${e.javaClass.simpleName}")
                throw SdkException("The Noxs Plugin SDK could not be unpacked")
            } finally {
                if (staging.exists()) staging.deleteRecursively()
            }
            onProgress(100)
            return versionDir(version)
        }
    }

    /** Safe extraction of the .noxs-sdk gzipped tar (same rules as plugins). */
    private fun extract(bytes: ByteArray, into: File) {
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
                        TarReader.Type.DIR -> Unit
                        else -> throw SdkException("The Noxs Plugin SDK package contains unsupported entries")
                    }
                }
            }
        } finally {
            payload.delete()
        }
    }

    /**
     * The staged release must carry a valid sdk.json pinned to [version]
     * whose apiVersion and features match the app's capability table
     * exactly — a manifest claiming unknown capabilities is rejected.
     */
    private fun validateStaged(staged: File, version: String) {
        val manifestFile = File(staged, SdkJson.MANIFEST_NAME)
        if (!manifestFile.isFile) throw SdkException("The Noxs Plugin SDK package has no sdk.json")
        val manifest = try {
            SdkJson.parseManifestFile(manifestFile)
        } catch (e: IllegalArgumentException) {
            throw SdkException("The Noxs Plugin SDK package has an invalid manifest")
        }
        val problems = SdkJson.validate(manifest, version)
        if (problems.isNotEmpty()) {
            throw SdkException("The Noxs Plugin SDK package has an invalid manifest")
        }
        if (!File(staged, SdkJson.ENTRY_NAME).isFile) {
            throw SdkException("The Noxs Plugin SDK package has no runtime entry")
        }
        val catalogRelease = PluginSdkCatalog.known(version)
        if (catalogRelease != null &&
            (manifest.apiVersion != catalogRelease.apiVersion ||
                manifest.features.toSet() != catalogRelease.features)
        ) {
            throw SdkException("The Noxs Plugin SDK manifest does not match the Noxs compatibility table")
        }
    }

    private fun versionDir(version: String): File = File(sdkRoot, version)

    companion object {
        private const val TAG = "NoxsSdkStore"
        const val PAYLOAD_NAME = ".payload.tar"
        const val ARTIFACT_TIMEOUT_MS = 60_000
        const val MAX_SDK_BYTES = 8L * 1024L * 1024L
        const val REFRESH_INTERVAL_MS = 5L * 60L * 1000L
        const val SDK_REGISTRY_URL =
            "https://raw.githubusercontent.com/web12-app/noxs-plugins/main/sdk/registry.json"
    }
}

// -------------------------------------------------------- PluginStateStore

/** One plugin's recorded install state (plugin-state.json). */
data class PluginStateRecord(
    val pluginVersion: String,
    val sdkVersion: String?,
    val sdkChecksum: String?
)

/**
 * Persists per-plugin install state — the exact SDK version and integrity
 * digest recorded at install/update time — in ~/.noxs/plugin-state.json.
 * JSON is hand-serialized (MiniJson parses but does not write) with strict
 * escaping; every write is atomic (tmp + rename).
 */
class PluginStateStore(private val noxsDir: File) {

    private val lock = Any()
    private val file: File get() = File(noxsDir, STATE_FILE)

    @Synchronized
    fun record(pluginId: String, pluginVersion: String, sdkVersion: String?, sdkChecksum: String?) {
        if (!PluginJson.validId(pluginId)) return
        val all = readAll().toMutableMap()
        all[pluginId] = PluginStateRecord(pluginVersion, sdkVersion, sdkChecksum)
        writeAll(all)
    }

    @Synchronized
    fun get(pluginId: String): PluginStateRecord? = readAll()[pluginId]

    @Synchronized
    fun remove(pluginId: String) {
        val all = readAll()
        if (pluginId !in all) return
        writeAll(all - pluginId)
    }

    @Synchronized
    fun all(): Map<String, PluginStateRecord> = readAll()

    private fun readAll(): Map<String, PluginStateRecord> {
        if (!file.isFile) return emptyMap()
        return runCatching {
            val root = MiniJson.parse(file.readText(Charsets.UTF_8)) as? Map<*, *> ?: return emptyMap()
            val plugins = (root["plugins"] as? Map<*, *>) ?: return emptyMap()
            plugins.entries.mapNotNull { (key, value) ->
                val id = key.toString()
                val record = value as? Map<*, *> ?: return@mapNotNull null
                id to PluginStateRecord(
                    pluginVersion = record["pluginVersion"]?.toString() ?: "",
                    sdkVersion = record["sdkVersion"]?.toString(),
                    sdkChecksum = record["sdkChecksum"]?.toString()
                )
            }.toMap()
        }.getOrElse { emptyMap() }
    }

    private fun writeAll(all: Map<String, PluginStateRecord>) {
        runCatching {
            noxsDir.mkdirs()
            val body = buildString {
                append("{\n  \"version\": 1,\n  \"plugins\": {\n")
                val entries = all.entries.sortedBy { it.key }
                entries.forEachIndexed { index, (id, record) ->
                    append("    ")
                    append(quoteString(id))
                    append(": {\"pluginVersion\": ")
                    append(quoteString(record.pluginVersion))
                    append(", \"sdkVersion\": ")
                    append(record.sdkVersion?.let { quoteString(it) } ?: "null")
                    append(", \"sdkChecksum\": ")
                    append(record.sdkChecksum?.let { quoteString(it) } ?: "null")
                    append("}")
                    if (index != entries.lastIndex) append(",")
                    append("\n")
                }
                append("  }\n}\n")
            }
            val tmp = File(noxsDir, ".$STATE_FILE.tmp")
            tmp.writeText(body, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(body, Charsets.UTF_8)
                tmp.delete()
            }
        }.onFailure { NoxsLog.w("PluginState", "state write failed: ${it.javaClass.simpleName}") }
    }

    companion object {
        const val STATE_FILE = "plugin-state.json"

        /** Minimal JSON string literal with full control-char escaping. */
        fun quoteString(text: String): String = buildString {
            append('"')
            text.forEach { ch ->
                when (ch) {
                    '\\' -> append(BACKSLASH).append(BACKSLASH)
                    '"' -> append(BACKSLASH).append('"')
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    '\b' -> append("\\b")
                    '\u000C' -> append(BACKSLASH).append('f')
                    else ->
                        if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
                }
            }
            append('"')
        }

        /** The escape introducer — kept as a constant so the escaping rules
          * stay readable next to the when-branches above. */
        private val BACKSLASH: Char = '\\'
    }
}

// --------------------------------------------------------- PluginSeenStore

/**
 * Remembers which registry plugin ids the user has already seen, backing
 * the store's "New" badge (a compatible entry not seen before). Stored in
 * the app cache dir — losing it only re-shows badges.
 */
class PluginSeenStore(private val cacheDir: File) {

    private val lock = Any()
    private val file: File get() = File(cacheDir, "seen-plugins.json")

    @Synchronized
    fun seen(): Set<String> {
        if (!file.isFile) return emptySet()
        return runCatching {
            val root = MiniJson.parse(file.readText(Charsets.UTF_8)) as? Map<*, *>
            (root?.get("seen") as? List<*>)?.mapNotNull { it as? String }?.toSet().orEmpty()
        }.getOrDefault(emptySet())
    }

    @Synchronized
    fun isNew(id: String): Boolean = id !in seen()

    @Synchronized
    fun markSeen(ids: Collection<String>) {
        val valid = ids.filter { PluginJson.validId(it) }
        if (valid.isEmpty()) return
        val updated = seen() + valid
        runCatching {
            cacheDir.mkdirs()
            val body = buildString {
                append("{\n  \"seen\": [")
                updated.sorted().forEachIndexed { index, id ->
                    if (index > 0) append(",")
                    append("\n    ")
                    append(PluginStateStore.quoteString(id))
                }
                if (updated.isNotEmpty()) append("\n  ")
                append("]\n}\n")
            }
            val tmp = File(cacheDir, ".seen-plugins.tmp")
            tmp.writeText(body, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(body, Charsets.UTF_8)
                tmp.delete()
            }
        }
    }
}

// ------------------------------------------------------- PluginStorageFile

/**
 * Bounded, plugin-scoped key/value storage backing the SDK "storage"
 * feature. Values are strings (the SDK layer JSON-encodes typed values);
 * the file lives inside the plugin's own directory so uninstall removes it.
 */
object PluginStorageFile {

    const val FILE_NAME = ".storage.json"
    const val MAX_FILE_BYTES = 128 * 1024
    const val MAX_KEY_LENGTH = 128
    const val MAX_VALUE_BYTES = 32 * 1024
    const val MAX_ENTRIES = 256

    /** Loads the stored map; a missing or broken file reads as empty. */
    fun load(file: File): Map<String, String> {
        if (!file.isFile || file.length() > MAX_FILE_BYTES) return emptyMap()
        return runCatching {
            val root = MiniJson.parse(file.readText(Charsets.UTF_8)) as? Map<*, *>
            val data = root?.get("data") as? Map<*, *> ?: return emptyMap()
            data.entries.associate { it.key.toString() to (it.value?.toString() ?: "") }
        }.getOrDefault(emptyMap())
    }

    /** Stores [data]; returns false when the result would exceed the bounds. */
    fun store(file: File, data: Map<String, String>): Boolean {
        if (data.size > MAX_ENTRIES) return false
        if (data.keys.any { it.isEmpty() || it.length > MAX_KEY_LENGTH }) return false
        if (data.values.any { it.length > MAX_VALUE_BYTES }) return false
        val body = buildString {
            append("{\n  \"version\": 1,\n  \"data\": {\n")
            val entries = data.entries.sortedBy { it.key }
            entries.forEachIndexed { index, (key, value) ->
                append("    ")
                append(PluginStateStore.quoteString(key))
                append(": ")
                append(PluginStateStore.quoteString(value))
                if (index != entries.lastIndex) append(",")
                append("\n")
            }
            append("  }\n}\n")
        }
        if (body.length > MAX_FILE_BYTES) return false
        return runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, ".${file.name}.tmp")
            tmp.writeText(body, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(body, Charsets.UTF_8)
                tmp.delete()
            }
            true
        }.getOrDefault(false)
    }
}
