/*
 * Noxs — original implementation.
 * Foreground service: keeps the Noxs runtime (sessions + control socket) alive
 * while used, owns the dedicated activity notification, and routes
 * notification actions (Open / Stop / Stop Noxs) to the Activity Center.
 */
package com.crossberry.noxs.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.crossberry.noxs.R
import com.crossberry.noxs.shared.NoxsLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class NoxsService : Service() {

    private lateinit var paths: NoxsPaths
    private lateinit var resources: NoxsResources
    private lateinit var launcher: ProotLauncher
    private lateinit var sessions: NoxsSessionManager
    private lateinit var socketServer: NoxsSocketServer
    private lateinit var center: NoxsActivityCenter

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastNotifyAt = 0L
    private var notifyPending = false
    private var stoppingAll = false

    // Keep-awake: servers started inside a session must stay reachable while
    // the user reads them in a browser (device-global localhost access).
    // Without these locks the CPU/Wi-Fi can doze mid-request or Android can
    // treat the runtime as idle, and "localhost:8080" stops loading even
    // though the server process is alive. Gated by settings.keep_awake.
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as com.crossberry.noxs.NoxsApplication
        // Multi-environment runtime (spec §32, §62): sessions, the control
        // socket and proot all operate on the ACTIVE environment's storage.
        paths = app.environments.activePaths()
        center = app.activityCenter
        // Security bootstrap (APT/CA/TLS repair) runs here in the service
        // background: it must never gate shell creation. The terminal always
        // clears straight to an active shell while this runs. Only apt-family
        // environments get the Debian APT bootstrap (spec §62 — no hard-coded
        // Debian backend).
        resources = NoxsResources(paths)
        launcher = ProotLauncher(paths, resources)
        sessions = NoxsSessionManager(paths, launcher, resources, center)
        socketServer = NoxsSocketServer(paths, sessions)
        RuntimeHolder.set(this, sessions, socketServer)
        if (app.environments.activeFamilyIsApt()) {
            NoxsAptSetup.startIfInstalled(paths, launcher, scope)
        }

        center.stopHandler = { record -> performSafeStop(record) }

        createChannel()
        running = this
        startForegroundCompat(1, buildLiveNotification())
        socketServer.start()
        refreshKeepAwake()

        // Migration: environments installed by earlier Noxs versions gain the
        // `nx` package system (CLI + templates) without a reinstall. Cheap,
        // idempotent, version-marker guarded — and it never gates the shell.
        scope.launch(Dispatchers.IO) {
            runCatching { RootfsConfigurator.ensureNxPackageSystem(paths) }
                .onFailure { NoxsLog.w("NoxsService", "nx package system not installed: ${it.message}") }
        }

        // Single source of truth: the notification mirrors the Activity Center.
        scope.launch {
            center.records.collect {
                refreshNotificationThrottled()
                refreshKeepAwake()
            }
        }
        NoxsLog.i("NoxsService", "foreground service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_ACTIVITY -> {
                val id = intent.getStringExtra(EXTRA_ACTIVITY_ID)
                if (id != null) scope.launch(Dispatchers.IO) { center.requestStop(id) }
            }
            ACTION_STOP_ALL -> stopEverythingGracefully()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseKeepAwake()
        socketServer.stop()
        sessions.closeAll()
        // The service is going away: reconcile records so the Activity Center
        // never shows RUNNING work that no longer exists.
        center.active().forEach { if (it.kind != NoxsActivityKind.SESSION) center.markStopped(it.activityId) }
        center.stopHandler = null
        RuntimeHolder.clear()
        running = null
        scope.cancel()
        NoxsLog.i("NoxsService", "foreground service destroyed")
        super.onDestroy()
    }

    // ------------------------------------------------------------------ stop

    /** Safe per-activity stop. Sessions go through the session manager. */
    private fun performSafeStop(record: NoxsActivityRecord) {
        when (record.kind) {
            NoxsActivityKind.SESSION -> {
                val entry = sessions.sessions.value.firstOrNull { it.label == record.sessionId }
                if (entry != null) {
                    sessions.closeSession(entry)
                } else {
                    center.markStopped(record.activityId)
                }
            }
            // The setup engine is cooperative: request a cancel and let the
            // session itself settle the record (COMPLETED/FAILED/CANCELLED).
            NoxsActivityKind.SETUP -> {
                (application as com.crossberry.noxs.NoxsApplication).setupSession.cancel()
            }
            else -> {
                record.pid?.let { center.stopProcessTree(it) }
                center.markStopped(record.activityId)
            }
        }
    }

    /**
     * Stop Noxs: sessions first (their exit flow settles the records), then
     * every other tracked activity with SIGTERM→wait→SIGKILL, then shutdown.
     */
    private fun stopEverythingGracefully() {
        if (stoppingAll) return
        stoppingAll = true
        refreshKeepAwake()
        NoxsLog.i("NoxsService", "graceful stop requested")
        scope.launch(Dispatchers.IO) {
            sessions.closeAll()
            center.active().forEach { record ->
                if (record.kind != NoxsActivityKind.SESSION) {
                    center.requestStop(record.activityId)
                }
            }
            delay(400)
            withMain { stopSelf() }
        }
    }

    private fun withMain(block: () -> Unit) = mainHandler.post(block)

    // ----------------------------------------------------------- notification

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_activity), NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shows what Noxs is running in the background" }
        )
    }

    /** Throttle so bursts of terminal output never spam notification updates. */
    private fun refreshNotificationThrottled() {
        val now = System.currentTimeMillis()
        val since = now - lastNotifyAt
        if (since >= MIN_NOTIFY_INTERVAL_MS) {
            lastNotifyAt = now
            postNotification()
        } else if (!notifyPending) {
            notifyPending = true
            mainHandler.postDelayed({
                notifyPending = false
                lastNotifyAt = System.currentTimeMillis()
                postNotification()
            }, MIN_NOTIFY_INTERVAL_MS - since)
        }
    }

    private fun postNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildLiveNotification())
    }

    internal fun buildLiveNotification(): Notification {
        val active = center.active()
        val top = active.maxByOrNull { it.startedAt }
        val sessionCount = if (this::sessions.isInitialized) sessions.sessions.value.size else 0

        val text = when {
            stoppingAll -> getString(R.string.noxs_shutting_down)
            top == null ->
                if (sessionCount > 0) getString(R.string.notif_sessions, sessionCount)
                else getString(R.string.notif_idle)
            top.status == NoxsActivityStatus.STARTING -> getString(R.string.activity_status_starting) + " " + top.title
            top.status == NoxsActivityStatus.STOPPING -> getString(R.string.activity_status_stopping) + " " + top.title
            active.size == 1 -> getString(R.string.notif_running_one, top.title)
            else -> getString(R.string.notif_running_many, top.title, active.size - 1)
        }

        val focusIntent = Intent(this, com.crossberry.noxs.ui.TerminalActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            top?.let { putExtra(EXTRA_FOCUS_ACTIVITY, it.activityId) }
        }
        val open = PendingIntent.getActivity(
            this, 1, focusIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_noxs)
            .setContentTitle(getString(R.string.notif_short_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        top?.progress?.let { p ->
            builder.setProgress(100, p, false)
        } ?: builder.setProgress(0, 0, top != null)

        if (top != null && !stoppingAll) {
            val stopIntent = Intent(this, NoxsService::class.java).apply {
                action = ACTION_STOP_ACTIVITY
                putExtra(EXTRA_ACTIVITY_ID, top.activityId)
            }
            val stopPi = PendingIntent.getService(
                this, 2, stopIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(0, getString(R.string.notif_action_stop), stopPi)
        }
        return builder.build()
    }

    // ------------------------------------------------------------- keep awake

    private fun keepAwakeEnabled(): Boolean =
        getSharedPreferences("noxs_settings", Context.MODE_PRIVATE).getBoolean("keep_awake", true)

    /** Hold while any session or tracked activity is running, release when idle. */
    internal fun refreshKeepAwake() {
        val shouldHold = keepAwakeEnabled() && !stoppingAll &&
            ((this::sessions.isInitialized && sessions.sessions.value.isNotEmpty()) ||
                (this::center.isInitialized && center.active().isNotEmpty()))
        if (!shouldHold) {
            releaseKeepAwake()
            return
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (wakeLock == null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "noxs:runtime")
                .apply { setReferenceCounted(false) }
        }
        // Long cap: re-armed on every activity/notification change; a lock that
        // lapses is re-acquired on the next refresh, never silently forgotten.
        runCatching { wakeLock?.acquire(6 * 60 * 60 * 1000L) }
            .onFailure { NoxsLog.w("NoxsService", "wake lock unavailable: ${it.message}") }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (wifiLock == null) {
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm.createWifiLock(mode, "noxs:runtime")
                .apply { setReferenceCounted(false) }
        }
        runCatching { wifiLock?.acquire() }
            .onFailure { NoxsLog.w("NoxsService", "wifi lock unavailable: ${it.message}") }
    }

    private fun releaseKeepAwake() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
    }

    private fun startForegroundCompat(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(id, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "noxs_activity"
        private const val NOTIFICATION_ID = 1
        private const val MIN_NOTIFY_INTERVAL_MS = 800L

        const val ACTION_STOP_ALL = "com.crossberry.noxs.STOP_ALL"
        const val ACTION_STOP_ACTIVITY = "com.crossberry.noxs.STOP_ACTIVITY"
        const val EXTRA_ACTIVITY_ID = "activity_id"
        const val EXTRA_FOCUS_ACTIVITY = "focus_activity_id"

        @Volatile
        private var running: NoxsService? = null

        fun start(context: Context) {
            context.startForegroundService(Intent(context, NoxsService::class.java))
        }

        /**
         * Restart so the runtime rebuilds against the newly active environment
         * (spec §32). Sessions are closed by onDestroy — the confirm dialog in
         * the environment manager warns the user before switching.
         */
        fun restart(context: Context) {
            context.stopService(Intent(context, NoxsService::class.java))
            context.startForegroundService(Intent(context, NoxsService::class.java))
        }

        /**
         * Graceful "Stop Noxs": routed through the running service so sessions,
         * tracked processes, the service and the notification shut down in the
         * correct order. Never touches unrelated Android apps.
         */
        fun requestStopAll(context: Context) {
            val intent = Intent(context, NoxsService::class.java).setAction(ACTION_STOP_ALL)
            if (running != null) {
                context.startService(intent)
            } else {
                context.startForegroundService(intent)
            }
        }

        /** Called by the session manager to refresh the notification. */
        fun notifySessionsChanged(count: Int) {
            running?.let {
                it.refreshNotificationThrottled()
                it.refreshKeepAwake()
            }
        }

        /** Focus payload for notification Open taps. */
        fun focusIntent(context: Context, activityId: String?): Intent =
            Intent(context, com.crossberry.noxs.ui.TerminalActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                activityId?.let { putExtra(EXTRA_FOCUS_ACTIVITY, it) }
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

    /** Re-evaluate the keep-awake locks (e.g. after the user flips the setting). */
    fun refreshKeepAwake() {
        service?.refreshKeepAwake()
    }
}
