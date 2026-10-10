/*
 * Noxs — original implementation.
 * Settings: organized sections (Linux Environment, Permissions, Terminal,
 * Appearance, Performance, Session, Bootstrap & Network). Every control maps
 * to a REAL backed setting — SharedPreferences keys, terminal.* settings or
 * /etc/noxs/resources.conf — so nothing presented here is fake.
 */
package com.crossberry.noxs.ui

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.crossberry.noxs.R
import com.crossberry.noxs.databinding.ActivityStackBinding
import com.crossberry.noxs.runtime.NoxsPaths
import com.crossberry.noxs.runtime.NoxsResources
import java.io.File

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStackBinding
    private lateinit var paths: NoxsPaths

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStackBinding.inflate(layoutInflater)
        setContentView(binding.root)
        paths = (application as com.crossberry.noxs.NoxsApplication).paths

        binding.screenTitle.text = getString(R.string.title_settings)

        val prefs = getSharedPreferences("noxs_settings", Context.MODE_PRIVATE)
        val terminalPrefs = AndroidTerminalPrefs.from(this)
        val resources = NoxsResources(paths)
        val stack = binding.stack

        fun section(text: String): TextView = TextView(this).apply {
            this.text = text
            setTextColor(0xffaaaaaa.toInt())
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(48, 32, 48, 8)
        }

        fun label(text: String): TextView = TextView(this).apply {
            this.text = text
            setTextColor(0xffffffff.toInt())
            textSize = 13f
            setPadding(48, 24, 48, 6)
        }

        fun addRow(text: String, control: View) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(48, 8, 48, 8)
            }
            val tv = TextView(this).apply {
                this.text = text
                setTextColor(0xffffffff.toInt())
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(tv)
            row.addView(control)
            stack.addView(row)
        }

        fun addSwitch(text: String, initial: Boolean, onChange: (Boolean) -> Unit) {
            addRow(text, SwitchMaterial(this).apply {
                isChecked = initial
                setOnCheckedChangeListener { _, c -> onChange(c) }
            })
        }

        fun addInput(text: String, current: String, multiline: Boolean, onSave: (String) -> Unit) {
            stack.addView(label(text))
            val et = EditText(this).apply {
                setText(current)
                setSingleLine(!multiline)
                minLines = if (multiline) 2 else 1
                setTextColor(0xffffffff.toInt())
            }
            stack.addView(et)
            stack.addView(MaterialButton(this).apply {
                this.text = getString(R.string.action_apply)
                setOnClickListener {
                    onSave(et.text.toString())
                    Toast.makeText(this@SettingsActivity, R.string.settings_saved, Toast.LENGTH_SHORT).show()
                }
            })
        }

        fun valueRow(title: String, description: String, value: String, onClick: () -> Unit) {
            stack.addView(SettingsWidgets.valueRow(this, title, description, value, onClick))
        }

        // ---- Linux Environment hub (spec §50) ----
        stack.addView(section(getString(R.string.settings_section_environment)))
        valueRow(
            getString(R.string.settings_environment_title),
            getString(R.string.settings_environment_desc),
            ""
        ) { startActivity(android.content.Intent(this, EnvironmentManagerActivity::class.java)) }

        // ---- Permission Center (Noxs API spec §9) ----
        stack.addView(section(getString(R.string.settings_section_permissions)))
        valueRow(
            getString(R.string.settings_permission_title),
            getString(R.string.settings_permission_desc),
            ""
        ) { startActivity(android.content.Intent(this, PermissionCenterActivity::class.java)) }

        // ---- Terminal ----
        val terminal = TerminalSettingsStore.load(terminalPrefs)
        stack.addView(section(getString(R.string.settings_section_terminal)))
        valueRow(
            getString(R.string.settings_terminal_title),
            getString(R.string.settings_terminal_entry_desc),
            "${terminal.fontSizeSp} sp · ${terminal.scrollMode.label}"
        ) { startActivity(android.content.Intent(this, TerminalSettingsActivity::class.java)) }
        addSwitch(getString(R.string.settings_extra_keys), prefs.getBoolean("extra_keys", true)) {
            prefs.edit().putBoolean("extra_keys", it).apply()
        }
        addSwitch(getString(R.string.settings_volume_keys), prefs.getBoolean("volume_keys", true)) {
            prefs.edit().putBoolean("volume_keys", it).apply()
        }
        addSwitch(getString(R.string.settings_bell), prefs.getBoolean("bell", false)) {
            prefs.edit().putBoolean("bell", it).apply()
        }

        // ---- Appearance ----
        stack.addView(section(getString(R.string.settings_section_appearance)))
        valueRow(
            getString(R.string.settings_appearance),
            getString(R.string.settings_appearance_desc),
            "${terminal.fontSizeSp} sp · ${terminal.theme.label}"
        ) {
            startActivity(
                TerminalSettingsSubActivity.intent(this, TerminalSettingsSubActivity.SECTION_APPEARANCE)
            )
        }
        addSwitch(getString(R.string.settings_show_toolbar), terminal.showToolbar) {
            TerminalSettingsStore.putBoolean(terminalPrefs, "terminal.showToolbar", it)
        }
        stack.addView(SettingsWidgets.infoRow(
            this,
            getString(R.string.settings_dark_theme),
            getString(R.string.settings_dark_theme_desc),
            "✓"
        ))

        // ---- Performance ----
        stack.addView(section(getString(R.string.settings_section_performance)))
        addSwitch(
            getString(R.string.settings_header_stats),
            prefs.getBoolean("header_stats", true)
        ) {
            prefs.edit().putBoolean("header_stats", it).apply()
        }
        addSwitch(
            getString(R.string.settings_keep_awake),
            prefs.getBoolean("keep_awake", true)
        ) {
            prefs.edit().putBoolean("keep_awake", it).apply()
            com.crossberry.noxs.runtime.RuntimeHolder.refreshKeepAwake()
        }
        val q = resources.load()
        addInput(getString(R.string.settings_max_sessions), q.maxSessions.toString(), false) {
            resources.save(q.copy(maxSessions = it.toIntOrNull()?.coerceIn(1, 32) ?: q.maxSessions))
        }
        addInput(getString(R.string.settings_max_procs), q.maxProcessesPerSession.toString(), false) {
            resources.save(q.copy(maxProcessesPerSession = it.toIntOrNull()?.coerceIn(16, 4096) ?: q.maxProcessesPerSession))
        }
        addInput(getString(R.string.settings_storage_warn), q.storageWarnMb.toString(), false) {
            resources.save(q.copy(storageWarnMb = it.toLongOrNull()?.coerceAtLeast(64) ?: q.storageWarnMb))
        }

        // ---- Session ----
        stack.addView(section(getString(R.string.settings_section_session)))
        addSwitch(getString(R.string.settings_confirm_exit), terminal.confirmExit) {
            TerminalSettingsStore.putBoolean(terminalPrefs, "terminal.confirmExit", it)
        }
        addSwitch(getString(R.string.settings_restore_sessions), terminal.restoreSessions) {
            TerminalSettingsStore.putBoolean(terminalPrefs, "terminal.restoreSessions", it)
        }
        addSwitch(getString(R.string.settings_keep_alive), terminal.keepAlive) {
            TerminalSettingsStore.putBoolean(terminalPrefs, "terminal.keepAlive", it)
        }

        // ---- Bootstrap & network ----
        stack.addView(section(getString(R.string.settings_section_network)))
        addInput(getString(R.string.settings_rootfs_url), prefs.getString("rootfs_url", "") ?: "", false) {
            prefs.edit().putString("rootfs_url", it.trim()).apply()
        }
        val dns = runCatching { File(paths.rootfs, "etc/resolv.conf").readText() }.getOrDefault("")
        addInput(getString(R.string.settings_dns), dns, true) { text ->
            val cleaned = text.lines().filter { it.startsWith("nameserver ") }.joinToString("\n")
            if (cleaned.isNotEmpty()) {
                runCatching { File(paths.rootfs, "etc/resolv.conf").writeText(cleaned + "\n") }
            }
        }
    }
}
