/*
 * Noxs — original implementation.
 * NoxsPermissionCenter: the central authority for every privileged Noxs API
 * request (Noxs API spec §6-§10).
 *
 * Architecture:
 *
 *     Application -> Permission Center -> Permission Manager
 *                 -> Android / Noxs Runtime -> API authorization
 *
 * Design rules enforced here:
 *  - packages are NEVER trusted just because they are installed
 *  - every privileged API call is validated against this center
 *  - Android permission state is never faked: real state comes from an
 *    injected probe (ContextCompat/PackageManager on device, lambdas in tests)
 *  - access is never claimed to be granted until the OS confirms it
 *  - only permissions actually needed by enabled features are requested
 *  - state persists as a JSON file inside the app sandbox
 *
 * The center is Android-free (probes injected) so the full policy is
 * unit-testable on the JVM.
 */
package com.crossberry.noxs.runtime

import com.crossberry.noxs.shared.MiniJson
import java.io.File

class NoxsPermissionCenter(
    stateDir: File,
    /** Real Android permission probe: true when the OS reports granted. */
    private val androidProbe: (androidPermission: String) -> Boolean = { false },
    /** Feature probes for feature-dependent permissions (e.g. keep-awake toggle). */
    private val featureProbe: (permissionId: String) -> Boolean = { false }
) {

    enum class State { ALLOWED, NOT_GRANTED, DENIED, RESTRICTED, NOT_SUPPORTED }

    data class Entry(
        val permission: NoxsPermissionCatalog.Permission,
        val state: State,
        /** True when the state comes straight from Android (not a user choice). */
        val fromAndroid: Boolean
    )

    private val stateFile = File(stateDir, FILE_NAME)
    private val decisions = HashMap<String, String>()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    private val lock = Any()

    init {
        load()
    }

    // ------------------------------------------------------------- queries

    /** Effective state for one Noxs permission (spec §8 states). */
    fun stateOf(permissionId: String): State {
        val permission = NoxsPermissionCatalog.byId(permissionId)
            ?: return State.NOT_SUPPORTED
        val androidPermission = permission.androidPermission
        if (androidPermission != null) {
            val osGranted = runCatching { androidProbe(androidPermission) }.getOrDefault(false)
            if (!osGranted) {
                // A user may still have permanently denied the runtime prompt.
                return if (decisions[permissionId] == State.DENIED.name) State.DENIED else State.NOT_GRANTED
            }
        }
        if (permission.level == NoxsPermissionCatalog.Level.FEATURE_DEPENDENT) {
            return if (runCatching { featureProbe(permissionId) }.getOrDefault(false)) {
                State.ALLOWED
            } else {
                State.RESTRICTED
            }
        }
        return when (decisions[permissionId]) {
            State.ALLOWED.name -> State.ALLOWED
            State.DENIED.name -> State.DENIED
            State.RESTRICTED.name -> State.RESTRICTED
            State.NOT_GRANTED.name -> State.NOT_GRANTED
            else -> State.NOT_GRANTED
        }
    }

    /**
     * Authorization gate used by the Noxs API bridge. Access requires an
     * explicit ALLOWED decision — absence of a decision is not access.
     */
    fun isAllowed(permissionId: String): Boolean = stateOf(permissionId) == State.ALLOWED

    fun entries(): List<Entry> = NoxsPermissionCatalog.ALL.map { permission ->
        Entry(permission, stateOf(permission.id), permission.androidPermission != null && stateOf(permission.id) != State.DENIED)
    }

    /** Android runtime permissions that still need a grant (spec §10 honesty). */
    fun pendingAndroidPermissions(): List<NoxsPermissionCatalog.Permission> =
        NoxsPermissionCatalog.ALL.filter { permission ->
            permission.androidPermission != null && !isAllowed(permission.id) &&
                stateOf(permission.id) != State.DENIED
        }

    // ------------------------------------------------------------ decisions

    /** User grants a Noxs permission. Returns false for unknown permissions. */
    fun grant(permissionId: String): Boolean {
        val permission = NoxsPermissionCatalog.byId(permissionId) ?: return false
        val androidPermission = permission.androidPermission
        if (androidPermission != null && !runCatching { androidProbe(androidPermission) }.getOrDefault(false)) {
            // Never claim access the OS has not confirmed (spec §10).
            setDecision(permissionId, State.NOT_GRANTED)
            notifyListeners()
            return false
        }
        setDecision(permissionId, State.ALLOWED)
        notifyListeners()
        return true
    }

    /** User or policy denies a Noxs permission. */
    fun deny(permissionId: String): Boolean {
        if (NoxsPermissionCatalog.byId(permissionId) == null) return false
        setDecision(permissionId, State.DENIED)
        notifyListeners()
        return true
    }

    /** Revoke a user decision (back to NOT_GRANTED). */
    fun revoke(permissionId: String): Boolean {
        if (NoxsPermissionCatalog.byId(permissionId) == null) return false
        synchronized(lock) { decisions.remove(permissionId) }
        persist()
        notifyListeners()
        return true
    }

    /** Called after an Android runtime permission request returns. */
    fun onAndroidPermissionResult(permissionId: String, granted: Boolean): Boolean {
        if (NoxsPermissionCatalog.byId(permissionId) == null) return false
        if (granted) return grant(permissionId)
        setDecision(permissionId, State.NOT_GRANTED)
        notifyListeners()
        return true
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    // ------------------------------------------------------------- storage

    private fun setDecision(permissionId: String, state: State) {
        synchronized(lock) { decisions[permissionId] = state.name }
        persist()
    }

    private fun persist() {
        runCatching {
            stateFile.parentFile?.mkdirs()
            val body: String
            synchronized(lock) {
                body = decisions.entries.joinToString(",") { (id, value) ->
                    "\"$id\":\"$value\""
                }
            }
            val json = "{$body}"
            val tmp = File(stateFile.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(json, Charsets.UTF_8)
            if (!tmp.renameTo(stateFile)) {
                stateFile.writeText(json, Charsets.UTF_8)
                tmp.delete()
            }
        }
    }

    private fun load() {
        runCatching {
            if (!stateFile.isFile) return
            val parsed = MiniJson.parse(stateFile.readText(Charsets.UTF_8)) as? Map<*, *> ?: return
            synchronized(lock) {
                decisions.clear()
                parsed.forEach { (key, value) ->
                    val id = key.toString()
                    val state = value as? String ?: return@forEach
                    // Only known permission ids survive a load (tamper guard).
                    if (NoxsPermissionCatalog.byId(id) != null && state.isNotEmpty()) {
                        decisions[id] = state
                    }
                }
            }
        }
    }

    private fun notifyListeners() {
        listeners.forEach { listener -> runCatching { listener() } }
    }

    companion object {
        private const val FILE_NAME = "noxs-permissions.json"

        /** Structured error used by the API bridge when a call is denied. */
        const val ERROR_PERMISSION_DENIED = "PERMISSION_DENIED"

        /**
         * Default production probe: PackageManager check — real Android state.
         * Install-time permissions resolve as granted; runtime permissions
         * reflect what the user actually granted.
         */
        fun defaultProbe(context: android.content.Context): (String) -> Boolean = { permission ->
            context.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }
}
