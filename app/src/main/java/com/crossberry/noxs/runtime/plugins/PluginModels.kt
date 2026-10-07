/*
 * Noxs — original implementation.
 * Noxs Plugin Store data model: plugin metadata, registry entries and the
 * validation contract shared by the Plugin Store UI, the `nx plug` CLI and
 * the installer. Parsing goes through the shared MiniJson parser so the
 * whole model layer stays JVM-testable (no Android or org.json dependency).
 *
 * Security model: metadata arrives from untrusted sources (a registry JSON
 * fetched over HTTPS, a plugin.json inside a downloaded archive). Every
 * field is re-validated here — the store never trusts a parsed field.
 */
package com.crossberry.noxs.runtime.plugins

import com.crossberry.noxs.shared.MiniJson
import java.io.File

/** Permissions a Noxs plugin can declare. The host enforces every one. */
object PluginPermissions {
    val KNOWN = setOf(
        "ui", "terminal", "filesystem", "storage",
        "network", "notifications", "background"
    )
    const val UI = "ui"
    const val TERMINAL = "terminal"
    const val FILESYSTEM = "filesystem"
    const val STORAGE = "storage"
    const val NETWORK = "network"
    const val NOTIFICATIONS = "notifications"
    const val BACKGROUND = "background"
}

/** Semantic version (MAJOR.MINOR.PATCH) with ordering, Noxs-compatible. */
data class PluginSemver(val major: Int, val minor: Int, val patch: Int) : Comparable<PluginSemver> {

    override fun compareTo(other: PluginSemver): Int {
        major.compareTo(other.major).let { if (it != 0) return it }
        minor.compareTo(other.minor).let { if (it != 0) return it }
        return patch.compareTo(other.patch)
    }

    companion object {
        val ZERO = PluginSemver(0, 0, 0)
        private val RE = Regex("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)$")

        fun parse(text: String?): PluginSemver? {
            if (text.isNullOrBlank()) return null
            val match = RE.matchEntire(text.trim()) ?: return null
            return PluginSemver(
                match.groupValues[1].toInt(),
                match.groupValues[2].toInt(),
                match.groupValues[3].toInt()
            )
        }
    }
}

/** Parsed plugin.json — the metadata contract of every Noxs plugin. */
data class PluginMeta(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val main: String,
    val permissions: List<String>,
    val minimumNoxsVersion: String,
    val logo: String?,
    val readme: String?,
    val author: String?,
    val license: String?,
    val category: String?,
    val commands: List<String>,
    val keywords: List<String>,
    val homepage: String?,
    val repository: String?
) {
    val semver: PluginSemver? get() = PluginSemver.parse(version)
}

/**
 * One registry.json entry — enough for the store list page; the details
 * page fetches the README via [readmeUrl] and the artifact via [artifact].
 */
data class RegistryEntry(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val logo: String?,
    val readme: String?,
    val logoUrl: String?,
    val readmeUrl: String?,
    val release: String?,
    val permissions: List<String>,
    val category: String?,
    val keywords: List<String>,
    val minimumNoxsVersion: String?,
    val artifact: String?,
    val checksum: String?,
    val releaseTag: String?,
    val updatedAt: String?,
    /* Optional detail-page fields the registry upsert carries when the
       plugin.json declares them (spec §19 details page). */
    val author: String? = null,
    val license: String? = null,
    val repository: String? = null,
    val commands: List<String> = emptyList()
)

/** A plugin installed under ~/.noxs/plugins/<id>/ inside the active rootfs. */
data class InstalledPlugin(
    val dir: File,
    val meta: PluginMeta,
    val enabled: Boolean
)

object PluginJson {

    const val REGISTRY_FORMAT = 1
    const val RUNTIME_ENTRY = "plugin.js"
    private val ID_RE = Regex("^[a-z0-9][a-z0-9-]{1,63}$")

    private fun map(text: String): Map<String, Any?> = try {
        (MiniJson.parse(text) as? Map<*, *>)?.mapKeys { it.key.toString() }
            ?: throw IllegalArgumentException("Expected a JSON object")
    } catch (e: MiniJson.JsonException) {
        throw IllegalArgumentException("Invalid JSON: ${e.message}")
    }

    private fun str(map: Map<String, Any?>, key: String): String? = map[key] as? String

