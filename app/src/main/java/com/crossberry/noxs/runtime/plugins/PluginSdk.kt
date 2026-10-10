/*
 * Noxs — original implementation.
 * PluginSdk: the Noxs Plugin SDK compatibility layer.
 *
 * The Noxs Plugin SDK is a versioned, plugin-facing JavaScript API contract.
 * The Noxs app implements the host functionality behind that contract; each
 * SDK release pins an API generation (apiVersion) plus the feature set that
 * generation exposes. Plugins declare which SDK they were developed against
 * and which API features they require; THIS file is the single explicit
 * compatibility matrix (spec: "Maintain a capability table") that decides:
 *
 *   - which SDK releases the running Noxs app supports
 *   - which SDK version a given plugin should load (newest compatible,
 *     preferring an already-installed one — no needless downloads)
 *   - the compatibility state shown in the Plugin Store:
 *     compatible / sdk_missing / sdk_incompatible / app_update_required /
 *     plugin_update_available / blocked / error
 *
 * Compatibility policy (short form):
 *   1. A plugin runs on an SDK release of the SAME apiVersion generation as
 *      the SDK it was developed against.
 *   2. The release must satisfy the plugin's [minimumSdkVersion, maximumSdkVersion)
 *      range — minimumSdkVersion defaults to the declared sdkVersion (a plugin
 *      is never silently assumed to work on an older SDK).
 *   3. Every required apiFeature must be provided by the selected release.
 *   4. The release must be supported by the running app (see RELEASES table).
 *   5. When several releases qualify, the newest one that is already
 *      installed locally wins; otherwise the newest supported release is
 *      selected and downloaded on demand. Older, still-supported SDK
 *      versions keep running — nothing is force-migrated.
 *
 * Pure JVM: no Android imports, parsing via the shared MiniJson parser.
 */
package com.crossberry.noxs.runtime.plugins

/** Feature identifiers of the Noxs Plugin SDK API. */
object SdkFeatures {
    const val LOGGING = "logging"
    const val UI = "ui"
    const val TERMINAL = "terminal"
    const val STORAGE = "storage"

    /** Every feature identifier the SDK contract knows about. */
    val ALL = listOf(LOGGING, UI, TERMINAL, STORAGE)
}

/**
 * The SDK requirements a plugin declares in plugin.json / registry.json.
 * Legacy manifests without SDK fields resolve to the initial stable SDK
 * ("0.0.1") — exactly the API surface those plugins were built on, so the
 * default never introduces an API the plugin has not seen.
 */
data class SdkRequirements(
    val sdkVersion: String,
    val minimumSdkVersion: String?,
    val maximumSdkVersion: String?,
    val apiFeatures: List<String>
) {
    companion object {
        const val DEFAULT_SDK_VERSION = "0.0.1"

        fun from(meta: PluginMeta): SdkRequirements = SdkRequirements(
            meta.sdkVersion.ifBlank { DEFAULT_SDK_VERSION },
            meta.minimumSdkVersion,
            meta.maximumSdkVersion,
            meta.apiFeatures
        )

        fun from(entry: RegistryEntry): SdkRequirements = SdkRequirements(
            entry.sdkVersion?.takeIf { it.isNotBlank() } ?: DEFAULT_SDK_VERSION,
            entry.minimumSdkVersion,
            entry.maximumSdkVersion,
            entry.apiFeatures
        )

        /**
         * Manifest-level validation shared by the app gate and the store:
         * rejects unparsable semvers and contradictory or unknown
         * requirements. Empty list = acceptable.
         */
        fun validate(
            sdkVersion: String,
            minimumSdkVersion: String?,
            maximumSdkVersion: String?,
            apiFeatures: List<String>
        ): List<String> {
            val problems = mutableListOf<String>()
            val sdk = PluginSemver.parse(sdkVersion)
            if (sdk == null) {
                problems += "sdkVersion: must be MAJOR.MINOR.PATCH"
                return problems
            }
            val min = minimumSdkVersion?.let { PluginSemver.parse(it) }
            if (minimumSdkVersion != null && min == null) {
                problems += "minimumSdkVersion: must be MAJOR.MINOR.PATCH"
            }
            val max = maximumSdkVersion?.let { PluginSemver.parse(it) }
            if (maximumSdkVersion != null && max == null) {
                problems += "maximumSdkVersion: must be MAJOR.MINOR.PATCH"
            }
            if (min != null && min > sdk) {
                problems += "minimumSdkVersion: must not exceed sdkVersion"
            }
            if (min != null && max != null && max <= min) {
                problems += "maximumSdkVersion: must be above minimumSdkVersion"
            }
            apiFeatures.forEach { feature ->
                if (feature !in SdkFeatures.ALL) {
                    problems += "apiFeatures: unknown SDK feature \"$feature\""
                }
            }
            return problems
        }
    }
}

