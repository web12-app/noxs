/*
 * Noxs — original implementation.
 * EnvironmentManagerActivity (spec §32, §33, §34, §35, §40, §50, §55):
 * active environment, installed environments (switch / remove with explicit
 * confirmation), available environments (install), setup task list and
 * temporary-file cleanup.
 */
package com.crossberry.noxs.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.crossberry.noxs.R
import com.crossberry.noxs.environments.model.Environment
import com.crossberry.noxs.environments.model.EnvironmentStatus
import com.crossberry.noxs.environments.model.SetupFormat
import com.crossberry.noxs.environments.model.SetupState
import com.crossberry.noxs.runtime.NoxsService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class EnvironmentManagerActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = ScrollView(this)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        root.addView(container)
        setContentView(root)
        title(getString(R.string.env_manager_title))

        val app = application as com.crossberry.noxs.NoxsApplication
        scope.launch {
            app.environments.environments.collect { render() }
        }
        scope.launch {
            app.environments.taskManager.tasks.collect { renderTasks() }
        }
        render()
    }

    private fun render() {
        val app = application as com.crossberry.noxs.NoxsApplication
        val manager = app.environments
        val activeId = manager.activeId.value
        val envs = manager.environments.value
        val installed = envs.filter { it.status == EnvironmentStatus.READY }
        val installing = envs.filter {
            it.status in setOf(EnvironmentStatus.INSTALLING, EnvironmentStatus.RECOVERY_REQUIRED)
        }

        container.removeAllViews()

        // Active environment (spec §34).
        container.addView(header(getString(R.string.env_manager_active)))
        val active = envs.firstOrNull { it.id == activeId && it.status == EnvironmentStatus.READY }
        container.addView(TextView(this).apply {
            text = active?.let { "🟢 ${it.displayName}" } ?: getString(R.string.env_manager_none_active)
            textSize = 16f
            setPadding(dp(8), 0, 0, dp(8))
        })

        // Installed (spec §32, §33).
        if (installed.size > 1 || (installed.isNotEmpty() && active != null && installed.size > 1)) {
            container.addView(header(getString(R.string.env_manager_installed)))
            installed.filter { it.id != activeId }.forEach { env ->
                container.addView(envRow(env))
            }
        }

        // Installing / needs recovery (spec §43, §44).
        if (installing.isNotEmpty()) {
            container.addView(header(getString(R.string.env_manager_installing)))
            installing.forEach { env -> container.addView(envRow(env)) }
        }

        // Available (spec §3).
        container.addView(header(getString(R.string.env_manager_available)))
        for (provider in manager.providers) {
            if (installed.any { it.providerId == provider.id }) continue
            container.addView(TextView(this).apply {
                text = "· ${provider.displayName} — ${provider.description}"
                textSize = 14f
                setPadding(dp(8), dp(2), 0, dp(2))
            })
        }
        val canAdd = installed.isNotEmpty()
        if (canAdd) {
            container.addView(Button(this).apply {
                text = getString(R.string.env_manager_add)
                setOnClickListener { startActivity(Intent(this@EnvironmentManagerActivity, EnvironmentPickerActivity::class.java)) }
            })
        }

        // Maintenance (spec §50).
        container.addView(header(getString(R.string.env_manager_maintenance)))
        container.addView(Button(this).apply {
            text = getString(R.string.env_manager_clear_temp)
            setOnClickListener { clearTemporaryFiles() }
        })

        // Tasks (spec §55): all setup operations appear here.
        container.addView(header(getString(R.string.env_manager_tasks)))

        renderTasks()
    }

    private fun envRow(env: Environment): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        row.setPadding(dp(8), dp(6), 0, dp(6))
        row.addView(TextView(this).apply {
            text = when (env.status) {
                EnvironmentStatus.READY -> "⚪ ${env.displayName}"
                EnvironmentStatus.INSTALLING -> "⏳ ${env.displayName} — installing"
                EnvironmentStatus.RECOVERY_REQUIRED -> "⚠ ${env.displayName} — needs attention"
                else -> "· ${env.displayName}"
            }
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (env.status == EnvironmentStatus.READY) {
            actions.addView(smallButton(getString(R.string.env_switch)) { confirmSwitch(env) })
            actions.addView(smallButton(getString(R.string.env_open_terminal)) {
                NoxsService.start(this)
                startActivity(Intent(this, TerminalActivity::class.java))
            })
        }
        if (env.status in setOf(EnvironmentStatus.READY, EnvironmentStatus.RECOVERY_REQUIRED)) {
            actions.addView(smallButton(getString(R.string.env_remove)) { confirmRemove(env) })
        }
        row.addView(actions)
        return row
    }

    private fun renderTasks() {
        val app = application as com.crossberry.noxs.NoxsApplication
        val tasks = app.environments.taskManager.tasks.value
        // The tasks section re-renders inside render(); keep a marker view.
        val existing = container.findViewWithTag<TextView>("tasks_header") ?: return
        val parent = existing.parent as? LinearLayout ?: return
        val index = parent.indexOfChild(existing)
        // Remove previous task rows (everything after the header).
        while (parent.childCount > index + 1) parent.removeViewAt(parent.childCount - 1)
        tasks.take(5).forEach { task ->
            parent.addView(TextView(this).apply {
                text = when (task.state) {
                    SetupState.READY -> "✓ ${task.title} — ${getString(R.string.env_task_complete)}"
                    SetupState.FAILED -> "✗ ${task.title} — ${getString(R.string.env_task_failed)}"
                    else -> "⏳ ${task.title} — ${task.progress.takeIf { it >= 0 }?.toString() ?: "…"}%" +
                        " (${task.currentOperation})"
                }
                textSize = 13f
                setPadding(dp(8), dp(2), 0, dp(2))
            })
        }
    }

    private fun confirmSwitch(env: Environment) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.env_switch_title, env.displayName))
            .setMessage(getString(R.string.env_switch_message, env.displayName))
            .setPositiveButton(getString(R.string.env_switch)) { _, _ ->
                val app = application as com.crossberry.noxs.NoxsApplication
                if (app.environments.setActive(env.id)) {
                    NoxsService.restart(this)
                    Toast.makeText(this, getString(R.string.env_switched_to, env.displayName), Toast.LENGTH_SHORT).show()
                    render()
                } else {
                    Toast.makeText(this, R.string.env_switch_failed, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmRemove(env: Environment) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.env_remove_title, env.displayName))
            .setMessage(getString(R.string.env_remove_confirm, env.displayName))
            .setPositiveButton(getString(R.string.env_remove)) { _, _ ->
                val app = application as com.crossberry.noxs.NoxsApplication
                if (app.environments.remove(env.id)) {
                    Toast.makeText(this, getString(R.string.env_removed, env.displayName), Toast.LENGTH_SHORT).show()
                    render()
                } else {
                    Toast.makeText(this, R.string.env_remove_failed_running, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun clearTemporaryFiles() {
        val app = application as com.crossberry.noxs.NoxsApplication
        var freed = 0L
        runCatching {
            app.paths.cache.listFiles()?.forEach { freed += it.length(); it.deleteRecursively() }
            val envRoot = app.environments.environmentsRoot
            java.io.File(envRoot, "tasks").listFiles()?.forEach { it.delete() }
        }
        Toast.makeText(this, getString(R.string.env_cleared_temp, SetupFormat.bytes(freed)), Toast.LENGTH_SHORT).show()
    }

    private fun header(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 17f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(16), 0, dp(6))
        tag = when (text) {
            getString(R.string.env_manager_tasks) -> "tasks_header"
            else -> null
        }
    }

    private fun smallButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 12f
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, dp(8), 0) }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