    private fun strings(map: Map<String, Any?>, key: String): List<String> =
        (map[key] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()

    /** Parses plugin.json content; throws IllegalArgumentException when broken. */
    fun parseMeta(text: String): PluginMeta {
        val map = map(text)
        return PluginMeta(
            id = str(map, "id") ?: "",
            name = str(map, "name") ?: "",
            version = str(map, "version") ?: "",
            description = str(map, "description") ?: "",
            main = str(map, "main") ?: "",
            permissions = strings(map, "permissions"),
            minimumNoxsVersion = str(map, "minimumNoxsVersion") ?: "0.0.0",
            logo = str(map, "logo"),
            readme = str(map, "readme") ?: "README.md",
            author = str(map, "author"),
            license = str(map, "license"),
            category = str(map, "category"),
            commands = strings(map, "commands"),
            keywords = strings(map, "keywords"),
            homepage = str(map, "homepage"),
            repository = str(map, "repository")
        )
    }

    fun parseMetaFile(file: File): PluginMeta = parseMeta(
        runCatching { file.readText(Charsets.UTF_8) }
            .getOrElse { throw IllegalArgumentException("plugin.json unreadable") }
    )

    /** Parses registry.json content; returns (format, entries). */
    fun parseRegistry(text: String): Pair<Int, List<RegistryEntry>> {
        val root = map(text)
        val format = (root["version"] as? Number)?.toInt() ?: 0
        val plugins = (root["plugins"] as? List<*>).orEmpty().mapNotNull { item ->
            val p = (item as? Map<*, *>)?.mapKeys { it.key.toString() } ?: return@mapNotNull null
            RegistryEntry(
                id = str(p, "id") ?: return@mapNotNull null,
                name = str(p, "name") ?: p["id"]?.toString() ?: "",
                version = str(p, "version") ?: "0.0.0",
                description = str(p, "description") ?: "",
                logo = str(p, "logo"),
                readme = str(p, "readme"),
                logoUrl = str(p, "logoUrl"),
                readmeUrl = str(p, "readmeUrl"),
                release = str(p, "release"),
                permissions = strings(p, "permissions"),
                category = str(p, "category"),
                keywords = strings(p, "keywords"),
                minimumNoxsVersion = str(p, "minimumNoxsVersion"),
                artifact = str(p, "artifact"),
                checksum = str(p, "checksum"),
                releaseTag = str(p, "releaseTag"),
                updatedAt = str(p, "updatedAt"),
                author = str(p, "author"),
                license = str(p, "license"),
                repository = str(p, "repository"),
                commands = strings(p, "commands")
            )
        }
        return format to plugins
    }

    fun validId(id: String): Boolean = ID_RE.matchEntire(id) != null
}

/**
 * The validation gate (Plugin Store build contract, mirrored from the
 * noxs-plugins repository validator). Returns all problems; an empty list
 * means the metadata may be installed.
 */
object PluginValidation {

    const val RUNTIME_ENTRY = PluginJson.RUNTIME_ENTRY

    fun validate(meta: PluginMeta): List<String> {
        val problems = mutableListOf<String>()
        if (!PluginJson.validId(meta.id)) {
            problems += "id: must match ^[a-z0-9][a-z0-9-]{1,63}$"
        }
        if (meta.name.isBlank() || meta.name.length > 60) problems += "name: must be 2-60 characters"
        if (meta.semver == null) problems += "version: must be MAJOR.MINOR.PATCH"
        if (meta.description.isBlank()) problems += "description: must describe the plugin"
        if (meta.main != RUNTIME_ENTRY) problems += "main: must be \"$RUNTIME_ENTRY\""
        if (meta.permissions.isEmpty()) problems += "permissions: declare at least \"ui\""
        meta.permissions.filterNot { it in PluginPermissions.KNOWN }
            .forEach { problems += "permissions: unknown permission \"$it\"" }
        if (PluginSemver.parse(meta.minimumNoxsVersion) == null) {
            problems += "minimumNoxsVersion: must be MAJOR.MINOR.PATCH"
        }
        if (meta.logo != null && meta.logo.isBlank()) problems += "logo: must not be empty when set"
        if (meta.readme.isNullOrBlank()) problems += "readme: must point at the README file"
        return problems
    }

    fun valid(meta: PluginMeta): Boolean = validate(meta).isEmpty()

    /** True when a Noxs [appVersion] satisfies the plugin's minimum. */
    fun compatible(minimumNoxsVersion: String?, appVersion: String): Boolean {
        val required = PluginSemver.parse(minimumNoxsVersion) ?: return true
        val actual = PluginSemver.parse(appVersion) ?: return true
        return actual >= required
    }
}
