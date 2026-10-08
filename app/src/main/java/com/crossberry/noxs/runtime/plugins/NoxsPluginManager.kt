/*
 * Noxs — original implementation.
 * NoxsPluginManager: the single Plugin Store service. The store UI, the
 * plugin details page, the plugin runtime and the `nx plug` CLI bridge all
 * call THIS object — there is no second plugin implementation.
 *
 * Construction is cheap and state lives on disk (registry cache under the
 * app cache dir, installations inside the active rootfs), so activities and
 * the service each build one against the active environment's paths.
 */
package com.crossberry.noxs.runtime.plugins

import com.crossberry.noxs.shared.NoxsLog
import java.io.File

class NoxsPluginManager(
    private val rootfsHome: File,
    cacheDir: File,
    private val appVersion: String,
    fetcher: NoxsPluginRegistry.Fetcher = HttpsFetcher(),
    guestBinDir: File? = null
) {

    val registry = NoxsPluginRegistry(File(cacheDir, "plugins"), fetcher)
    val installer = NoxsPluginInstaller(File(rootfsHome, PLUGINS_DIR), appVersion, fetcher, guestBinDir)

    class ManagerException(message: String) : Exception(message)

    // ------------------------------------------------------------- queries

    /** All registry entries (last-good cache when offline). */
    fun catalog(refresh: Boolean = true): List<RegistryEntry> = registry.entries(refresh)

    /** Shared search over the catalog (store UI + CLI). */
    fun search(query: String, refresh: Boolean = true): List<RegistryEntry> =
        registry.search(catalog(refresh), query)

    fun installed(): List<InstalledPlugin> = installer.listInstalled()

    fun installed(id: String): InstalledPlugin? = installer.installed(id)

    /** Store view-model for one plugin: registry entry + local state. */
    data class StoreCard(
        val entry: RegistryEntry,
        val installed: InstalledPlugin?,
        val updateAvailable: Boolean
    )

    fun storeCards(refresh: Boolean = true): List<StoreCard> {
        val installedById = installed().associateBy { it.meta.id }
        return catalog(refresh).map { entry ->
            val local = installedById[entry.id]
            StoreCard(entry, local, local != null && installer.updateAvailable(entry, local))
        }
    }

    /** Plugins with a newer registry version (store "Updates" filter). */
    fun updates(refresh: Boolean = true): List<StoreCard> = storeCards(refresh).filter { it.updateAvailable }

    // ------------------------------------------------------------ lifecycle

    fun install(id: String, onProgress: (Int) -> Unit = {}): InstalledPlugin {
        val entry = registry.entry(id)
            ?: throw ManagerException("Unknown plugin")
        return installer.install(entry, onProgress)
    }

    fun update(id: String, onProgress: (Int) -> Unit = {}): InstalledPlugin {
        val entry = registry.entry(id)
            ?: throw ManagerException("Unknown plugin")
        val current = installer.installed(id)
            ?: throw ManagerException("Plugin is not installed")
        return installer.update(entry, current, onProgress)
    }

    fun uninstall(id: String) {
        installer.uninstall(id)
        NoxsLog.i("PluginManager", "plugin uninstalled: $id")
    }

    fun enable(id: String) = installer.enable(id)

    fun disable(id: String) = installer.disable(id)

    /** CLI-facing status line fields for one plugin id (or null). */
    fun infoLine(id: String): String? {
        val entry = registry.entry(id)
        val local = installed(id)
        if (entry == null && local == null) return null
        val meta = local?.meta
        val version = meta?.version ?: entry?.version ?: "?"
        val name = meta?.name ?: entry?.name ?: id
        val description = meta?.description ?: entry?.description ?: ""
        val permissions = (meta?.permissions ?: entry?.permissions ?: emptyList()).joinToString(",")
        val category = meta?.category ?: entry?.category ?: "-"
        val state = when {
            local == null -> "available"
            local.enabled -> "installed"
            else -> "disabled"
        }
        return "$id\t$name\t$version\t$state\t$category\t$permissions\t$description"
    }

    companion object {
        const val PLUGINS_DIR = ".noxs/plugins"
    }
}
