/*
 * Noxs — original implementation.
 * NoxsWebWindowManager: registry for Noxs browser windows opened via `nx ow`
 * (or a URL share into Noxs). Tracks live window state so other components
 * (Activity Center, nx.window API) can observe windows without touching the
 * activities directly. All content rules live in NoxsUrlGuard — this class
 * never trusts a URL itself.
 */
package com.crossberry.noxs.runtime

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

object NoxsWebWindowManager {

    enum class State { OPEN, MINIMIZED, FULLSCREEN }

    data class WebWindowState(
        val windowId: String,
        val url: String,
        val state: State
    )

    private val windows = ConcurrentHashMap<String, WebWindowState>()
    private val listeners = CopyOnWriteArrayList<(WebWindowState) -> Unit>()

    fun register(windowId: String, url: String) {
        update(WebWindowState(windowId, url, State.OPEN))
    }

    fun updateState(windowId: String, state: State) {
        windows[windowId]?.let { update(it.copy(state = state)) }
    }

    fun updateUrl(windowId: String, url: String) {
        val safe = (NoxsUrlGuard.check(url) as? NoxsUrlGuard.Decision.Allowed)?.url ?: return
        windows[windowId]?.let { update(it.copy(url = safe)) }
    }

    fun unregister(windowId: String) {
        windows.remove(windowId)
    }

    fun snapshot(): List<WebWindowState> = windows.values.toList()

    fun get(windowId: String): WebWindowState? = windows[windowId]

    fun addListener(listener: (WebWindowState) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (WebWindowState) -> Unit) {
        listeners.remove(listener)
    }

    private fun update(state: WebWindowState) {
        windows[state.windowId] = state
        listeners.forEach { listener ->
            runCatching { listener(state) }
        }
    }
}
