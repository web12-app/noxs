/*
 * Noxs — original implementation.
 * Session manager: creates/holds terminal sessions bound to the Debian
 * userspace, enforces the session quota, and keeps them alive behind the
 * foreground service.
 */
package com.noxs.linux.runtime

import com.noxs.linux.MainLoop
import com.noxs.linux.shared.NoxsConstants
import com.noxs.linux.shared.NoxsLog
import com.noxs.linux.terminal.emulator.TerminalSession
import com.noxs.linux.terminal.emulator.TerminalSessionClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

class NoxsSessionManager(
    private val paths: NoxsPaths,
    private val launcher: ProotLauncher,
    private val resources: NoxsResources
) : TerminalSessionClient {

    data class Entry(
        val session: TerminalSession,
        val label: String,
        val startedAt: Long,
        val loginAsRoot: Boolean,
        val isFallback: Boolean = false
    )

    private val _sessions = MutableStateFlow<List<Entry>>(emptyList())
    val sessions: StateFlow<List<Entry>> = _sessions

    private val quota get() = resources.load()

    /** Set by TerminalActivity to route emulator callbacks to the UI. */
    var client: TerminalSessionClient? = null

    private var sessionSeq = 0

    fun createSession(label: String, loginAsRoot: Boolean = false): Result<Entry> {
        if (_sessions.value.size >= quota.maxSessions) {
            return Result.failure(IllegalStateException("Session quota reached (${quota.maxSessions})"))
        }
        if (!paths.isInstalled()) {
            return Result.failure(IllegalStateException("Noxs Linux environment is not set up"))
        }
        runCatching { RootfsConfigurator.ensureHealthyRootfs(paths) }

        sessionSeq++
        val name = label.ifBlank { if (loginAsRoot) "root-$sessionSeq" else "shell-$sessionSeq" }
        val session = TerminalSession(
            label = name,
            client = this,
            scrollbackLines = NoxsConstants.DEFAULT_SCROLLBACK
        )
        // route emulator callbacks via main loop
        session.mainThreadDispatcher = { MainLoop.post(it) }

        // Write immediate header into the emulator buffer so the terminal view
        // is never blank while proot initializes the Debian userspace.
        val userTag = if (loginAsRoot) "root@noxs" else "noxs@android"
        val header = "\u001b[1;32m● Noxs Linux\u001b[0m — session \u001b[1m$name\u001b[0m ($userTag)\r\n"
        session.emulator.write(header.toByteArray(Charsets.UTF_8))

        val env = launcher.buildEnv(
            if (loginAsRoot) mapOf("NOXS_ROOT_LOGIN" to "1") else emptyMap()
        )
        val workDir = if (loginAsRoot) NoxsConstants.DEFAULT_ROOT_HOME else NoxsConstants.DEFAULT_USER_HOME
        val argv = launcher.buildArgv(ProotLauncher.Options(loginAsRoot = loginAsRoot, workDir = workDir))

        session.start(argv.toTypedArray(), env, paths.tmp.absolutePath, preferPty = true)

        val entry = Entry(session, name, System.currentTimeMillis(), loginAsRoot, isFallback = false)
        _sessions.value = _sessions.value + entry
        NoxsLog.i("Sessions", "created '$name' pid=${session.pid} pty=${session.isPty}")
        updateNotification()
        return Result.success(entry)
    }

    private fun startFallbackSession(failedEntry: Entry, exitCode: Int) {
        val shBin = listOf("/system/bin/sh", "/bin/sh").firstOrNull { File(it).canExecute() } ?: return
        val session = TerminalSession(
            label = failedEntry.label,
            client = this,
            scrollbackLines = NoxsConstants.DEFAULT_SCROLLBACK
        )
        session.mainThreadDispatcher = { MainLoop.post(it) }

        val prevText = failedEntry.session.emulator.transcriptText().replace("█", "").trim()
        val banner = buildString {
            if (prevText.isNotEmpty()) {
                append(prevText.replace("\n", "\r\n"))
                append("\r\n")
            }
            append("\u001b[1;33m[Noxs] proot exited (code $exitCode) — attached sandbox shell in rootfs\u001b[0m\r\n")
            append("Type \u001b[1;36mls -la\u001b[0m, \u001b[1;36mpwd\u001b[0m, or \u001b[1;36muname -a\u001b[0m.\r\n\r\n")
        }
        session.emulator.write(banner.toByteArray(Charsets.UTF_8))

        val homeDir = if (failedEntry.loginAsRoot) {
            File(paths.rootfs, "root").apply { mkdirs() }
        } else {
            paths.rootfsHomeNoxs.apply { mkdirs() }
        }
        val prompt = if (failedEntry.loginAsRoot) "root@noxs:\\w# " else "noxs@android:\\w$ "
        val env = arrayOf(
            "PATH=${File(paths.rootfs, "usr/local/bin").absolutePath}:${File(paths.rootfs, "usr/bin").absolutePath}:/system/bin:/system/xbin",
            "HOME=${homeDir.absolutePath}",
            "USER=${if (failedEntry.loginAsRoot) "root" else NoxsConstants.DEFAULT_USER}",
            "LOGNAME=${if (failedEntry.loginAsRoot) "root" else NoxsConstants.DEFAULT_USER}",
            "TERM=${NoxsConstants.TERM_VALUE}",
            "PS1=$prompt",
            "TMPDIR=${paths.tmp.absolutePath}"
        )
        session.start(arrayOf(shBin, "-i"), env, homeDir.absolutePath, preferPty = true)

        val fallbackEntry = Entry(
            session = session,
            label = failedEntry.label,
            startedAt = System.currentTimeMillis(),
            loginAsRoot = failedEntry.loginAsRoot,
            isFallback = true
        )
        _sessions.value = (_sessions.value - failedEntry) + fallbackEntry
        updateNotification()
        MainLoop.post { client?.onTextChanged(session) }
    }

    fun closeSession(entry: Entry) {
        entry.session.kill()
        _sessions.value = _sessions.value - entry
        updateNotification()
    }

    fun closeAll() {
        _sessions.value.forEach { it.session.kill() }
        _sessions.value = emptyList()
        updateNotification()
    }

    private fun updateNotification() {
        NoxsService.notifySessionsChanged(_sessions.value.size)
    }

    // ---- TerminalSessionClient forwarding (session -> UI + service) ----

    override fun onTextChanged(session: TerminalSession) {
        MainLoop.post { client?.onTextChanged(session) }
    }

    override fun onTitleChanged(session: TerminalSession) {
        MainLoop.post { client?.onTitleChanged(session) }
    }

    override fun onBell(session: TerminalSession) {
        MainLoop.post { client?.onBell(session) }
    }

    override fun onSessionFinished(session: TerminalSession) {
        val entry = _sessions.value.firstOrNull { it.session === session }
        val elapsedMs = if (entry != null) System.currentTimeMillis() - entry.startedAt else Long.MAX_VALUE
        NoxsLog.i("Sessions", "session '${session.label}' exited code=${session.exitCode} elapsed=${elapsedMs}ms")

        // If proot died immediately upon startup, automatically fall back to an
        // interactive sandbox shell so the user never gets a dead/blank terminal.
        if (entry != null && !entry.isFallback && session.exitCode != 0 && elapsedMs < 2500L) {
            startFallbackSession(entry, session.exitCode)
            return
        }

        val exitNotice = "\r\n\u001b[1;33m[Process exited with code ${session.exitCode}]\u001b[0m\r\n"
        session.emulator.write(exitNotice.toByteArray(Charsets.UTF_8))

        if (entry != null) _sessions.value = _sessions.value - entry
        updateNotification()
        MainLoop.post { client?.onSessionFinished(session) }
    }
}
