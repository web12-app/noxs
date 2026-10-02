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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class NoxsSessionManager(
    private val paths: NoxsPaths,
    private val launcher: ProotLauncher,
    private val resources: NoxsResources
) : TerminalSession.TerminalSessionClient {

    data class Entry(
        val session: TerminalSession,
        val label: String,
        val startedAt: Long,
        val loginAsRoot: Boolean
    )

    private val _sessions = MutableStateFlow<List<Entry>>(emptyList())
    val sessions: StateFlow<List<Entry>> = _sessions

    private val quota get() = resources.load()

    /** Set by NoxsService to route emulator callbacks to the UI. */
    var client: TerminalSession.TerminalSessionClient? = null

    fun createSession(label: String, loginAsRoot: Boolean = false): Result<Entry> {
        if (_sessions.value.size >= quota.maxSessions) {
            return Result.failure(IllegalStateException("Session quota reached (${quota.maxSessions})"))
        }
        if (!paths.isInstalled()) {
            return Result.failure(IllegalStateException("Noxs Linux environment is not set up"))
        }
        val name = label.ifBlank { "shell-${_sessions.value.size + 1}" }
        val session = TerminalSession(
            label = name,
            client = this,
            scrollbackLines = com.noxs.linux.shared.NoxsConstants.DEFAULT_SCROLLBACK
        )
        // route emulator callbacks via main loop
        session.mainThreadDispatcher = { MainLoop.post(it) }

        val env = launcher.buildEnv(
            if (loginAsRoot) mapOf("NOXS_ROOT_LOGIN" to "1") else emptyMap()
        )
        val workDir = if (loginAsRoot) NoxsConstants.DEFAULT_ROOT_HOME else NoxsConstants.DEFAULT_USER_HOME
        val argv = launcher.buildArgv(ProotLauncher.Options(loginAsRoot = loginAsRoot, workDir = workDir))

        session.start(argv.toTypedArray(), env, paths.tmp.absolutePath, preferPty = true)

        val entry = Entry(session, name, System.currentTimeMillis(), loginAsRoot)
        _sessions.value = _sessions.value + entry
        NoxsLog.i("Sessions", "created '$name' pid=${session.pid} pty=${session.isPty}")
        updateNotification()
        return Result.success(entry)
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
        client?.onTextChanged(session)
    }

    override fun onTitleChanged(session: TerminalSession) {
        client?.onTitleChanged(session)
    }

    override fun onBell(session: TerminalSession) {
        client?.onBell(session)
    }

    override fun onSessionFinished(session: TerminalSession) {
        val entry = _sessions.value.firstOrNull { it.session === session }
        if (entry != null) _sessions.value = _sessions.value - entry
        client?.onSessionFinished(session)
        NoxsLog.i("Sessions", "session '${session.label}' exited code=${session.exitCode}")
        updateNotification()
    }
}
