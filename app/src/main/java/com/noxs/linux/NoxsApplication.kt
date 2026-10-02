/*
 * Noxs — original implementation.
 * Application class: global paths + main-loop dispatcher wiring.
 */
package com.noxs.linux

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.noxs.linux.runtime.NoxsPaths
import com.noxs.linux.shared.NoxsConstants
import com.noxs.linux.shared.NoxsLog

class NoxsApplication : Application() {

    lateinit var paths: NoxsPaths
        private set

    override fun onCreate() {
        super.onCreate()
        paths = NoxsPaths(this)

        // Route emulator callbacks to the Android main thread.
        val handler = Handler(Looper.getMainLooper())
        MainLoop.handler = handler

        NoxsLog.i("Noxs", "Noxs ${NoxsConstants.VERSION_NAME} started (arch=${android.os.Build.SUPPORTED_ABIS.firstOrNull()})")
    }
}

/** Bridges JVM-pure modules (terminal-emulator) to the Android main loop. */
object MainLoop {
    var handler: Handler? = null

    fun post(r: Runnable) {
        val h = handler
        if (h != null) h.post(r) else r.run()
    }
}
