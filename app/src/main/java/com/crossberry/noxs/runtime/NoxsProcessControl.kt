/*
 * Noxs — original implementation.
 * Process ownership/signal layer. Noxs only ever signals PIDs it spawned or
 * that descend from its own sessions — never broad "kill by name" sweeps.
 */
package com.crossberry.noxs.runtime

import android.os.Process
import com.crossberry.noxs.shared.NoxsLog
import java.io.File

/** Production signaller backed by android.os.Process (same-UID PIDs only). */
class AndroidSignaller : NoxsSignaller {

    override fun terminate(pid: Int) = send(pid, SIGTERM)

    override fun kill(pid: Int) = send(pid, SIGKILL)

    override fun isAlive(pid: Int): Boolean = try {
        // /proc/<pid> exists only for live same-UID processes.
        File("/proc/$pid").exists()
    } catch (_: Exception) {
        false
    }

    private fun send(pid: Int, signal: Int) {
        if (pid <= 0) return
        try {
            Process.sendSignal(pid, signal)
            NoxsLog.i("ProcessCtl", "signal $signal → pid $pid")
        } catch (e: Exception) {
            NoxsLog.w("ProcessCtl", "signal $signal → pid $pid failed: ${e.message}")
        }
    }

    companion object {
        const val SIGTERM = 15
        const val SIGKILL = 9
    }
}
