/*
 * Noxs — original implementation.
 * Application class: global paths, main-loop dispatcher wiring, and the
 * app-scoped NoxsActivityCenter (single source of truth for background
 * activity — intentionally OUTSIDE any Activity/Service so it survives
 * Activity recreation, configuration changes and service restarts).
 */
package com.crossberry.noxs

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.crossberry.noxs.runtime.AndroidSignaller
import com.crossberry.noxs.runtime.NoxsActivityCenter
import com.crossberry.noxs.runtime.NoxsPaths
import com.crossberry.noxs.shared.NoxsLog
import java.io.File

class NoxsApplication : Application() {

    lateinit var paths: NoxsPaths
        private set

    lateinit var activityCenter: NoxsActivityCenter
        private set

    override fun onCreate() {
        super.onCreate()
        paths = NoxsPaths(this)

        // Route emulator callbacks to the Android main thread.
        val handler = Handler(Looper.getMainLooper())
        MainLoop.handler = handler

        // Activity metadata persists in app-private storage; it holds no
        // terminal output history and no secrets.
        activityCenter = NoxsActivityCenter(AndroidSignaller())
        activityCenter.attachStorage(File(filesDir, "activity/history.tsv"))

        NoxsLog.i("Noxs", "Noxs ${BuildConfig.VERSION_NAME} started (arch=${android.os.Build.SUPPORTED_ABIS.firstOrNull()})")
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
