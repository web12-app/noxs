/*
 * Noxs — original implementation.
 * Setup console session: owns the REAL setup process (NoxsInstaller) at
 * application scope and streams its real events — step transitions, live
 * download bytes, live tar-entry counts, real apt/dpkg output lines and
 * real failures — into a genuine terminal session (TerminalEmulator).
 *
 * The UI (SetupActivity) is a thin viewer on top: it attaches a TerminalView
 * to the terminal session and reconnects after any recreation. The setup
 * process itself never depends on the Activity lifecycle, and stopping it
 * (Stop / Ctrl+C) is cooperative, keeps verified partial downloads, and
 * never fakes or hides output.
 */
package com.crossberry.noxs.runtime

import android.content.Context
import com.crossberry.noxs.MainLoop
import com.crossberry.noxs.R
import com.crossberry.noxs.shared.NoxsConstants
import com.crossberry.noxs.shared.NoxsLog
import com.crossberry.noxs.terminal.emulator.TerminalSession
import com.crossberry.noxs.terminal.emulator.TerminalSessionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

class NoxsSetupSession(
    private val context: Context,
    private val paths: NoxsPaths,
    private val center: NoxsActivityCenter? = null
) : TerminalSessionClient {

    enum class State { IDLE, AWAITING_PASSWORD, RUNNING, REPAIRING, COMPLETED, FAILED, CANCELLED }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state

    /** 0 = not collecting, 1 = choose password, 2 = confirm password. */
    private val _passwordPhase = MutableStateFlow(0)
    val passwordPhase: StateFlow<Int> = _passwordPhase

    @Volatile
    var failureDetail: String = ""
        private set

    @Volatile
    var failureHintRes: Int = 0
        private set

    /** Genuine terminal session (no child process; the emulator renders events). */
    val terminal = TerminalSession("setup", this, scrollbackLines = NoxsConstants.DEFAULT_SCROLLBACK).apply {
        mainThreadDispatcher = { MainLoop.post(it) }
    }

    /** Main-thread hook: the terminal buffer changed (attach from an Activity). */
    @Volatile
    var onViewChanged: (() -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var cancelRequested = false

    /** Held only in memory until chpasswd consumes it; wiped right after. */
    @Volatile
    private var password: CharArray? = null

    private var liveLineActive = false
    private var lastLiveRenderMs = 0L
    private var lastCenterProgressMs = 0L
    private var setupActivityId: String? = null
    private var currentStep = 0
    private val startedAtMs = java.util.concurrent.atomic.AtomicLong(0L)

    private val logFile: File by lazy { File(paths.logs, "setup-console.log") }
    private val eventFile: File by lazy { File(paths.logs, "setup-events.jsonl") }

    // ------------------------------------------------------------- console

    /** Writes real setup events into the terminal buffer (main loop) + log. */
    fun console(text: String) {
        MainLoop.post { terminal.emulator.write(text.toByteArray(Charsets.UTF_8)) }
        appendLog(text)
    }

    private fun appendLog(text: String) {
        try {
            logFile.parentFile?.mkdirs()
            if (logFile.isFile && logFile.length() > MAX_LOG_BYTES) logFile.delete()
            logFile.appendText(text.replace(ANSI, ""))
        } catch (_: Exception) {
            // Console logging must never break the setup process.
        }
    }

    private fun endLiveLine(suffix: String = "") {
        if (!liveLineActive) return
        liveLineActive = false
        console(suffix + "\r\n")
    }

    private fun liveLine(plain: String, ansi: String) {
        val pad = (CONSOLE_WIDTH - plain.length).coerceAtLeast(0)
        console("\r" + ansi + " ".repeat(pad))
        liveLineActive = true
    }

    private fun stepPhrase(step: Int): String = when (step) {
        1 -> "Preparing Linux environment..."
        2 -> "Checking filesystem..."
        3 -> "Checking setup..."
        4 -> "Preparing your environment..."
        5 -> "Downloading Debian 12 Bookworm..."
        6 -> "Extracting filesystem..."
        7 -> "Setting up your environment..."
        8 -> "Creating Linux account..."
        9 -> "Preparing workspace..."
        10 -> "Preparing secure connections..."
        11 -> "Finalizing environment..."
        else -> "Working..."
    }

    private fun renderStep(step: Int) {
        if (currentStep in 1..11) {
            NoxsSetupEventLog.append(eventFile, NoxsSetupEventLog.Event(
                NoxsSetupEventLog.STEP_COMPLETED, currentStep, System.currentTimeMillis() - startedAtMs.get()))
        }
        currentStep = step
        NoxsSetupEventLog.append(eventFile, NoxsSetupEventLog.Event(
            NoxsSetupEventLog.STEP_STARTED, step, System.currentTimeMillis() - startedAtMs.get()))
        setupActivityId?.let { center?.attachOutput(it, stepPhrase(step)) }
        endLiveLine(" \u001b[1;32mok\u001b[0m")
        lastLiveRenderMs = 0L // next live tick renders immediately
        val phrase = "\u001b[1;32m[ Noxs ]\u001b[0m " + stepPhrase(step)
        // Steps 5 and 6 stream live progress on the same line.
        console(phrase + if (step == 5 || step == 6) " " else "\r\n")
    }

    private fun renderDownload(doneBytes: Long, totalBytes: Long) {
        val now = System.currentTimeMillis()
        if (doneBytes < totalBytes && now - lastLiveRenderMs < LIVE_RENDER_INTERVAL_MS) return
        lastLiveRenderMs = now
        val pct = if (totalBytes > 0) ((doneBytes * 100) / totalBytes).coerceIn(0L, 100L).toInt() else 0
        // Activity Center / notification: real download percentage, throttled.
        if (totalBytes > 0 && now - lastCenterProgressMs >= 1_500L) {
            lastCenterProgressMs = now
            setupActivityId?.let { center?.setProgress(it, pct) }
        }
        NoxsSetupEventLog.append(eventFile, NoxsSetupEventLog.Event(
            NoxsSetupEventLog.OUTPUT_TICK, 5, note = "download $pct%"))
        if (doneBytes >= totalBytes && totalBytes > 0) {
            endLiveLine(" \u001b[1;32mok (${mb(totalBytes)} MB, SHA-256 verified)\u001b[0m")
        } else {
            val plain = "[ Noxs ] ${mb(doneBytes)} / ${mb(totalBytes)} MB ($pct%)"
            liveLine(plain, "\u001b[1;32m[ Noxs ]\u001b[0m ${mb(doneBytes)} / ${mb(totalBytes)} MB ($pct%)")
        }
    }

    private fun renderExtract(entries: Long) {
        val now = System.currentTimeMillis()
        if (now - lastLiveRenderMs < LIVE_RENDER_INTERVAL_MS) return
        lastLiveRenderMs = now
        val count = "%,d".format(Locale.US, entries)
        val plain = "[ Noxs ] $count entries extracted"
        liveLine(plain, "\u001b[1;32m[ Noxs ]\u001b[0m $count entries extracted")
    }

    private fun mb(bytes: Long): String = "%.1f".format(Locale.US, bytes / (1024.0 * 1024.0))

    // ------------------------------------------------------------ lifecycle

    /**
     * Called by the setup console on every entry. Prints the banner and the
     * password prompts exactly once per fresh install; every later call is a
     * no-op reconnect (buffer already holds the live output).
     */
    fun prepareIfNeeded() {
        if (paths.isInstalled()) return
        synchronized(this) {
            if (_state.value != State.IDLE) return
            console("\u001b[1;32mNoxs Linux\u001b[0m \u001b[90m— Debian 12 (Bookworm) · setup console\u001b[0m\r\n")
            if (logFile.isFile && logFile.length() > 0) {
                console("\u001b[33m[ noxs ] A previous setup attempt was interrupted; verified partial downloads will be reused.\u001b[0m\r\n")
            }
            console("root@noxs:~# \u001b[1mnoxs setup\u001b[0m\r\n")
            askPassword(1)
            _state.value = State.AWAITING_PASSWORD
        }
    }

    private fun askPassword(phase: Int) {
        _passwordPhase.value = phase
        when (phase) {
            1 -> {
                console("\u001b[1mChoose a password for the 'noxs' account\u001b[0m\r\n")
                console("\u001b[90m(min 4 characters — used with sudo inside Noxs; never stored by the app)\u001b[0m\r\n")
                console("\u001b[1;36mEnter password:\u001b[0m \u001b[?25h")
            }
            else -> console("\u001b[1;36mConfirm password:\u001b[0m \u001b[?25h")
        }
    }

    /** Terminal-style password entry (echoed by the masked input row). */
    fun submitPassword(input: String) {
        if (_state.value != State.AWAITING_PASSWORD) return
        when (_passwordPhase.value) {
            1 -> {
                val chars = input.toCharArray()
                if (chars.size < 4 || chars.any { it == ':' || Character.isISOControl(it) }) {
                    chars.fill('\u0000')
                    console("\u001b[1;31mPassword must be at least 4 characters (':' and control characters are not allowed).\u001b[0m\r\n")
                    askPassword(1)
                    return
                }
                password = chars
                askPassword(2)
            }
            2 -> {
                val candidate = input.toCharArray()
                val stored = password
                if (stored == null || !candidate.contentEquals(stored)) {
                    candidate.fill('\u0000')
                    stored?.fill('\u0000')
                    password = null
                    console("\u001b[1;31mPasswords do not match. Try again.\u001b[0m\r\n")
                    askPassword(1)
                    return
                }
                candidate.fill('\u0000')
                _passwordPhase.value = 0
                startInstall()
            }
        }
    }

    private fun startInstall() {
        cancelRequested = false
        failureDetail = ""
        failureHintRes = 0
        _state.value = State.RUNNING
        startedAtMs.set(System.currentTimeMillis())
        currentStep = 0
        val hasPartial = paths.cache.listFiles()
            ?.any { it.isFile && it.name.endsWith(".part") && it.length() > 1_000_000L } == true
        NoxsSetupEventLog.append(eventFile, NoxsSetupEventLog.Event(
            if (hasPartial) NoxsSetupEventLog.RESUMED else NoxsSetupEventLog.STARTED))
        setupActivityId = center?.register(
            title = "Noxs setup",
            command = "noxs setup",
            sessionId = "setup",
            kind = NoxsActivityKind.SETUP,
            status = NoxsActivityStatus.STARTING
        )?.activityId
        setupActivityId?.let { center?.markRunning(it) }
        val installer = NoxsInstaller(
            context, paths,
            ProotLauncher(paths, NoxsResources(paths)),
            isCancelled = { cancelRequested }
        )
        scope.launch {
            val result = try {
                installer.install(installerProgress)
            } catch (e: SetupCancelledException) {
                NoxsInstaller.InstallResult.Cancelled
            } catch (e: Exception) {
                NoxsLog.e("SetupSession", "setup crashed", e)
                NoxsInstaller.InstallResult.Failure(R.string.err_bootstrap, e.message ?: e.javaClass.simpleName)
            }
            when (result) {
                is NoxsInstaller.InstallResult.Success -> {
                    endLiveLine()
                    NoxsSetupEventLog.append(eventFile, NoxsSetupEventLog.Event(
                        NoxsSetupEventLog.COMPLETED, elapsedMs = System.currentTimeMillis() - startedAtMs.get()))
                    setupActivityId?.let { center?.markCompleted(it) }
                    console("\u001b[1;32mNoxs Linux environment setup completed successfully.\u001b[0m\r\n")
                    console("root@noxs:~# ")
                    _state.value = State.COMPLETED
                }
                is NoxsInstaller.InstallResult.Failure -> {
                    failureDetail = result.detail
                    failureHintRes = result.userMessageRes
                    endLiveLine()
                    NoxsSetupEventLog.append(eventFile, NoxsSetupEventLog.Event(
                        NoxsSetupEventLog.FAILED, currentStep.takeIf { it > 0 },
                        System.currentTimeMillis() - startedAtMs.get(),
                        note = NoxsSetupEventLog.sanitize(result.detail.lineSequence().firstOrNull { it.isNotBlank() } ?: "setup failed")))
                    setupActivityId?.let { center?.markFailed(it, summary = "setup failed — real reason shown in the setup console") }
                    console("\u001b[1;31mERROR: setup failed\u001b[0m\r\n")
                    console("\u001b[1;31mReason:\u001b[0m\r\n")
                    result.detail.lineSequence().filter { it.isNotBlank() }.take(12).forEach {
                        console("  $it\r\n")
                    }
                    console("Setup stopped.\r\n")
                    console("root@noxs:~# ")
                    _state.value = State.FAILED
                }
                is NoxsInstaller.InstallResult.Cancelled -> {
                    endLiveLine()
                    NoxsSetupEventLog.append(eventFile, NoxsSetupEventLog.Event(
                        NoxsSetupEventLog.CANCELLED, currentStep.takeIf { it > 0 },
                        System.currentTimeMillis() - startedAtMs.get()))
                    setupActivityId?.let { center?.markStopped(it) }
                    console("\u001b[1;33m^C Setup stopped by user. Verified partial downloads are kept for resume.\u001b[0m\r\n")
                    console("root@noxs:~# ")
                    _state.value = State.CANCELLED
                }
            }
        }
    }

    /** [Retry] action: fresh password prompt, real resume of partial data. */
    fun retry() {
        val current = _state.value
        if (current != State.FAILED && current != State.CANCELLED) return
        cancelRequested = false
        failureDetail = ""
        failureHintRes = 0
        console("\r\n\u001b[1mRetrying setup\u001b[0m\r\n")
        askPassword(1)
        _state.value = State.AWAITING_PASSWORD
    }

    /**
     * [Repair] action: runs the REAL recovery pipeline — dpkg --configure -a,
     * authenticated dependency repair, CA certificates, verified HTTPS update —
     * through the signed APT bootstrapper while holding the package-manager
     * transaction. Real output streams into the console; locks are never
     * deleted or overridden.
     */
    fun repair() {
        val current = _state.value
        if (current != State.FAILED && current != State.CANCELLED) return
        cancelRequested = false
        _state.value = State.REPAIRING
        setupActivityId?.let { center?.attachOutput(it, "repairing Debian package layer") }
        console("\r\n\u001b[1;32m[ Noxs ]\u001b[0m Repairing Debian package layer (dpkg recovery + signed HTTPS update)…\r\n")
        scope.launch {
            val launcher = ProotLauncher(paths, NoxsResources(paths))
            val outcome: NoxsAptBootstrapper.Result? = try {
                NoxsPkgTransaction.withTransaction(paths, "setup-repair") {
                    NoxsAptBootstrapper(paths, launcher, { cancelRequested })
                        .initialize(force = true, onLog = { line ->
                            endLiveLine()
                            console("\u001b[90m[ noxs ]\u001b[0m $line\r\n")
                        })
                }
            } catch (e: SetupCancelledException) {
                null
            }
            endLiveLine()
            when {
                outcome == null && cancelRequested -> {
                    console("\u001b[1;33m^C Repair stopped. Partial state is kept.\u001b[0m\r\n")
                    console("root@noxs:~# ")
                    _state.value = State.CANCELLED
                }
                outcome == null -> {
                    console("\u001b[1;31m${NoxsPkgTransaction.BUSY_MESSAGE}\u001b[0m\r\n")
                    console("Setup stopped.\r\n")
                    console("root@noxs:~# ")
                    _state.value = State.FAILED
                }
                outcome.success -> {
                    console("\u001b[1;32m[ Noxs ]\u001b[0m Repair finished. Run Retry to complete setup.\r\n")
                    console("root@noxs:~# ")
                    _state.value = State.FAILED
                }
                else -> {
                    console("\u001b[1;31mERROR: repair failed\u001b[0m\r\n")
                    outcome.detail.lineSequence().filter { it.isNotBlank() }.take(12).forEach {
                        console("  $it\r\n")
                    }
                    console("Setup stopped.\r\n")
                    console("root@noxs:~# ")
                    _state.value = State.FAILED
                }
            }
        }
    }

    /** Stop / Ctrl+C: cooperative cancel; the engine aborts at the next check. */
    fun cancel() {
        if (_state.value != State.RUNNING && _state.value != State.REPAIRING) return
        cancelRequested = true
        console("\r\n\u001b[90m^C requested — stopping after the current operation…\u001b[0m\r\n")
    }

    // ------------------------------------------------- installer progress

    private val installerProgress = object : NoxsInstaller.Progress {
        override fun onStep(step: Int, titleRes: Int, detail: String) = renderStep(step)
        override fun onProgressBytes(downloaded: Long, total: Long) = renderDownload(downloaded, total)
        override fun onExtracted(entries: Long) = renderExtract(entries)
        override fun onLog(line: String) {
            endLiveLine()
            console("\u001b[90m[ noxs ]\u001b[0m $line\r\n")
        }
        override fun onPasswordRequired(): CharArray? {
            val pw = password
            password = null // consumed; the installer wipes the array after use
            return pw
        }
    }

    // ---------------------------------------------------- TerminalSessionClient

    override fun onTextChanged(session: TerminalSession) {
        onViewChanged?.invoke()
    }

    override fun onTitleChanged(session: TerminalSession) {
        onViewChanged?.invoke()
    }

    override fun onBell(session: TerminalSession) = Unit

    override fun onSessionFinished(session: TerminalSession) {
        onViewChanged?.invoke()
    }

    private companion object {
        const val LIVE_RENDER_INTERVAL_MS = 300L
        const val CONSOLE_WIDTH = 40
        const val MAX_LOG_BYTES = 512L * 1024
        val ANSI = Regex("\u001B\\[[0-9;]*[A-Za-z]")
    }
}