/** Parsed sdk.json — the manifest of one published SDK release. */
data class SdkManifest(
    val name: String,
    val version: String,
    val apiVersion: String,
    val entry: String,
    val types: String?,
    val status: String,
    val features: List<String>
)

/** One entry of the published sdk/registry.json (the download source). */
data class SdkRemoteEntry(
    val version: String,
    val apiVersion: String,
    val status: String,
    val features: List<String>,
    val minimumNoxsVersion: String?,
    val artifact: String?,
    val checksum: String?
)

/**
 * The app-side capability row for one SDK release. This table is the ONLY
 * place SDK versions are hardcoded in the app; new SDK releases ship with a
 * Noxs app update that extends this table (spec §8).
 */
data class SdkRelease(
    val version: String,
    val apiVersion: String,
    val features: Set<String>,
    val minimumNoxsVersion: String,
    val status: String
) {
    val semver: PluginSemver? get() = PluginSemver.parse(version)
}

/** The resolution outcome for one plugin against the running app. */
sealed class SdkPlan {
    /** The host can run this plugin on [selected]. */
    data class Supported(val selected: SdkRelease, val installedLocally: Boolean) : SdkPlan()

    /** A newer Noxs app is required — [requiredNoxs] is the version to show. */
    data class NeedsNewerApp(val requiredNoxs: String, val reason: String) : SdkPlan()

    /** The requirements can never be satisfied — shown verbatim to the user. */
    data class Incompatible(val reason: String) : SdkPlan()
}

/** Plugin Store compatibility states (spec §7). */
enum class PluginCompatState {
    COMPATIBLE,
    SDK_MISSING,
    SDK_INCOMPATIBLE,
    APP_UPDATE_REQUIRED,
    PLUGIN_UPDATE_AVAILABLE,
    BLOCKED,
    ERROR;

    /** Stable lowercase code used in CLI snapshots and logs. */
    val code: String get() = name.lowercase()
}

/** One plugin's compatibility as displayed by the store / CLI. */
data class PluginCompatInfo(
    val state: PluginCompatState,
    val sdkVersion: String? = null,
    val requiredNoxs: String? = null,
    val reason: String? = null
)

object PluginSdkCatalog {

    /**
     * The capability table: every SDK release this codebase knows, with the
     * features its apiVersion generation provides and the minimum Noxs app
     * version that implements them host-side.
     *
     *  - 0.0.1 — initial stable contract: logging, ui, terminal
     *    (apiVersion "1"), shipped with Noxs 0.11.0.
     *  - 0.0.2 — backward-compatible addition: plugin-scoped storage
     *    (host storage API landed in Noxs 0.13.0).
     */
    val RELEASES: List<SdkRelease> = listOf(
        SdkRelease(
            version = "0.0.1",
            apiVersion = "1",
            features = setOf(SdkFeatures.LOGGING, SdkFeatures.UI, SdkFeatures.TERMINAL),
            minimumNoxsVersion = "0.11.0",
            status = "stable"
        ),
        SdkRelease(
            version = "0.0.2",
            apiVersion = "1",
            features = setOf(
                SdkFeatures.LOGGING, SdkFeatures.UI, SdkFeatures.TERMINAL, SdkFeatures.STORAGE
            ),
            minimumNoxsVersion = "0.13.0",
            status = "stable"
        )
    )

