/*
 * Noxs — original implementation.
 * NoxsPluginManager: the single Plugin Store service. The store UI, the
 * plugin details page, the plugin runtime and the `nx plug` CLI bridge all
 * call THIS object — there is no second plugin implementation.
 *
 * Construction is cheap and state lives on disk (registry cache under the
 * app cache dir, installations, SDK releases and plugin-state.json inside
 * the active rootfs home), so activities and the service each build one
 * against the active environment's paths.
 *
 * SDK integration: every store card carries a PluginCompatInfo (compatible
 * / sdk_missing / sdk_incompatible / app_update_required / blocked / …)
 * computed from the PluginSdkCatalog capability table; installs and
 * updates resolve the plugin's SDK requirements first, download and verify
 * the selected SDK release, and record the exact version + digest in
 * plugin-state.json. Incompatible requirements never reach the installer.
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
    val sdkStore = NoxsSdkStore(File(rootfsHome, SDK_DIR), File(cacheDir, "plugins"), fetcher)
    val stateStore = PluginStateStore(File(rootfsHome, NOXS_DIR))
    val seenStore = PluginSeenStore(File(cacheDir, "plugins"))

    class ManagerException(message: String) : Exception(message)

    // ------------------------------------------------------------- queries

    /** All registry entries (last-good cache when offline). */
    fun catalog(refresh: Boolean = true, force: Boolean = false): List<RegistryEntry> =
        registry.entries(refresh, force)

    /** Shared search over the catalog (store UI + CLI). */
    fun search(query: String, refresh: Boolean = true): List<RegistryEntry> =
        registry.search(catalog(refresh), query)

    fun installed(): List<InstalledPlugin> = installer.listInstalled()

    fun installed(id: String): InstalledPlugin? = installer.installed(id)

    /** Store view-model for one plugin: registry entry + local state + compat. */
    data class StoreCard(
        val entry: RegistryEntry,
        val installed: InstalledPlugin?,
        val updateAvailable: Boolean,
        val compat: PluginCompatInfo = PluginCompatInfo(PluginCompatState.COMPATIBLE)
    )

    fun storeCards(refresh: Boolean = true, force: Boolean = false): List<StoreCard> {
        val installedById = installed().associateBy { it.meta.id }
        val installedSdks = sdkStore.installedVersions().toSet()
        return catalog(refresh, force).map { entry ->
            val local = installedById[entry.id]
            val update = local != null && installer.updateAvailable(entry, local)
            // A plugin directory that exists but fails validation (broken or
            // tampered) is invisible to listInstalled — surface it as blocked.
            val blocked = local == null && installer.hasInvalidRecord(entry.id)
            StoreCard(
                entry, local, update,
                PluginSdkCatalog.compat(entry, local, appVersion, installedSdks, blocked, update)
            )
        }
    }

    /** Plugins with a newer registry version (store "Updates" filter). */
    fun updates(refresh: Boolean = true): List<StoreCard> = storeCards(refresh).filter { it.updateAvailable }

    /** True when the last registry refresh hit the network successfully. */
    val lastRefreshOk: Boolean get() = registry.lastRefreshOk

    // ------------------------------------------------------------ lifecycle

    /**
     * Installs a plugin from the registry. SDK requirements are resolved
     * first — an incompatible or app-update-required plugin is refused with
     * a user-stable message before anything is downloaded. The selected SDK
     * release is then made available (downloaded + verified when missing;
     * a failed download installs the plugin but leaves it in sdk_missing).
     */
    fun install(id: String, onProgress: (Int) -> Unit = {}): InstalledPlugin {
        val entry = registry.entry(id)
            ?: throw ManagerException("Unknown plugin")
        gateSdkRequirements(SdkRequirements.from(entry))
        val installed = installer.install(entry, onProgress)
        ensureSdkQuietly(SdkRequirements.from(entry), installed.meta.id, onProgress)
        return installed
    }

    fun update(id: String, onProgress: (Int) -> Unit = {}): InstalledPlugin {
        val entry = registry.entry(id)
            ?: throw ManagerException("Unknown plugin")
        val current = installer.installed(id)
            ?: throw ManagerException("Plugin is not installed")
        gateSdkRequirements(SdkRequirements.from(entry))
        val updated = installer.update(entry, current, onProgress)
        ensureSdkQuietly(SdkRequirements.from(entry), updated.meta.id, onProgress)
        return updated
    }

    fun uninstall(id: String) {
        installer.uninstall(id)
        stateStore.remove(id)
        NoxsLog.i("PluginManager", "plugin uninstalled: $id")
    }

    fun enable(id: String) = installer.enable(id)

    fun disable(id: String) = installer.disable(id)

    // ----------------------------------------------------------------- SDK

    /** Refuses plugins whose SDK requirements this app cannot satisfy. */
    private fun gateSdkRequirements(req: SdkRequirements) {
        val problems = SdkRequirements.validate(
            req.sdkVersion, req.minimumSdkVersion, req.maximumSdkVersion, req.apiFeatures
        )
        if (problems.isNotEmpty()) {
            throw ManagerException("This plugin declares invalid SDK requirements")
        }
        when (val plan = PluginSdkCatalog.resolve(req, appVersion, sdkStore.installedVersions().toSet())) {
            is SdkPlan.Incompatible -> throw ManagerException(plan.reason)
            is SdkPlan.NeedsNewerApp -> throw ManagerException(
                "Update Noxs to ${plan.requiredNoxs} to use this plugin"
            )
            is SdkPlan.Supported -> Unit
        }
    }

    /**
     * Best-effort SDK download after a successful install: a failed
     * download must not undo the install — the store shows sdk_missing and
     * the next activation attempt retries.
     */
    private fun ensureSdkQuietly(req: SdkRequirements, pluginId: String, onProgress: (Int) -> Unit) {
        val plan = PluginSdkCatalog.resolve(req, appVersion, sdkStore.installedVersions().toSet())
        if (plan !is SdkPlan.Supported) return
        try {
            val dir = sdkStore.ensure(plan.selected.version, onProgress = onProgress)
            val manifest = runCatching { SdkJson.parseManifestFile(File(dir, SdkJson.MANIFEST_NAME)) }.getOrNull()
            stateStore.record(
                pluginId, pluginVersion = installed(pluginId)?.meta?.version ?: "",
                sdkVersion = plan.selected.version,
                sdkChecksum = manifest?.let { PluginChecksum.sha256(File(dir, SdkJson.MANIFEST_NAME).readBytes()) }
            )
        } catch (e: NoxsSdkStore.SdkException) {
            NoxsLog.w("PluginManager", "sdk ensure failed: ${e.message}")
        }
    }

    /**
     * The SDK bootstrap source to inject before [plugin]'s code, or a
     * compatibility explanation when it cannot run. Never throws.
     */
    fun sdkRuntimeJs(plugin: InstalledPlugin): SdkRuntime {
        val req = SdkRequirements.from(plugin.meta)
        return try {
            when (val plan = PluginSdkCatalog.resolve(req, appVersion, sdkStore.installedVersions().toSet())) {
                is SdkPlan.Incompatible ->
                    SdkRuntime(null, PluginCompatState.SDK_INCOMPATIBLE, plan.reason)
                is SdkPlan.NeedsNewerApp ->
                    SdkRuntime(
                        null, PluginCompatState.APP_UPDATE_REQUIRED,
                        "Update Noxs to ${plan.requiredNoxs} to use this plugin"
                    )
                is SdkPlan.Supported -> {
                    val dir = try {
                        sdkStore.ensure(plan.selected.version)
                    } catch (e: NoxsSdkStore.SdkException) {
                        null
                    }
                    if (dir == null) {
                        SdkRuntime(
                            null, PluginCompatState.SDK_MISSING,
                            "Noxs Plugin SDK ${plan.selected.version} could not be downloaded. " +
                                "Check your connection and try again."
                        )
                    } else {
                        val js = sdkStore.entryJs(plan.selected.version)
                        if (js == null) {
                            SdkRuntime(
                                null, PluginCompatState.ERROR,
                                "The Noxs Plugin SDK installation is unreadable. Reinstall the plugin."
                            )
                        } else {
                            SdkRuntime(js, PluginCompatState.COMPATIBLE, null)
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            NoxsLog.w("PluginManager", "sdk resolve failed: ${t.javaClass.simpleName}")
            SdkRuntime(null, PluginCompatState.ERROR, "The plugin could not be started.")
        }
    }

    /** Result of [sdkRuntimeJs]: js == null means the plugin must not run. */
    data class SdkRuntime(
        val js: String?,
        val state: PluginCompatState,
        val message: String?
    )

    // ----------------------------------------------------------------- CLI

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
        const val SDK_DIR = ".noxs/sdk"
        const val NOXS_DIR = ".noxs"
    }
}
