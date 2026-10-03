/*
 * Noxs — original implementation.
 * Main terminal screen: multiple sessions (tabs in the drawer), extra keys,
 * navigation drawer to all manager screens, live resource status widget.
 */
package com.noxs.linux.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.noxs.linux.R
import com.noxs.linux.databinding.ActivityTerminalBinding
import com.noxs.linux.runtime.NoxsPaths
import com.noxs.linux.runtime.NoxsResources
import com.noxs.linux.runtime.NoxsRuntimeFactory
import com.noxs.linux.runtime.NoxsService
import com.noxs.linux.runtime.NoxsSessionManager
import com.noxs.linux.runtime.RuntimeHolder
import com.noxs.linux.terminal.view.TerminalView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TerminalActivity : AppCompatActivity(), com.noxs.linux.terminal.emulator.TerminalSessionClient {

    private lateinit var binding: ActivityTerminalBinding
    private lateinit var paths: NoxsPaths
    private var resources: NoxsResources? = null
    private var sessionManager: NoxsSessionManager? = null

    private var current: NoxsSessionManager.Entry? = null
    private lateinit var sessionAdapter: TwoLineAdapter

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

        NoxsService.start(this)
        adoptRuntime()

        sessionAdapter = TwoLineAdapter.bind(binding.sessionList, emptyList())

        // Drawer: sessions
        binding.btnNewSession.setOnClickListener { newSession(root = false) }
        binding.btnNewRoot.setOnClickListener { newSession(root = true) }

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
        routes.forEach { view, activity ->
            view.setOnClickListener {
                binding.drawer.closeDrawer(GravityCompat.START)
                startActivity(Intent(this, activity))
            }
        }

        // Terminal view config + extra keys wiring
        binding.terminal.volumeKeysEnabled = true
        binding.extraKeys.terminalView = binding.terminal

        // Status widget ticker
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    refreshStatus()
                    delay(5000)
                }
            }
        }

        if (RuntimeHolder.sessions?.sessions?.value.isNullOrEmpty()) {
            newSession(root = false)
        } else {
            current = RuntimeHolder.sessions?.sessions?.value?.firstOrNull()
            attachCurrent()
        }
    }

    private fun adoptRuntime() {
        // Ensure the service is up and grab its runtime singletons
        if (RuntimeHolder.sessions == null) {
            // Service starting asynchronously; wait briefly then adopt.
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
            sessionManager?.sessions?.collect { list -> renderSessions(list) }
        }
    }

    private fun newSession(root: Boolean) {
        val mgr = sessionManager ?: run {
            Toast.makeText(this, R.string.err_generic, Toast.LENGTH_SHORT).show()
            return
        }
        mgr.createSession("", loginAsRoot = root)
            .onSuccess { entry ->
                current = entry
                attachCurrent()
                binding.drawer.closeDrawer(GravityCompat.START)
            }
            .onFailure {
                Toast.makeText(this, it.message ?: getString(R.string.err_generic), Toast.LENGTH_LONG).show()
            }
    }

    private fun attachCurrent() {
        val entry = current ?: return
        binding.terminal.attach(entry.session)
        binding.statusSession.text = entry.label + (if (entry.loginAsRoot) " ▸ ${getString(R.string.session_root_badge)}" else "")
        // keep the keyboard available
        binding.terminal.requestFocus()
    }

    private fun renderSessions(list: List<NoxsSessionManager.Entry>) {
        sessionAdapter.submit(list.map { entry ->
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
                    if (current === entry) current = null
                }
            )
        })
        binding.statusDot.setBackgroundResource(
            if (list.any { it.session.isRunning }) R.color.noxs_ok else R.color.noxs_warn
        )
    }

    private suspend fun refreshStatus() {
        val r = resources ?: return
        val usage = withContext(Dispatchers.IO) { r.currentUsage() }
        binding.statusCpu.text = getString(R.string.status_cpu, "%.0f".format(usage.cpuPercent))
        binding.statusMem.text = getString(R.string.status_mem, usage.memMb.toString(), usage.totalMemMb.toString())
        binding.statusStorage.text = getString(R.string.status_storage, usage.storageUsedMb.toString())
    }

    // ---- TerminalSessionClient forwarding to the terminal view ----

    override fun onTextChanged(session: com.noxs.linux.terminal.emulator.TerminalSession) {
        if (current?.session === session) binding.terminal.invalidate()
    }

    override fun onTitleChanged(session: com.noxs.linux.terminal.emulator.TerminalSession) {
        if (current?.session === session) binding.statusSession.text = session.label
    }

    override fun onBell(session: com.noxs.linux.terminal.emulator.TerminalSession) {
        Toast.makeText(this, "🔔 ${session.label}", Toast.LENGTH_SHORT).show()
    }

    override fun onSessionFinished(session: com.noxs.linux.terminal.emulator.TerminalSession) {
        if (current?.session === session) {
            current = null
            binding.statusSession.text = getString(R.string.no_sessions)
        }
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