    /** The capability row of a known SDK version, or null when unknown. */
    fun known(version: String): SdkRelease? =
        RELEASES.firstOrNull { it.version == version }

    /** SDK releases the running app can actually host. */
    fun supported(appVersion: String): List<SdkRelease> =
        RELEASES.filter { PluginValidation.compatible(it.minimumNoxsVersion, appVersion) }

    /** True when the running app implements [feature] through some SDK. */
    fun featureAvailable(feature: String, appVersion: String): Boolean =
        supported(appVersion).any { feature in it.features }

    /**
     * Resolves the SDK plan for [req] against the running app, preferring
     * an already-installed release from [installedVersions].
     */
    fun resolve(
        req: SdkRequirements,
        appVersion: String,
        installedVersions: Set<String> = emptySet()
    ): SdkPlan {
        val sdk = PluginSemver.parse(req.sdkVersion)
            ?: return SdkPlan.Incompatible("This plugin declares an invalid Noxs Plugin SDK version")
        val min = req.minimumSdkVersion?.let { PluginSemver.parse(it) } ?: sdk
        if (req.minimumSdkVersion != null && min == null) {
            return SdkPlan.Incompatible("This plugin declares an invalid minimumSdkVersion")
        }
        val max = req.maximumSdkVersion?.let { PluginSemver.parse(it) }
        if (req.maximumSdkVersion != null && max == null) {
            return SdkPlan.Incompatible("This plugin declares an invalid maximumSdkVersion")
        }
        if (max != null && max <= min) {
            return SdkPlan.Incompatible(
                "This plugin declares an empty SDK range (maximumSdkVersion must be above minimumSdkVersion)"
            )
        }
        val anchor = known(req.sdkVersion)
        if (anchor == null) {
            // A future SDK this app knows nothing about — only a Noxs update helps.
            return SdkPlan.NeedsNewerApp(
                requiredNoxs = "the latest Noxs",
                reason = "requires Noxs Plugin SDK ${req.sdkVersion}, which this Noxs release does not include"
            )
        }
        val supported = supported(appVersion)
        val inRange = supported.filter {
            it.apiVersion == anchor.apiVersion &&
                (it.semver ?: PluginSemver.ZERO) >= min &&
                (max == null || (it.semver ?: PluginSemver.ZERO) < max)
        }
        val feasible = inRange.filter { release ->
            req.apiFeatures.all { it in release.features }
        }
        val chosen = feasible.filter { it.version in installedVersions }
            .maxByOrNull { it.semver ?: PluginSemver.ZERO }
            ?: feasible.maxByOrNull { it.semver ?: PluginSemver.ZERO }
        if (chosen != null) {
            return SdkPlan.Supported(chosen, chosen.version in installedVersions)
        }

        // No runnable release — diagnose precisely for the store message.
        val unknownFeatures = req.apiFeatures.filter { feature ->
            RELEASES.none { feature in it.features }
        }
        if (unknownFeatures.isNotEmpty()) {
            return SdkPlan.Incompatible(
                "requires API feature(s) ${unknownFeatures.joinToString(", ")} that the Noxs host does not provide"
            )
        }
        if (inRange.isNotEmpty()) {
            // Some supported release matches the range but lacks features;
            // check whether a newer SDK (needing a newer app) provides them.
            val providers = RELEASES.filter { release ->
                release.apiVersion == anchor.apiVersion &&
                    req.apiFeatures.all { it in release.features } &&
                    !PluginValidation.compatible(release.minimumNoxsVersion, appVersion)
            }
            if (providers.isNotEmpty()) {
                return SdkPlan.NeedsNewerApp(
                    requiredNoxs = providers.minOf { it.minimumNoxsVersion },
                    reason = "requires ${req.apiFeatures.joinToString(", ")} from Noxs Plugin SDK " +
                        providers.maxByOrNull { it.semver ?: PluginSemver.ZERO }?.version.orEmpty()
                )
            }
            val missing = req.apiFeatures.filter { feature ->
                inRange.none { feature in it.features }
            }
            return SdkPlan.Incompatible(
                "requires API feature(s) ${missing.joinToString(", ")} that this Noxs release cannot provide" +
                    " for Noxs Plugin SDK ${anchor.apiVersion}"
            )
        }
        // In-range releases exist in the table but none is supported here.
        val satisfying = RELEASES.filter {
            it.apiVersion == anchor.apiVersion &&
                (it.semver ?: PluginSemver.ZERO) >= min &&
                (max == null || (it.semver ?: PluginSemver.ZERO) < max)
        }
        if (satisfying.isEmpty()) {
            return SdkPlan.Incompatible(
                "requires Noxs Plugin SDK ${req.sdkVersion} which no Noxs release provides"
            )
        }
        return SdkPlan.NeedsNewerApp(
            requiredNoxs = satisfying.minOf { it.minimumNoxsVersion },
            reason = "requires a newer Noxs to run Noxs Plugin SDK ${req.sdkVersion}"
        )
    }

