/*
 * Noxs — original implementation.
 * Main terminal screen: multiple sessions (tabs + drawer), visible action bar,
 * clear terminal TextView + interactive TTY canvas, direct command input bar,
 * extra keys, navigation drawer to all manager screens, live resource status.
 */
package com.noxs.linux.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.noxs.linux.R
import com.noxs.linux.databinding.ActivityTerminalBinding
import com.noxs.linux.runtime.NoxsPaths
import com.noxs.linux.runtime.NoxsResources
import com.noxs.linux.runtime.NoxsService
import com.noxs.linux.runtime.NoxsSessionManager
import com.noxs.linux.runtime.RootfsConfigurator
import com.noxs.linux.runtime.RuntimeHolder
import com.noxs.linux.terminal.emulator.TerminalColors
import com.noxs.linux.terminal.emulator.TerminalEmulator
import com.noxs.linux.terminal.emulator.TerminalSession
import com.noxs.linux.terminal.emulator.TerminalSessionClient
import com.noxs.linux.terminal.emulator.TextStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TerminalActivity : AppCompatActivity(), TerminalSessionClient {

    private lateinit var binding: ActivityTerminalBinding
    private lateinit var paths: NoxsPaths
    private var resources: NoxsResources? = null
    private var sessionManager: NoxsSessionManager? = null

    private var current: NoxsSessionManager.Entry? = null

    // Nullable (not lateinit): adoptRuntimeNow() collects the sessions StateFlow,
    // which can emit synchronously during onCreate before/around adapter binding.
    private var sessionAdapter: TwoLineAdapter? = null

    /** True = show clear selectable TextView mode; False = raw TTY Canvas view. */
    private var useTextViewMode = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTerminalBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = application as com.noxs.linux.NoxsApplication
        paths = app.paths

        if (!paths.isInstalled()) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        // Repair any broken 0-byte symlinks from older installs in the background
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { RootfsConfigurator.ensureHealthyRootfs(paths) }
        }

        // Bind the session list adapter BEFORE adopting the runtime
        sessionAdapter = TwoLineAdapter.bind(binding.sessionList, emptyList())

        NoxsService.start(this)
        adoptRuntime()

        // Top bar: Drawer menu button
        binding.btnDrawer.setOnClickListener {
            if (binding.drawer.isDrawerOpen(GravityCompat.START)) {
                binding.drawer.closeDrawer(GravityCompat.START)
            } else {
                binding.drawer.openDrawer(GravityCompat.START)
            }
        }

        // Drawer: sessions
        binding.btnNewSession.setOnClickListener { newSession(root = false) }
        binding.btnNewRoot.setOnClickListener { newSession(root = true) }

        // Visible Action Bar buttons
        binding.btnActionNewShell.setOnClickListener { newSession(root = false) }
        binding.btnActionNewRoot.setOnClickListener { newSession(root = true) }
        binding.btnActionCopy.setOnClickListener { copyTerminalText() }
        binding.btnActionPaste.setOnClickListener { pasteIntoShell() }
        binding.btnActionClear.setOnClickListener { clearTerminal() }
        binding.btnActionMode.setOnClickListener { toggleDisplayMode() }
        binding.btnActionPackages.setOnClickListener { startActivity(Intent(this, PackageManagerActivity::class.java)) }
        binding.btnActionFiles.setOnClickListener { startActivity(Intent(this, FileBrowserActivity::class.java)) }
        binding.btnActionServices.setOnClickListener { startActivity(Intent(this, ServiceManagerActivity::class.java)) }
        binding.btnActionProcesses.setOnClickListener { startActivity(Intent(this, ProcessManagerActivity::class.java)) }
        binding.btnActionSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.btnActionDiagnostics.setOnClickListener { startActivity(Intent(this, DiagnosticsActivity::class.java)) }

        // Drawer: managers
        val routes = mapOf(
            binding.navFiles to FileBrowserActivity::class.java,
            binding.navPackages to PackageManagerActivity::class.java,
            binding.navUsers to UserManagerActivity::class.java,
            binding.navProcesses to ProcessManagerActivity::class.java,
            binding.navServices to ServiceManagerActivity::class.java,
            binding.navEnv to EnvVarsActivity::class.java,
            binding.navStorage to StorageActivity::class.java,
            binding.navSecurity to SecurityActivity::class.java,
            binding.navSettings to SettingsActivity::class.java,
            binding.navDiagnostics to DiagnosticsActivity::class.java,
            binding.navAbout to AboutActivity::class.java
        )
        routes.forEach { (view, activity) ->
            view.setOnClickListener {
                binding.drawer.closeDrawer(GravityCompat.START)
                startActivity(Intent(this, activity))
            }
        }

        // Direct command input bar
        binding.btnRunCmd.setOnClickListener { submitCommandBar() }
        binding.etCommand.setOnEditorActionListener { _, actionId, event ->
            val isEnter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE || isEnter) {
                submitCommandBar()
                true
            } else {
                false
            }
        }
        binding.btnKeyboard.setOnClickListener {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            if (useTextViewMode) {
                binding.etCommand.requestFocus()
                imm.showSoftInput(binding.etCommand, InputMethodManager.SHOW_IMPLICIT)
            } else {
                binding.terminal.showSoftInput()
            }
        }

        // Tapping the terminal TextView focuses the command input bar
        binding.terminalTxtView.setOnClickListener {
            binding.etCommand.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.etCommand, InputMethodManager.SHOW_IMPLICIT)
        }

        // Terminal view config + extra keys wiring
        binding.terminal.volumeKeysEnabled = true
        binding.extraKeys.terminalView = binding.terminal
        applyDisplayMode()

        // Status widget ticker
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    refreshStatus()
                    delay(5000)
                }
            }
        }

        val existing = RuntimeHolder.sessions?.sessions?.value
        if (!existing.isNullOrEmpty()) {
            current = existing.first()
            attachCurrent()
        } else {
            autoCreatePending = true
            fulfillAutoCreateIfReady()
        }
    }

    private var autoCreatePending = false

    private fun fulfillAutoCreateIfReady() {
        if (!autoCreatePending) return
        val mgr = sessionManager ?: RuntimeHolder.sessions ?: return
        sessionManager = mgr
        autoCreatePending = false
        val list = mgr.sessions.value
        if (list.isEmpty()) {
            newSession(root = false)
        } else if (current == null) {
            current = list.first()
            attachCurrent()
        }
    }

    private fun adoptRuntime() {
        if (RuntimeHolder.sessions == null) {
            lifecycleScope.launch(Dispatchers.IO) {
                var tries = 0
                while (RuntimeHolder.sessions == null && tries < 20) {
                    delay(250); tries++
                }
                withContext(Dispatchers.Main) {
                    adoptRuntimeNow()
                }
            }
        } else {
            adoptRuntimeNow()
        }
    }

    private fun adoptRuntimeNow() {
        sessionManager = RuntimeHolder.sessions
        resources = NoxsResources(paths)
        sessionManager?.client = this
        lifecycleScope.launch {
            sessionManager?.sessions?.collect { list ->
                if (current == null && list.isNotEmpty()) {
                    current = list.last()
                    attachCurrent()
                } else if (current != null) {
                    val replacement = list.firstOrNull { it.label == current?.label && it.session !== current?.session }
                    if (replacement != null) {
                        current = replacement
                        attachCurrent()
                    }
                }
                renderSessions(list)
            }
        }
        fulfillAutoCreateIfReady()
    }

    private fun newSession(root: Boolean, initialCommand: String? = null) {
        val mgr = sessionManager ?: RuntimeHolder.sessions ?: run {
            Toast.makeText(this, R.string.err_generic, Toast.LENGTH_SHORT).show()
            return
        }
        sessionManager = mgr
        mgr.createSession("", loginAsRoot = root)
            .onSuccess { entry ->
                current = entry
                attachCurrent()
                binding.drawer.closeDrawer(GravityCompat.START)
                if (!initialCommand.isNullOrBlank()) {
                    entry.session.write((initialCommand + "\n").toByteArray(Charsets.UTF_8))
                }
            }
            .onFailure {
                Toast.makeText(this, it.message ?: getString(R.string.err_generic), Toast.LENGTH_LONG).show()
            }
    }

    private fun attachCurrent() {
        val entry = current ?: return
        binding.terminal.attach(entry.session)
        binding.statusSession.text = entry.label + (if (entry.loginAsRoot) " ▸ ${getString(R.string.session_root_badge)}" else "")
        binding.tvPrompt.text = if (entry.loginAsRoot) "root@noxs:# " else "noxs@android:$ "
        binding.tvPrompt.setTextColor(
            getColor(if (entry.loginAsRoot) R.color.noxs_accent else R.color.noxs_primary)
        )
        updateTerminalTextView(entry.session)
    }

    private fun submitCommandBar() {
        val cmd = binding.etCommand.text?.toString().orEmpty()
        binding.etCommand.setText("")
        sendCommandToShell(cmd)
    }

    private fun sendCommandToShell(cmd: String) {
        val entry = current
        if (entry == null || !entry.session.isRunning) {
            newSession(root = false, initialCommand = cmd)
            return
        }
        entry.session.write((cmd + "\n").toByteArray(Charsets.UTF_8))
        binding.terminal.scrollToBottom()
        updateTerminalTextView(entry.session)
    }

    private fun copyTerminalText() {
        val text = current?.session?.emulator?.transcriptText()?.replace("█", "") ?: ""
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("noxs-terminal", text))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun pasteIntoShell() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: return
        val session = current?.session ?: return
        session.write(session.emulator.paste(clip))
        updateTerminalTextView(session)
    }

    private fun clearTerminal() {
        val session = current?.session ?: return
        session.emulator.buffer.reset()
        session.write("clear\n".toByteArray(Charsets.UTF_8))
        binding.terminal.invalidate()
        updateTerminalTextView(session)
    }

    private fun toggleDisplayMode() {
        useTextViewMode = !useTextViewMode
        applyDisplayMode()
        current?.session?.let { updateTerminalTextView(it) }
    }

    private fun applyDisplayMode() {
        binding.terminalTxtScroll.visibility = if (useTextViewMode) View.VISIBLE else View.GONE
        binding.btnActionMode.text = getString(
            if (useTextViewMode) R.string.action_mode_tty else R.string.action_mode_txt
        )
    }

    private fun renderSessions(list: List<NoxsSessionManager.Entry>) {
        val adapter = sessionAdapter ?: return
        adapter.submit(list.map { entry ->
            TwoLineRow(
                title = entry.label + (if (entry.loginAsRoot) " [ROOT]" else ""),
                subtitle = if (entry.session.isRunning) "running" else "exited (${entry.session.exitCode})",
                onClick = {
                    current = entry
                    attachCurrent()
                    binding.drawer.closeDrawer(GravityCompat.START)
                },
                onLongClick = {
                    sessionManager?.closeSession(entry)
                    if (current === entry) current = list.firstOrNull { it !== entry }
                    attachCurrent()
                }
            )
        })
        binding.statusDot.setBackgroundResource(
            if (list.any { it.session.isRunning }) R.color.noxs_ok else R.color.noxs_warn
        )
        renderSessionTabsAndQuickCommands(list)
    }

    private fun renderSessionTabsAndQuickCommands(list: List<NoxsSessionManager.Entry>) {
        val row = binding.sessionTabsRow
        row.removeAllViews()
        val dp = getResources().displayMetrics.density

        // 1. Active session tabs
        list.forEach { entry ->
            val isSelected = entry === current
            val tab = TextView(this).apply {
                val dot = if (entry.session.isRunning) "● " else "○ "
                val rootTag = if (entry.loginAsRoot) " [root]" else ""
                text = "$dot${entry.label}$rootTag"
                typeface = Typeface.MONOSPACE
                textSize = 11.5f
                setTextColor(if (isSelected) Color.BLACK else 0xffe6e6e6.toInt())
                background = GradientDrawable().apply {
                    cornerRadius = 6f * dp
                    setColor(if (isSelected) 0xff3ddc84.toInt() else 0xff232a35.toInt())
                    setStroke((1 * dp).toInt(), if (isSelected) 0xff3ddc84.toInt() else 0xff2f3946.toInt())
                }
                setPadding((10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt())
                setOnClickListener {
                    current = entry
                    attachCurrent()
                    renderSessionTabsAndQuickCommands(list)
                }
                setOnLongClickListener {
                    sessionManager?.closeSession(entry)
                    true
                }
            }
            row.addView(tab, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (6 * dp).toInt() })
        }

        // 2. Quick one-tap shell commands (ls, pwd, whoami, noxs help, apt update)
        val quickCommands = listOf(
            "ls -la" to "ls -la",
            "pwd" to "pwd",
            "whoami" to "whoami",
            "noxs help" to "noxs help",
            "apt update" to "sudo apt update"
        )
        quickCommands.forEach { (label, cmd) ->
            val chip = TextView(this).apply {
                text = "$ $label"
                typeface = Typeface.MONOSPACE
                textSize = 11f
                setTextColor(0xff4fc3f7.toInt())
                background = GradientDrawable().apply {
                    cornerRadius = 6f * dp
                    setColor(0xff161b22.toInt())
                    setStroke((1 * dp).toInt(), 0xff2f3946.toInt())
                }
                setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
                gravity = Gravity.CENTER
                setOnClickListener { sendCommandToShell(cmd) }
            }
            row.addView(chip, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (6 * dp).toInt() })
        }
    }

    /**
     * Renders the terminal buffer into `binding.terminalTxtView` with full ANSI
     * colors, bold attributes, and a visible green cursor block.
     */
    private fun updateTerminalTextView(session: TerminalSession) {
        val emu = session.emulator
        // Automatically switch to raw TTY Canvas view if an app enters alt screen (nano/vim/htop)
        if (emu.buffer.usingAlt && useTextViewMode) {
            useTextViewMode = false
            applyDisplayMode()
        }
        val spannable = formatEmulatorSpannable(emu)
        binding.terminalTxtView.setText(spannable, TextView.BufferType.SPANNABLE)
        binding.terminalTxtScroll.post {
            binding.terminalTxtScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun formatEmulatorSpannable(emu: TerminalEmulator): SpannableStringBuilder {
        val buf = emu.buffer
        val out = SpannableStringBuilder()
        val defaultFg = 0xffe6e6e6.toInt()
        val defaultBg = 0xff10141a.toInt()
        val cursorColor = 0xff3ddc84.toInt()

        // 1. Scrollback lines
        for (i in buf.scrollbackSize - 1 downTo 0) {
            val line = buf.scrollbackLine(i) ?: continue
            appendStyledLine(out, line, -1, buf.cursorCol, false, defaultFg, defaultBg, cursorColor)
            if (!line.lineWrap) out.append('\n')
        }

        // 2. Visible screen lines up to last non-empty row or cursorRow
        val scr = buf.screen()
        var lastRow = buf.cursorRow.coerceIn(0, buf.rows - 1)
        for (r in buf.rows - 1 downTo 0) {
            if (scr[r].text().isNotEmpty()) {
                if (r > lastRow) lastRow = r
                break
            }
        }
        for (r in 0..lastRow) {
            val line = scr[r]
            val isCursorLine = (r == buf.cursorRow && buf.cursorVisible)
            appendStyledLine(out, line, r, buf.cursorCol, isCursorLine, defaultFg, defaultBg, cursorColor)
            if (r < lastRow && !line.lineWrap) out.append('\n')
        }
        return out
    }

    private fun appendStyledLine(
        out: SpannableStringBuilder,
        line: com.noxs.linux.terminal.emulator.TerminalBuffer.Line,
        rowIdx: Int,
        cursorCol: Int,
        isCursorLine: Boolean,
        defaultFg: Int,
        defaultBg: Int,
        cursorColor: Int
    ) {
        var endCol = line.chars.size
        while (endCol > 0 && line.chars[endCol - 1] == ' ' &&
            TerminalColors.colorOf(line.styles[endCol - 1], false, defaultFg, defaultBg) == defaultBg
        ) {
            endCol--
        }
        if (isCursorLine && cursorCol + 1 > endCol) {
            endCol = (cursorCol + 1).coerceAtMost(line.chars.size)
        }

        var col = 0
        while (col < endCol) {
            val style = line.styles[col]
            var runEnd = col + 1
            while (runEnd < endCol && line.styles[runEnd] == style) runEnd++

            val startOffset = out.length
            for (i in col until runEnd) {
                if (i > col && TextStyle.isWideCont(line.styles[i])) continue
                if (isCursorLine && i == cursorCol && line.chars[i] == ' ') {
                    out.append('█')
                } else {
                    out.append(line.chars[i])
                }
            }
            val endOffset = out.length
            if (endOffset > startOffset) {
                val fg = TerminalColors.colorOf(style, true, defaultFg, defaultBg)
                val bg = TerminalColors.colorOf(style, false, defaultFg, defaultBg)
                val flags = TextStyle.flags(style)
                if (fg != defaultFg) {
                    out.setSpan(ForegroundColorSpan(fg), startOffset, endOffset, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if (bg != defaultBg) {
                    out.setSpan(BackgroundColorSpan(bg), startOffset, endOffset, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if (flags and TextStyle.FLAG_BOLD != 0) {
                    out.setSpan(StyleSpan(Typeface.BOLD), startOffset, endOffset, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            col = runEnd
        }

        if (isCursorLine && cursorCol >= 0) {
            // Highlight cursor cell in green
            val lineStart = out.lastIndexOf('\n').let { if (it < 0) 0 else it + 1 }
            val cursorPos = (lineStart + cursorCol).coerceAtMost(out.length - 1)
            if (cursorPos >= lineStart && cursorPos < out.length) {
                out.setSpan(
                    ForegroundColorSpan(cursorColor),
                    cursorPos,
                    cursorPos + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }

    private suspend fun refreshStatus() {
        val r = resources ?: return
        val usage = withContext(Dispatchers.IO) { r.currentUsage() }
        binding.statusCpu.text = getString(R.string.status_cpu, "%.0f".format(usage.cpuPercent))
        binding.statusMem.text = getString(R.string.status_mem, usage.memMb.toString(), usage.totalMemMb.toString())
        binding.statusStorage.text = getString(R.string.status_storage, usage.storageUsedMb.toString())
    }

    // ---- TerminalSessionClient forwarding to the terminal views ----

    override fun onTextChanged(session: TerminalSession) {
        if (current?.session === session) {
            binding.terminal.invalidate()
            updateTerminalTextView(session)
        }
    }

    override fun onTitleChanged(session: TerminalSession) {
        if (current?.session === session) binding.statusSession.text = session.label
    }

    override fun onBell(session: TerminalSession) {
        Toast.makeText(this, "🔔 ${session.label}", Toast.LENGTH_SHORT).show()
    }

    override fun onSessionFinished(session: TerminalSession) {
        if (current?.session === session) {
            binding.terminal.invalidate()
            updateTerminalTextView(session)
            binding.statusSession.text = "${session.label} (exited ${session.exitCode})"
        }
        Toast.makeText(
            this,
            getString(R.string.session_finished_toast, session.label, session.exitCode),
            Toast.LENGTH_LONG
        ).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (binding.drawer.isDrawerOpen(GravityCompat.START)) {
            binding.drawer.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }
}
