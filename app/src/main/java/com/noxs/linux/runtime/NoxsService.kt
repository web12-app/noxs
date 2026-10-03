/*
 * Noxs — original implementation.
 * Foreground service: keeps the Noxs runtime (sessions + control socket) alive
 * while used. Android may still stop it under memory pressure — sessions are
 * designed to die cleanly and the app recovers (no background bypass tricks).
 */
package com.noxs.linux.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.noxs.linux.R
import com.noxs.linux.shared.NoxsLog
import com.noxs.linux.shared.NoxsConstants

class NoxsService : Service() {

    private lateinit var paths: NoxsPaths
    private lateinit var resources: NoxsResources
    private lateinit var launcher: ProotLauncher
    private lateinit var sessions: NoxsSessionManager
    private lateinit var socketServer: NoxsSocketServer

    override fun onCreate() {
        super.onCreate()
        val app = application as com.noxs.linux.NoxsApplication
        paths = app.paths
        resources = NoxsResources(paths)
        launcher = ProotLauncher(paths, resources)
        sessions = NoxsSessionManager(paths, launcher, resources)
        socketServer = NoxsSocketServer(paths, sessions)
        RuntimeHolder.set(this, sessions, socketServer)

        createChannel()
        running = this
        startForegroundCompat(1, buildNotification(0))
        socketServer.start()
        NoxsLog.i("NoxsService", "foreground service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_ALL) {
            sessions.closeAll()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        socketServer.stop()
        sessions.closeAll()
        RuntimeHolder.clear()
        running = null
        NoxsLog.i("NoxsService", "foreground service destroyed")
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Noxs Linux environment", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Keeps the Noxs Debian userspace running" }
        )
    }

    private fun buildNotification(sessionCount: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, com.noxs.linux.ui.TerminalActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_noxs)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(
                if (sessionCount > 0) getString(R.string.notif_sessions, sessionCount)
                else getString(R.string.notif_idle)
            )
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundCompat(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(id, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "noxs_runtime"
        const val ACTION_STOP_ALL = "com.noxs.linux.STOP_ALL"

        @Volatile
        private var running: NoxsService? = null

        fun start(context: Context) {
            context.startForegroundService(Intent(context, NoxsService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, NoxsService::class.java).setAction(ACTION_STOP_ALL)
            )
            context.stopService(Intent(context, NoxsService::class.java))
        }

        /** Called by the session manager to refresh the notification. */
        fun notifySessionsChanged(count: Int) {
            val svc = running ?: return
            val nm = svc.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(1, svc.buildNotification(count))
        }
    }
}

/** Process-wide access to the active runtime (used by manager screens). */
object RuntimeHolder {
    var sessions: NoxsSessionManager? = null
        private set
    var socketServer: NoxsSocketServer? = null
        private set
    private var service: NoxsService? = null

    fun set(service: NoxsService, sessions: NoxsSessionManager, socketServer: NoxsSocketServer) {
        this.service = service
        this.sessions = sessions
        this.socketServer = socketServer
    }

    fun clear() {
        service = null
        sessions = null
        socketServer = null
    }
}
