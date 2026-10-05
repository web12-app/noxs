/*
 * Noxs — original implementation.
 * Main terminal screen: multiple sessions (tabs + drawer), visible action bar,
 * direct Android IME-to-PTY input, terminal canvas, extra keys, and live status.
 */
package com.crossberry.noxs.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.FileObserver
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.crossberry.noxs.NoxsApplication
import com.crossberry.noxs.R
import com.crossberry.noxs.databinding.ActivityTerminalBinding
import com.crossberry.noxs.runtime.AndroidProcSource
import com.crossberry.noxs.runtime.NoxsActivityCenter
import com.crossberry.noxs.runtime.NoxsActivityRecord
import com.crossberry.noxs.runtime.NoxsAptSetup
import com.crossberry.noxs.runtime.NoxsPaths
import com.crossberry.noxs.runtime.NoxsProcSampler
import com.crossberry.noxs.runtime.NoxsResources
import com.crossberry.noxs.runtime.NoxsService
import com.crossberry.noxs.runtime.NoxsSessionManager
import com.crossberry.noxs.runtime.NoxsStorageBridge
import com.crossberry.noxs.runtime.NoxsTreeUsage
import com.crossberry.noxs.runtime.RuntimeHolder
import com.crossberry.noxs.terminal.emulator.TerminalSession
import com.crossberry.noxs.terminal.emulator.TerminalSessionClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class TerminalActivity : AppCompatActivity(), TerminalSessionClient {

    private lateinit var binding: ActivityTerminalBinding
    private lateinit var paths: NoxsPaths
    private var resources: NoxsResources? = null
    private var sessionManager: NoxsSessionManager? = null
    private lateinit var storageBridge: NoxsStorageBridge
    private lateinit var storagePickerLauncher: ActivityResultLauncher<Intent>
    private var storageObserver: FileObserver? = null
    private var storageObservedPath: String? = null
    private var pendingStorageAction: PendingStorageAction? = null

    private data class PendingStorageAction(
        val requestId: String,
        val category: NoxsStorageBridge.Category
    )

    private var current: NoxsSessionManager.Entry? = null

    // Terminal settings (terminal.* keys) — applied live, no shell restart
    private var terminalSettings: TerminalSettings = TerminalSettings()
    private val scrollOffsets = mutableMapOf<String, Int>()
    private val searchHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val searchRunnable = Runnable {
        binding.terminal.setSearchQuery(binding.etSearch.text?.toString().orEmpty())
    }

    // One-shot guard: the background APT repair failure toast is shown once
    // per resumed session, never per state re-emission.
    private var aptFailureAnnounced = false

    // Nullable (not lateinit): adoptRuntimeNow() collects the sessions StateFlow,
    // which can emit synchronously during onCreate before/around adapter binding.
    private var sessionAdapter: TwoLineAdapter? = null

    // Background Activity Center integration
    private val activityCenter: NoxsActivityCenter
        get() = (application as NoxsApplication).activityCenter
    private val procSampler by lazy { NoxsProcSampler(AndroidProcSource()) }
    private var floatingPanel: FloatingStatusPanel? = null
    private var panelJob: Job? = null
    private var attachedAtMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storagePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            onStoragePickerResult(result)
        }
        binding = ActivityTerminalBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = application as com.crossberry.noxs.NoxsApplication
        paths = app.paths
        storageBridge = NoxsStorageBridge(this, paths)
        val savedStorageRequest = savedInstanceState?.getString(STATE_STORAGE_REQUEST_ID)
        val savedStorageCategory = savedInstanceState?.getString(STATE_STORAGE_CATEGORY)?.let { NoxsStorageBridge.Category.fromKey(it) }
        if (savedStorageRequest != null && savedStorageCategory != null) {
            pendingStorageAction = PendingStorageAction(savedStorageRequest, savedStorageCategory)
        }

        if (!paths.isInstalled()) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
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
        binding.btnActionActivity.setOnClickListener { startActivity(Intent(this, ActivityCenterActivity::class.java)) }
        binding.btnActionActivity.setOnLongClickListener {
            val id = current?.activityId
            if (id != null) toggleFloatingPanel(id) else {
                Toast.makeText(this, R.string.activity_none, Toast.LENGTH_SHORT).show()
            }
            true
        }
        binding.btnActionCopy.setOnClickListener { copyTerminalText() }
        binding.btnActionPaste.setOnClickListener { pasteIntoShell() }
        binding.btnActionClear.setOnClickListener { clearTerminal() }
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
            binding.navActivity to ActivityCenterActivity::class.java,
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

        // Terminal view config + extra keys wiring
        binding.terminal.volumeKeysEnabled = true
        binding.terminal.onTerminalLinkClick = ::openTerminalLink
        binding.terminal.onScrollStateChanged = { atLiveBottom ->
            binding.btnTerminalLatest.visibility = if (atLiveBottom) View.GONE else View.VISIBLE
        }
        binding.btnTerminalLatest.setOnClickListener { binding.terminal.scrollToBottom() }
        binding.extraKeys.terminalView = binding.terminal

        // Terminal UX upgrade: persisted settings, toolbar, search, indicator
        applyTerminalSettings()
        wireTerminalToolbar()
        wireSearchBar()
        binding.terminal.onIndicatorChanged = { label ->
            binding.btnTerminalLatest.text = label?.let { "↓ $it" } ?: getString(R.string.terminal_latest)
        }

        // Status widget ticker
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    refreshStatus()
                    renderSessionStatus()
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
        // APT security setup runs in the service background (NoxsAptSetup).
        // It never gates the shell: observe it only for a failure notice.
        observeAptSetup()
        onNewIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Notification "Open" lands here: focus the tapped activity and raise
        // the floating status panel over the terminal.
        intent?.getStringExtra(NoxsService.EXTRA_FOCUS_ACTIVITY)?.let { activityId ->
            val record = activityCenter.find(activityId)
            val session = record?.sessionId?.let { label ->
                sessionManager?.sessions?.value?.firstOrNull { it.label == label }
            }
            if (session != null) {
                current = session
                attachCurrent()
            }
            showFloatingPanel(activityId)
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

    /**
     * Observes the service-side APT bootstrap purely for diagnostics. The
     * shell is never blocked on it; a failed repair is announced once and
     * retried automatically on the next launch.
     */
    private fun observeAptSetup() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                NoxsAptSetup.state.collect { state ->
                    if (state === NoxsAptSetup.State.FAILED && !aptFailureAnnounced) {
                        aptFailureAnnounced = true
                        Toast.makeText(
                            this@TerminalActivity,
                            getString(R.string.apt_background_failed, NoxsAptSetup.detail.take(160)),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
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

    private fun newSession(root: Boolean) {
        // No setup gates here by design: the terminal clears straight to an
        // active shell while the APT security layer repairs in the background.
        val mgr = sessionManager ?: RuntimeHolder.sessions ?: run {
            Toast.makeText(this, R.string.err_generic, Toast.LENGTH_SHORT).show()
            return
        }
        sessionManager = mgr
        val prefs = getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        val showWelcome = !prefs.getBoolean(PREF_FIRST_SHELL_WELCOME_SHOWN, false)
        mgr.createSession("", loginAsRoot = root, showFirstRunWelcome = showWelcome)
            .onSuccess { entry ->
                if (showWelcome) {
                    prefs.edit().putBoolean(PREF_FIRST_SHELL_WELCOME_SHOWN, true).apply()
                }
                current = entry
                attachCurrent()
                binding.drawer.closeDrawer(GravityCompat.START)
            }
            .onFailure {
                Toast.makeText(this, it.message ?: getString(R.string.err_generic), Toast.LENGTH_LONG).show()
            }
    }

    private fun openTerminalLink(uri: String): Boolean {
        val intent = when (uri) {
            WEBSITE_URL -> Intent(Intent.ACTION_VIEW, Uri.parse(WEBSITE_URL))
            SUPPORT_EMAIL -> Intent(Intent.ACTION_SENDTO, Uri.parse(SUPPORT_EMAIL))
            else -> return false
        }
        return try {
            startActivity(intent)
            true
        } catch (_: Exception) {
            Toast.makeText(this, "No compatible app is available to open this link.", Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun attachCurrent() {
        val entry = current ?: return
        binding.terminal.attach(entry.session)
        binding.statusSession.text = entry.label + (if (entry.loginAsRoot) " ▸ ${getString(R.string.session_root_badge)}" else "")
        attachedAtMs = System.currentTimeMillis()
        // Live settings: scrollback capacity + preserved reading position.
        binding.terminal.applyAppearance(terminalSettings.toAppearance())
        runCatching { entry.session.emulator.setScrollbackLimit(terminalSettings.scrollbackLines) }
        binding.terminal.post {
            if (terminalSettings.preserveScrollPosition) {
                scrollOffsets[entry.label]?.let { binding.terminal.restoreScrollOffset(it) }
            }
            if (terminalSettings.autoFocusTerminal) {
                binding.terminal.requestFocus()
                binding.terminal.showSoftInput()
            }
        }
        renderSessionStatus()
    }

    /** Loads terminal.* settings and applies them to the live view. */
    private fun applyTerminalSettings() {
        terminalSettings = TerminalSettingsStore.load(AndroidTerminalPrefs.from(this))
        binding.terminal.applyAppearance(terminalSettings.toAppearance())
        binding.terminal.onFontSizeChanged = { sp ->
            TerminalSettingsStore.putInt(AndroidTerminalPrefs.from(this), "terminal.fontSize", sp.toInt())
            binding.fontSizeLabel.text = "${sp.toInt()}"
        }
        binding.fontSizeLabel.text = "${terminalSettings.fontSizeSp}"
        binding.terminalToolbarScroll.visibility =
            if (terminalSettings.showToolbar) View.VISIBLE else View.GONE
        current?.session?.let { runCatching { it.emulator.setScrollbackLimit(terminalSettings.scrollbackLines) } }
    }

    private fun wireTerminalToolbar() {
        binding.btnScrollTop.setOnClickListener { binding.terminal.scrollToTop() }
        binding.btnScrollBottom.setOnClickListener { binding.terminal.scrollToBottom() }
        binding.btnTbCopy.setOnClickListener { copyTerminalText() }
        binding.btnTbPaste.setOnClickListener { pasteIntoShell() }
        binding.btnTbSearch.setOnClickListener { toggleSearchBar() }
        binding.btnFontDecr.setOnClickListener { binding.terminal.nudgeFontSize(-1f) }
        binding.btnFontIncr.setOnClickListener { binding.terminal.nudgeFontSize(1f) }
        binding.fontSizeLabel.setOnClickListener { binding.terminal.resetFontSize() }
        binding.btnTbMore.setOnClickListener { showTerminalMoreMenu(it) }
    }

    private fun wireSearchBar() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // Debounced: large scrollbacks must not be re-scanned per keystroke.
                searchHandler.removeCallbacks(searchRunnable)
                searchHandler.postDelayed(searchRunnable, 250)
            }
        })
        binding.btnSearchPrev.setOnClickListener { binding.terminal.searchPreviousMatch() }
        binding.btnSearchNext.setOnClickListener { binding.terminal.searchNextMatch() }
        binding.btnSearchClose.setOnClickListener { closeSearchBar() }
        binding.terminal.onSearchResult = { count, index ->
            binding.tvSearchCount.text = if (count <= 0) "" else "${index + 1}/$count"
        }
    }

    private fun toggleSearchBar() {
        if (binding.searchBar.visibility == View.VISIBLE) closeSearchBar() else openSearchBar()
    }

    private fun openSearchBar() {
        binding.searchBar.visibility = View.VISIBLE
        binding.etSearch.requestFocus()
    }

    private fun closeSearchBar() {
        searchHandler.removeCallbacks(searchRunnable)
        binding.searchBar.visibility = View.GONE
        binding.tvSearchCount.text = ""
        binding.terminal.clearSearch()
    }

    private fun showTerminalMoreMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, MORE_CLEAR_SCROLLBACK, 0, getString(R.string.terminal_menu_clear_scrollback))
        popup.menu.add(0, MORE_SAVE_OUTPUT, 1, getString(R.string.terminal_menu_save_output))
        popup.menu.add(0, MORE_RESET_FONT, 2, getString(R.string.terminal_menu_reset_font))
        popup.menu.add(0, MORE_SETTINGS, 3, getString(R.string.nav_settings))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MORE_CLEAR_SCROLLBACK -> {
                    binding.terminal.clearScrollback()
                    true
                }
                MORE_SAVE_OUTPUT -> {
                    saveTerminalOutput()
                    true
                }
                MORE_RESET_FONT -> {
                    binding.terminal.resetFontSize()
                    true
                }
                MORE_SETTINGS -> {
                    startActivity(Intent(this, TerminalSettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    /** Shares the full transcript through the system share sheet. */
    private fun saveTerminalOutput() {
        val text = binding.terminal.selectionOrTranscriptText()
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.terminal_menu_save_output))
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching { startActivity(Intent.createChooser(send, null)) }
            .onFailure { Toast.makeText(this, R.string.err_generic, Toast.LENGTH_SHORT).show() }
    }

    /** Subtle session/process status: ● RUNNING / ○ IDLE / ✓ EXITED / ✗ FAILED. */
    private fun renderSessionStatus() {
        val session = current?.session
        binding.statusProc.text = when {
            session == null -> ""
            session.isRunning && session.lastOutputAtMs > 0 &&
                System.currentTimeMillis() - session.lastOutputAtMs < 10_000L ->
                getString(R.string.status_proc_running, session.label, session.pid)
            session.isRunning -> getString(R.string.status_proc_idle, session.label, session.pid)
            session.exitCode == 0 -> getString(R.string.status_proc_exited, session.exitCode)
            else -> getString(R.string.status_proc_failed, session.exitCode)
        }
    }

    private fun copyTerminalText() {
        val text = binding.terminal.selectionOrTranscriptText()
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("noxs-terminal", text))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun pasteIntoShell() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: return
        val session = current?.session ?: return
        session.write(session.emulator.paste(clip))
    }

    private fun clearTerminal() {
        val session = current?.session ?: return
        session.emulator.clearScreen()
        binding.terminal.onSessionOutputChanged(session)
        session.write("clear\n".toByteArray(Charsets.UTF_8))
        binding.terminal.invalidate()
    }

    override fun onStart() {
        super.onStart()
        if (::storageBridge.isInitialized && paths.isInstalled()) startStorageObserver()
    }

    override fun onStop() {
        // Preserve the reading position across backgrounding/recreation.
        current?.let { scrollOffsets[it.label] = binding.terminal.currentScrollOffset() }
        storageObserver?.stopWatching()
        storageObserver = null
        storageObservedPath = null
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingStorageAction?.let { pending ->
            outState.putString(STATE_STORAGE_REQUEST_ID, pending.requestId)
            outState.putString(STATE_STORAGE_CATEGORY, pending.category.key)
        }
        super.onSaveInstanceState(outState)
    }

    private fun startStorageObserver() {
        val recreated = runCatching { storageBridge.ensureControlDirectories() }.getOrElse {
            Toast.makeText(this, "Unable to prepare Noxs storage commands", Toast.LENGTH_LONG).show()
            return
        }
        cleanupStaleStorageTransfers()
        armStorageObserver(force = recreated)
        processPendingStorageRequests()
    }

    private fun armStorageObserver(force: Boolean) {
        val path = paths.storageRequests.absolutePath
        if (!force && storageObserver != null && storageObservedPath == path) return
        storageObserver?.stopWatching()
        storageObserver = null
        storageObservedPath = null
        val requestMask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO
        val directoryInvalidatedMask = FileObserver.DELETE_SELF or FileObserver.MOVE_SELF
        val watchMask = requestMask or directoryInvalidatedMask
        val observer = object : FileObserver(path, watchMask) {
            override fun onEvent(event: Int, path: String?) {
                if ((event and directoryInvalidatedMask) != 0) {
                    runOnUiThread {
                        storageObserver?.stopWatching()
                        storageObserver = null
                        storageObservedPath = null
                        startStorageObserver()
                    }
                    return
                }
                if (path == null || (event and requestMask) == 0) return
                runOnUiThread { processPendingStorageRequests() }
            }
        }
        runCatching { observer.startWatching() }.onSuccess {
            storageObserver = observer
            storageObservedPath = path
        }.onFailure {
            Toast.makeText(this, "Unable to watch Noxs storage requests", Toast.LENGTH_LONG).show()
        }
    }

    private fun cleanupStaleStorageTransfers() {
        val cutoff = System.currentTimeMillis() - 6L * 60L * 60L * 1000L
        listOf(paths.storageRequests, paths.storageResponses, paths.storagePayloads, paths.storageResponsePayloads)
            .forEach { directory ->
                directory.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
            }
    }

    private fun processPendingStorageRequests() {
        val recreated = runCatching { storageBridge.ensureControlDirectories() }.getOrElse { return }
        if (recreated) {
            cleanupStaleStorageTransfers()
            armStorageObserver(force = true)
        }
        if (pendingStorageAction != null) return
        val pendingFiles = paths.storageRequests.listFiles().orEmpty()
        pendingFiles.filter(::isSymbolicLink).forEach { it.delete() }
        val request = pendingFiles
            .filter { it.isFile && !isSymbolicLink(it) }
            .sortedBy { it.lastModified() }
            .firstOrNull() ?: return
        val requestId = request.name
        if (!requestId.matches(Regex("[A-Za-z0-9._-]{1,120}"))) {
            request.delete()
            return
        }
        val lines = runCatching { request.readLines(Charsets.UTF_8) }.getOrDefault(emptyList())
        request.delete()
        if (lines.isEmpty()) {
            completeStorageRequest(requestId, NoxsStorageBridge.CommandResult(false, "Empty storage request"))
            return
        }
        val operation = lines[0].trim().lowercase()
        val categoryKey = lines.getOrNull(1)?.trim().orEmpty()
        val path = lines.getOrNull(2).orEmpty()
        val argument = lines.getOrNull(3).orEmpty()

        when (operation) {
            "setup" -> confirmStoragePicker(requestId, NoxsStorageBridge.Category.SHARED)
            "map" -> {
                val category = NoxsStorageBridge.Category.fromKey(categoryKey)
                if (category == null) {
                    completeStorageRequest(requestId, NoxsStorageBridge.CommandResult(false, "Unknown storage category: $categoryKey"))
                } else confirmStoragePicker(requestId, category)
            }
            "reset" -> confirmStorageReset(requestId)
            else -> lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    storageBridge.execute(requestId, operation, categoryKey, path, argument)
                }
                completeStorageRequest(requestId, result)
            }
        }
    }

    private fun confirmStoragePicker(requestId: String, category: NoxsStorageBridge.Category) {
        pendingStorageAction = PendingStorageAction(requestId, category)
        AlertDialog.Builder(this)
            .setTitle("Noxs Storage Access")
            .setMessage(
                "Choose a folder for ${category.label}. Android will grant Noxs access only to the folder you select. " +
                    "SAF access is available through Noxs storage commands, not as a Linux POSIX path."
            )
            .setPositiveButton("Choose folder") { _, _ -> launchStoragePicker() }
            .setNegativeButton("Cancel") { _, _ ->
                val pending = pendingStorageAction
                pendingStorageAction = null
                if (pending != null) completeStorageRequest(
                    pending.requestId,
                    NoxsStorageBridge.CommandResult(false, "Permission not granted; storage mappings were not changed")
                )
            }
            .setOnCancelListener {
                val pending = pendingStorageAction
                pendingStorageAction = null
                if (pending != null) completeStorageRequest(
                    pending.requestId,
                    NoxsStorageBridge.CommandResult(false, "Permission not granted; storage mappings were not changed")
                )
            }
            .show()
    }

    private fun launchStoragePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
        }
        storagePickerLauncher.launch(intent)
    }

    private fun onStoragePickerResult(result: ActivityResult) {
        val pending = pendingStorageAction ?: return
        pendingStorageAction = null
        val uri: Uri? = result.data?.data
        if (result.resultCode != RESULT_OK || uri == null) {
            completeStorageRequest(
                pending.requestId,
                NoxsStorageBridge.CommandResult(false, "Folder access was cancelled or denied; no mapping was saved")
            )
            return
        }
        val response = runCatching {
            storageBridge.grantTree(pending.category, uri, result.data?.flags ?: 0)
            NoxsStorageBridge.CommandResult(
                true,
                "${pending.category.label} folder permission saved.\n${storageBridge.statusText()}"
            )
        }.getOrElse {
            NoxsStorageBridge.CommandResult(false, it.message ?: "Could not retain access to the selected folder")
        }
        completeStorageRequest(pending.requestId, response)
    }

    private fun confirmStorageReset(requestId: String) {
        pendingStorageAction = PendingStorageAction(requestId, NoxsStorageBridge.Category.SHARED)
        AlertDialog.Builder(this)
            .setTitle("Reset Noxs storage mappings?")
            .setMessage(
                "This releases Noxs' saved folder permissions and clears its mappings. " +
                    "It will not delete, move, or modify files in Android storage."
            )
            .setPositiveButton("Reset mappings") { _, _ ->
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { storageBridge.resetMappings() }
                            .fold(
                                onSuccess = { NoxsStorageBridge.CommandResult(true, "Noxs storage mappings reset. Android files were not deleted.") },
                                onFailure = { NoxsStorageBridge.CommandResult(false, it.message ?: "Storage reset failed") }
                            )
                    }
                    pendingStorageAction = null
                    completeStorageRequest(requestId, result)
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                pendingStorageAction = null
                completeStorageRequest(requestId, NoxsStorageBridge.CommandResult(false, "Reset cancelled; no mappings changed"))
            }
            .setOnCancelListener {
                pendingStorageAction = null
                completeStorageRequest(requestId, NoxsStorageBridge.CommandResult(false, "Reset cancelled; no mappings changed"))
            }
            .show()
    }

    private fun completeStorageRequest(requestId: String, result: NoxsStorageBridge.CommandResult) {
        if (!requestId.matches(Regex("[A-Za-z0-9._-]{1,120}"))) return
        if (runCatching { storageBridge.ensureControlDirectories() }.isFailure) return
        val response = File(paths.storageResponses, requestId)
        val temporary = File(paths.storageResponses, "$requestId.${java.util.UUID.randomUUID()}.tmp")
        runCatching {
            paths.storageResponses.mkdirs()
            temporary.writeText("${if (result.ok) "OK" else "ERR"}\n${result.output.trimEnd()}\n", Charsets.UTF_8)
            if (isSymbolicLink(response)) response.delete()
            if (response.exists() && !response.delete()) {
                temporary.delete()
                return@runCatching
            }
            if (!temporary.renameTo(response)) {
                temporary.delete()
                return@runCatching
            }
        }
        if (pendingStorageAction?.requestId == requestId) pendingStorageAction = null
        if (storageObserver != null) processPendingStorageRequests()
    }

    private fun isSymbolicLink(file: File): Boolean = runCatching {
        val mode = android.system.Os.lstat(file.absolutePath).st_mode
        (mode and android.system.OsConstants.S_IFMT) == android.system.OsConstants.S_IFLNK
    }.getOrDefault(false)

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
        renderSessionTabs(list)
    }

    private fun renderSessionTabs(list: List<NoxsSessionManager.Entry>) {
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
                    renderSessionTabs(list)
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

    }

    private suspend fun refreshStatus() {
        val r = resources ?: return
        val usage = withContext(Dispatchers.IO) { r.currentUsage() }
        binding.statusCpu.text = getString(R.string.status_cpu, "%.0f".format(usage.cpuPercent))
        binding.statusMem.text = getString(R.string.status_mem, usage.memMb.toString(), usage.totalMemMb.toString())
        binding.statusStorage.text = getString(R.string.status_storage, usage.storageUsedMb.toString())
    }

    // ---- Background Activity Center: floating status panel ----

    private fun toggleFloatingPanel(activityId: String) {
        if (floatingPanel != null && floatingPanel?.visibility == View.VISIBLE) {
            hideFloatingPanel()
        } else {
            showFloatingPanel(activityId)
        }
    }

    private fun showFloatingPanel(activityId: String) {
        val record = activityCenter.find(activityId) ?: run {
            Toast.makeText(this, R.string.activity_none, Toast.LENGTH_SHORT).show()
            return
        }
        val existing = floatingPanel
        val panel = existing ?: FloatingStatusPanel(this).also { created ->
            floatingPanel = created
            binding.terminalContainer.addView(created)
            created.onTerminal = {
                val rec = activityCenter.find(activityId)
                val entry = rec?.sessionId?.let { label ->
                    sessionManager?.sessions?.value?.firstOrNull { it.label == label }
                }
                if (entry != null) {
                    current = entry
                    attachCurrent()
                }
                hideFloatingPanel()
            }
            created.onLogs = { showPanelLogs(activityId) }
            created.onDetails = { showPanelDetails(activityId) }
            created.onStop = { confirmPanelStop(activityId) }
        }
        panel.render(record, null, System.currentTimeMillis())
        panel.showAnimated()
        startPanelLoop(activityId)
    }

    private fun hideFloatingPanel() {
        panelJob?.cancel()
        panelJob = null
        floatingPanel?.hideAnimated()
    }

    /** Lightweight polling only while the panel is visible (spec: never hot). */
    private fun startPanelLoop(activityId: String) {
        panelJob?.cancel()
        panelJob = lifecycleScope.launch {
            while (isActive) {
                renderPanel(activityId)
                delay(2_000L)
            }
        }
    }

    private fun renderPanel(activityId: String) {
        val panel = floatingPanel ?: return
        val record = activityCenter.find(activityId)
        if (record == null || record.status.isTerminal) {
            hideFloatingPanel()
            return
        }
        val rootPids = buildSet {
            record.pid?.takeIf { it > 0 }?.let { add(it) }
            // Attribute the whole process tree of the owning session.
            sessionManager?.sessions?.value
                ?.firstOrNull { it.label == record.sessionId }
                ?.let { entry -> entry.session.pid.takeIf { it > 0 }?.let(::add) }
        }
        val usage: NoxsTreeUsage? = if (rootPids.isEmpty()) null
        else runCatching { procSampler.sampleTree(rootPids) }.getOrNull()
        panel.render(record, usage, System.currentTimeMillis())
    }

    private fun showPanelLogs(activityId: String) {
        val lines = activityCenter.recentOutput(activityId)
        AlertDialog.Builder(this)
            .setTitle(R.string.activity_logs_title)
            .setMessage(if (lines.isEmpty()) "No recent output." else lines.joinToString("\n"))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showPanelDetails(activityId: String) {
        val record = activityCenter.find(activityId) ?: return
        val usage = procSampler.sampleTree(setOfNotNull(record.pid?.takeIf { it > 0 }))
        AlertDialog.Builder(this)
            .setTitle(R.string.activity_details_title)
            .setMessage(
                FloatingStatusPanel.statusText(record.status) + "\n" +
                    "Session: " + record.sessionId.ifBlank { "—" } + "\n" +
                    "Process: " + record.command.ifBlank { record.title } + "\n" +
                    "PID: " + (record.pid?.toString() ?: "—") + "\n" +
                    "CPU: " + (usage?.let { "%.0f%%".format(it.cpuPercent) } ?: "—") + "\n" +
                    "RAM: " + (usage?.let { "${it.rssKb / 1024} MB" } ?: "—") + "\n" +
                    "Processes: " + (usage?.processCount ?: 0) + "\n" +
                    "Directory: " + record.workingDirectory.ifBlank { "—" }
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun confirmPanelStop(activityId: String) {
        val record = activityCenter.find(activityId) ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.activity_stop_confirm_title)
            .setMessage(getString(R.string.activity_stop_confirm_msg, record.title))
            .setPositiveButton(R.string.activity_stop) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) { activityCenter.requestStop(activityId) }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ---- TerminalSessionClient forwarding to the terminal views ----

    override fun onTextChanged(session: TerminalSession) {
        if (current?.session === session) binding.terminal.onSessionOutputChanged(session)
    }

    override fun onTitleChanged(session: TerminalSession) {
        if (current?.session === session) binding.statusSession.text = session.label
    }

    override fun onBell(session: TerminalSession) {
        Toast.makeText(this, "🔔 ${session.label}", Toast.LENGTH_SHORT).show()
    }

    override fun onSessionFinished(session: TerminalSession) {
        val wasCurrent = current?.session === session
        if (wasCurrent) {
            binding.terminal.invalidate()
            binding.statusSession.text = "${session.label} (exited ${session.exitCode})"
        }
        current?.activityId?.let { id ->
            if (floatingPanel?.visibility == View.VISIBLE) hideFloatingPanel()
        }

        // Smart exit: only the finished shell closes. Noxs stays alive while
        // other sessions or background activity remain, and shuts down cleanly
        // only when the LAST shell exits with nothing else running (spec 8-10).
        val mgr = sessionManager ?: RuntimeHolder.sessions
        val remaining = mgr?.sessions?.value?.size ?: 0
        val center = (application as NoxsApplication).activityCenter
        when {
            remaining > 0 ->
                Toast.makeText(this, getString(R.string.session_closed_remaining, remaining), Toast.LENGTH_LONG).show()
            center.hasPersistentWork() ->
                Toast.makeText(this, getString(R.string.session_closed_background), Toast.LENGTH_LONG).show()
            wasCurrent && System.currentTimeMillis() - attachedAtMs < SHUTDOWN_GRACE_MS ->
                // A shell that died immediately is a bootstrap failure, not a
                // user "exit" — keep the app open so the user can diagnose.
                Toast.makeText(this, getString(R.string.session_closed_background), Toast.LENGTH_LONG).show()
            else -> {
                Toast.makeText(this, getString(R.string.noxs_shutting_down), Toast.LENGTH_LONG).show()
                NoxsService.requestStopAll(this)
                finishAffinity()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (binding.drawer.isDrawerOpen(GravityCompat.START)) {
            binding.drawer.closeDrawer(GravityCompat.START)
        } else if (terminalSettings.confirmExit &&
            sessionManager?.sessions?.value?.any { it.session.isRunning } == true
        ) {
            AlertDialog.Builder(this)
                .setTitle(R.string.terminal_exit_title)
                .setMessage(R.string.terminal_exit_msg)
                .setPositiveButton(R.string.action_confirm) { _, _ -> finishAffinity() }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        } else {
            super.onBackPressed()
        }
    }

    private companion object {
        const val STATE_STORAGE_REQUEST_ID = "storage_request_id"
        const val STATE_STORAGE_CATEGORY = "storage_category"
        const val PREFS_SETTINGS = "noxs_settings"
        const val PREF_FIRST_SHELL_WELCOME_SHOWN = "first_shell_welcome_shown"
        const val WEBSITE_URL = "https://crossberry.vercel.app"
        const val SUPPORT_EMAIL = "mailto:crossberryweb@gmail.com"
        const val SHUTDOWN_GRACE_MS = 3_000L
        const val MORE_CLEAR_SCROLLBACK = 301
        const val MORE_SAVE_OUTPUT = 302
        const val MORE_RESET_FONT = 303
        const val MORE_SETTINGS = 304
    }
}
