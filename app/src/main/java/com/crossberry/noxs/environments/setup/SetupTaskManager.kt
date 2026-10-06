/*
 * Noxs — original implementation.
 * SetupTaskManager (spec §9-§10, §14-§18, §41, §43-§46, §55-§56).
 *
 * One source of truth for every setup task: the install UI, the environment
 * manager, the task list and the Android notification all observe the same
 * StateFlow. Tasks run on Dispatchers.IO (never the UI thread), persist every
 * major state transition, survive process death (recovery scan on init) and
 * support cancellation at safe points only.
 */
package com.crossberry.noxs.environments.setup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.crossberry.noxs.R
import com.crossberry.noxs.environments.InstallContext
import com.crossberry.noxs.environments.SafeExtractor
import com.crossberry.noxs.environments.model.Environment
import com.crossberry.noxs.environments.model.EnvironmentStatus
import com.crossberry.noxs.environments.model.EnvironmentVariant
import com.crossberry.noxs.environments.model.SetupState
import com.crossberry.noxs.environments.model.SetupStateMachine
import com.crossberry.noxs.environments.model.SetupTask
import com.crossberry.noxs.environments.providers.EnvironmentProvider
import com.crossberry.noxs.runtime.NoxsPaths
import com.crossberry.noxs.runtime.ProotLauncher
import com.crossberry.noxs.runtime.NoxsResources
import com.crossberry.noxs.shared.NoxsLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class SetupTaskManager(
    private val context: Context,
    private val environmentsRoot: File,
    private val pathsFactory: (File) -> NoxsPaths,
    /** Called after every registry-relevant change so EnvironmentManager can persist. */
    private val onEnvironmentChanged: (Environment) -> Unit,
    private val onEnvironmentRemoved: (String) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    /** Cooperative cancellation flags — separate from task state so progress
     *  callbacks can never overwrite a persisted CANCELLED (spec §46). */
    private val cancelRequested = ConcurrentHashMap.newKeySet<String>()

    private val _tasks = MutableStateFlow<List<SetupTask>>(emptyList())
    val tasks: StateFlow<List<SetupTask>> = _tasks

    private val tasksDir = File(environmentsRoot, "tasks").apply { mkdirs() }
    private val logsDir = File(environmentsRoot, "logs").apply { mkdirs() }
    private val notifier = SetupNotifier(context)

    // ------------------------------------------------------------- persistence

    private fun taskFile(id: String) = File(tasksDir, "$id.json")

    private fun persist(task: SetupTask) {
        runCatching { taskFile(task.id).writeText(task.toJson()) }
        _tasks.value = (_tasks.value.filterNot { it.id == task.id } + task)
            .sortedByDescending { it.startedAt }
    }

    private fun loadAll(): List<SetupTask> = tasksDir.listFiles()
        ?.mapNotNull { SetupTask.fromJson(runCatching { it.readText() }.getOrDefault("")) }
        .orEmpty()

    // ------------------------------------------------------------------ api

    fun startInstall(
        provider: EnvironmentProvider,
        variant: EnvironmentVariant,
        environment: Environment,
        passwordProvider: () -> CharArray? = { null }
    ): SetupTask {
        require(!jobs.containsKey(environment.id)) { "an install for ${environment.id} is already running" }
        val taskId = "setup-${environment.id}-${System.currentTimeMillis()}"
        val logFile = File(logsDir, "setup-${environment.id}-${taskId.takeLast(8)}.log")
        var task = SetupTask(
            id = taskId,
            environmentId = environment.id,
            title = "${provider.displayName} setup",
            state = SetupState.SELECTED,
            progress = 0,
            currentOperation = "Preparing",
            startedAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            logPath = logFile.absolutePath,
            variant = variant.id
        )
        persist(task)

        val paths = pathsFactory(File(environment.storagePath))
        val launcher = ProotLauncher(paths, NoxsResources(paths))
        val logLock = Any()
        fun appendLog(line: String) {
            synchronized(logLock) {
                runCatching {
                    val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                    logFile.appendText("[$ts] ${sanitize(line)}\n")
                }
            }
        }
        appendLog("task=$taskId provider=${provider.id} variant=${variant.id}")

        val ctx = InstallContext(
            context = context,
            paths = paths,
            launcher = launcher,
            safeExtractor = SafeExtractor(environmentsRoot),
            isCancelled = { cancelRequested.contains(environment.id) || task.state == SetupState.CANCELLED },
            onStage = { state, op ->
                if (!cancelRequested.contains(environment.id)) {
                    task = task.copy(
                        state = state,
                        currentOperation = op,
                        updatedAt = System.currentTimeMillis()
                    )
                    persist(task)
                    appendLog("stage=${state.name} $op")
                    notifier.update(task)
                }
            },
            onLog = { line ->
                appendLog(line)
            },
            onDownloadProgress = { done, total ->
                if (!cancelRequested.contains(environment.id)) {
                    val pct = com.crossberry.noxs.environments.StageWeights.overallFromDownload(done, total)
                    task = task.copy(
                        state = SetupState.DOWNLOADING,
                        progress = pct,
                        currentOperation = "Downloading — ${formatBytes(done)}" +
                            (if (total > 0) " / ${formatBytes(total)}" else ""),
                        downloadedBytes = done,
                        expectedBytes = total,
                        updatedAt = System.currentTimeMillis()
                    )
                    persist(task)
                    notifier.update(task)
                }
            },
            onExtractProgress = { entries ->
                if (!cancelRequested.contains(environment.id)) {
                    task = task.copy(
                        state = SetupState.EXTRACTING,
                        progress = -1,
                        currentOperation = "Extracting filesystem ($entries files)",
                        updatedAt = System.currentTimeMillis()
                    )
                    // Extraction progress updates are frequent; persist but notify rarely.
                    runCatching { taskFile(task.id).writeText(task.toJson()) }
                }
            },
            passwordProvider = passwordProvider
        )

        markEnvironment(environment, EnvironmentStatus.INSTALLING)
        jobs[environment.id] = scope.launch {
            try {
                val ok = provider.runInstall(ctx, variant)
                if (cancelRequested.contains(environment.id) || task.state == SetupState.CANCELLED) {
                    finishCancelled(task, environment)
                } else if (ok) {
                    task = task.copy(
                        state = SetupState.READY, progress = 100,
                        currentOperation = "Ready", completedAt = System.currentTimeMillis()
                    )
                    persist(task)
                    markEnvironment(environment.with(status = EnvironmentStatus.READY), null)
                    notifier.finished(environment.displayName, success = true)
                    appendLog("result=READY")
                } else {
                    fail(task, environment, "installation did not complete")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                task = task.copy(state = SetupState.CANCELLED, currentOperation = "Cancelled")
                persist(task)
                finishCancelled(task, environment)
            } catch (e: Throwable) {
                NoxsLog.e("SetupTasks", "setup failed for ${environment.id}", e)
                appendLog("error=${e.message}")
                fail(task, environment, e.message ?: e.javaClass.simpleName)
            } finally {
                jobs.remove(environment.id)
                cancelRequested.remove(environment.id)
                notifier.clear()
            }
        }
        return task
    }

    fun cancel(environmentId: String) {
        val task = _tasks.value.firstOrNull {
            it.environmentId == environmentId && it.state.isWorking
        } ?: return
        cancelRequested.add(environmentId)
        val cancelled = task.copy(state = SetupState.CANCELLED, currentOperation = "Cancelling…")
        persist(cancelled)
        // The pipeline observes the flag through isCancelled() at safe points
        // (between tar entries, between download chunks, between stages).
    }

    fun isRunning(environmentId: String): Boolean = jobs.containsKey(environmentId)

    /**
     * Recovery scan (spec §44): any task persisted in a working state after
     * process death becomes RECOVERY_REQUIRED; the user chooses continue,
     * restart or remove (spec §43).
     */
    fun scanForRecovery(): List<SetupTask> {
        val recovered = mutableListOf<SetupTask>()
        val loaded = loadAll()
        for (task in loaded) {
            if (SetupStateMachine.needsRecovery(task.state)) {
                val flagged = task.copy(
                    state = SetupState.RECOVERY_REQUIRED,
                    currentOperation = "Setup was interrupted",
                    error = task.error.ifBlank { "process died during ${task.state.name.lowercase()}" }
                )
                persist(flagged)
                recovered += flagged
            }
        }
        _tasks.value = loaded.sortedByDescending { it.startedAt }
        return recovered
    }

    fun latestTaskFor(environmentId: String): SetupTask? =
        _tasks.value.lastOrNull { it.environmentId == environmentId }

    fun removeEnvironmentFiles(environment: Environment) {
        val dir = File(environment.storagePath)
        if (dir.exists() && dir.canonicalPath.startsWith(environmentsRoot.canonicalPath)) {
            dir.deleteRecursively()
        }
        tasksDir.listFiles()?.filter { file ->
            SetupTask.fromJson(runCatching { file.readText() }.getOrDefault(""))
                ?.environmentId == environment.id
        }?.forEach { it.delete() }
        onEnvironmentRemoved(environment.id)
    }

    // -------------------------------------------------------------- helpers

    private fun fail(task: SetupTask, environment: Environment, error: String) {
        val failed = task.copy(
            state = SetupState.FAILED,
            error = error,
            currentOperation = "Setup failed",
            completedAt = System.currentTimeMillis()
        )
        persist(failed)
        markEnvironment(environment.with(status = EnvironmentStatus.RECOVERY_REQUIRED), null)
        notifier.finished(environment.displayName, success = false)
    }

    private fun finishCancelled(task: SetupTask, environment: Environment) {
        persist(task.copy(completedAt = System.currentTimeMillis()))
        markEnvironment(
            environment.with(status = EnvironmentStatus.NOT_INSTALLED),
            EnvironmentStatus.NOT_INSTALLED
        )
        cancelRequested.remove(environment.id)
    }

    private fun markEnvironment(env: Environment, status: EnvironmentStatus?) {
        val updated = if (status != null) env.with(status = status) else env
        onEnvironmentChanged(updated)
    }

    private fun sanitize(line: String): String {
        // Never log secrets (spec §41): password prompts are masked upstream;
        // this strips any accidental credential-looking assignments.
        return line
            .replace(Regex("(?i)(password|passwd|token|secret)\\s*[:=]\\s*\\S+"), "$1=<redacted>")
            .take(2000)
    }

    private fun formatBytes(b: Long): String = when {
        b >= 1L shl 30 -> "%.2f GB".format(b.toDouble() / (1L shl 30))
        b >= 1L shl 20 -> "%.1f MB".format(b.toDouble() / (1L shl 20))
        else -> "$b B"
    }
}

// ----------------------------------------------------------------- notifier

/**
 * Android notification for long-running setup tasks (spec §18): one
 * notification per task updated in place, [Open] and [Stop] actions,
 * honest indeterminate progress when no percentage is known.
 */
class SetupNotifier(private val context: Context) {

    private val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Noxs setup", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Environment installation progress" }
            )
        }
    }

    fun update(task: SetupTask) {
        runCatching {
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, com.crossberry.noxs.ui.EnvironmentInstallActivity::class.java)
                    .putExtra("environmentId", task.environmentId)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                pendingFlags()
            )
            val stop = PendingIntent.getBroadcast(
                context, 1,
                Intent(ACTION_STOP).putExtra("environmentId", task.environmentId),
                pendingFlags()
            )
            val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL_ID)
            else @Suppress("DEPRECATION") Notification.Builder(context)
            builder.setContentTitle("Noxs")
                .setContentText("${task.title} — ${task.currentOperation}")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(Notification.Action.Builder(null, "Open", open).build())
                .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            if (task.progress in 0..100) {
                builder.setProgress(100, task.progress, false)
                if (task.progress > 0) {
                    builder.setSubText("$task.progress%")
                }
            } else {
                builder.setProgress(0, 0, true)
            }
            notifySafely(builder.build())
        }
    }

    fun finished(name: String, success: Boolean) {
        runCatching {
            val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL_ID)
            else @Suppress("DEPRECATION") Notification.Builder(context)
            builder.setContentTitle("Noxs")
                .setContentText(if (success) "$name environment is ready" else "$name setup failed")
                .setSmallIcon(
                    if (success) android.R.drawable.stat_sys_download_done
                    else android.R.drawable.stat_notify_error
                )
                .setOngoing(false)
            notifySafely(builder.build())
        }
    }

    fun clear() {
        runCatching { nm.cancel(NOTIFICATION_ID) }
    }

    private fun notifySafely(notification: Notification) {
        try {
            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted — setup continues, UI still shows progress.
        }
    }

    private fun pendingFlags(): Int =
        if (Build.VERSION.SDK_INT >= 31)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT

    companion object {
        private const val CHANNEL_ID = "noxs-setup"
        private const val NOTIFICATION_ID = 4201
        const val ACTION_STOP = "com.crossberry.noxs.action.STOP_SETUP"
    }
}
