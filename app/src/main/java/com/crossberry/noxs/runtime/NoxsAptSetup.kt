/*
 * Noxs — original implementation.
 * Process-wide APT security bootstrap runner.
 *
 * The signed APT/CA/TLS bootstrap repairs the Debian package layer in the
 * background. It must NEVER gate shell creation: the terminal always clears
 * straight to an active shell while this runs (and retries on later launches).
 * State intentionally lives outside any Activity/Service so it survives
 * Activity recreation and service restarts within the same process.
 */
package com.crossberry.noxs.runtime

import com.crossberry.noxs.shared.NoxsLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

object NoxsAptSetup {

    enum class State { IDLE, RUNNING, READY, FAILED }

    private val _state = MutableStateFlow(State.IDLE)

    /** Observable bootstrap state (single source of truth for the UI). */
    val state: StateFlow<State> = _state

    /** Last failure detail, for diagnostics. Never contains secrets. */
    @Volatile
    var detail: String = ""
        private set

    private val running = AtomicBoolean(false)

    /**
     * Starts the bootstrap at most once at a time, only for installed
     * environments. Safe to call on every service start:
     * - IDLE + readiness marker present (verified by the setup wizard) -> READY
     * - READY -> no-op (nothing to repair)
     * - otherwise -> run/repair in [scope] on a background dispatcher
     */
    @Synchronized
    fun startIfInstalled(paths: NoxsPaths, launcher: ProotLauncher, scope: CoroutineScope) {
        if (!paths.isInstalled()) return
        when (_state.value) {
            State.RUNNING -> return
            State.READY -> if (paths.aptReadyMarker.isFile) return
            State.IDLE -> if (paths.aptReadyMarker.isFile) {
                _state.value = State.READY
                return
            }
            State.FAILED -> Unit // retry on this launch
        }
        if (!running.compareAndSet(false, true)) return

        _state.value = State.RUNNING
        detail = ""
        scope.launch(Dispatchers.IO) {
            NoxsLog.i("AptSetup", "background APT bootstrap started")
            val result = runCatching { NoxsAptBootstrapper(paths, launcher).initialize() }
                .getOrElse { NoxsAptBootstrapper.Result(false, it.message ?: it.javaClass.simpleName) }
            detail = result.detail
            _state.value = if (result.success) State.READY else State.FAILED
            running.set(false)
            NoxsLog.i("AptSetup", "background APT bootstrap finished success=${result.success}")
        }
    }

    /** Test/diagnostic hook: forget the cached state for this process. */
    fun resetForTests() {
        running.set(false)
        detail = ""
        _state.value = State.IDLE
    }
}