    /**
     * Full store-card compatibility: combines the SDK plan, the app gate,
     * local SDK availability, blocked install records and update detection.
     */
    fun compat(
        entry: RegistryEntry,
        installed: InstalledPlugin?,
        appVersion: String,
        installedSdkVersions: Set<String>,
        blockedRecord: Boolean,
        updateAvailable: Boolean
    ): PluginCompatInfo {
        if (blockedRecord) {
            return PluginCompatInfo(
                PluginCompatState.BLOCKED,
                SdkRequirements.from(entry).sdkVersion,
                null,
                "The last installation failed its Noxs integrity checks — uninstall the plugin, then install it again"
            )
        }
        val req = SdkRequirements.from(entry)
        val problems = SdkRequirements.validate(
            req.sdkVersion, req.minimumSdkVersion, req.maximumSdkVersion, req.apiFeatures
        )
        if (problems.isNotEmpty()) {
            return PluginCompatInfo(
                PluginCompatState.BLOCKED,
                req.sdkVersion,
                null,
                "This plugin declares invalid SDK requirements — ${problems.first()}"
            )
        }
        if (!PluginValidation.compatible(entry.minimumNoxsVersion, appVersion)) {
            return PluginCompatInfo(
                PluginCompatState.APP_UPDATE_REQUIRED,
                req.sdkVersion,
                entry.minimumNoxsVersion,
                "Update Noxs to ${entry.minimumNoxsVersion} or newer to use this plugin"
            )
        }
        return when (val plan = resolve(req, appVersion, installedSdkVersions)) {
            is SdkPlan.Incompatible -> PluginCompatInfo(
                PluginCompatState.SDK_INCOMPATIBLE, req.sdkVersion, null, plan.reason
            )
            is SdkPlan.NeedsNewerApp -> PluginCompatInfo(
                PluginCompatState.APP_UPDATE_REQUIRED,
                req.sdkVersion,
                newestSemver(plan.requiredNoxs, entry.minimumNoxsVersion),
                "Update Noxs to use this plugin. ${plan.reason}"
            )
            is SdkPlan.Supported -> when {
                installed != null && updateAvailable -> PluginCompatInfo(
                    PluginCompatState.PLUGIN_UPDATE_AVAILABLE,
                    plan.selected.version,
                    null,
                    "Version ${entry.version} is available — the installed version keeps working"
                )
                installed != null && !plan.installedLocally -> PluginCompatInfo(
                    PluginCompatState.SDK_MISSING,
                    plan.selected.version,
                    null,
                    "Noxs Plugin SDK ${plan.selected.version} will be downloaded from the Noxs Plugin Store"
                )
                else -> PluginCompatInfo(
                    PluginCompatState.COMPATIBLE, plan.selected.version, null, null
                )
            }
        }
    }

    /** Highest of two version strings, ignoring unparsable input. */
    fun newestSemver(a: String?, b: String?): String? {
        val av = a?.let { PluginSemver.parse(it) }
        val bv = b?.let { PluginSemver.parse(it) }
        return when {
            av == null && bv == null -> null
            av == null -> b
            bv == null -> a
            av >= bv -> a
            else -> b
        }
    }
}
