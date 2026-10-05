/*
 * Noxs — original implementation.
 * Settings: terminal behavior, resource quotas, bootstrap URL override, DNS.
 * Backed by SharedPreferences + /etc/noxs/resources.conf + /etc/resolv.conf.
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
        val resources = NoxsResources(paths)
        val stack = binding.stack

        fun label(text: String): TextView = TextView(this).apply {
            this.text = text
            setTextColor(0xffe6e6e6.toInt())
            textSize = 13f
            setPadding(48, 28, 48, 6)
        }

        fun addRow(text: String, control: View) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(48, 8, 48, 8)
            }
            val tv = TextView(this).apply {
                this.text = text
                setTextColor(0xffe6e6e6.toInt())
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(tv)
            row.addView(control)
            stack.addView(row)
        }

        fun addInput(text: String, current: String, multiline: Boolean, onSave: (String) -> Unit) {
            stack.addView(label(text))
            val et = EditText(this).apply {
                setText(current)
                setSingleLine(!multiline)
                minLines = if (multiline) 2 else 1
                setTextColor(0xffe6e6e6.toInt())
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

        fun switch(initial: Boolean, onChange: (Boolean) -> Unit): SwitchMaterial =
            SwitchMaterial(this).apply { isChecked = initial; setOnCheckedChangeListener { _, c -> onChange(c) } }

        // Terminal hub (Appearance / Interaction / Scrolling / Behavior / Advanced)
        stack.addView(label(getString(R.string.settings_section_terminal)))
        stack.addView(SettingsWidgets.valueRow(
            this,
            getString(R.string.settings_terminal_title),
            getString(R.string.settings_terminal_entry_desc),
            TerminalSettingsStore.load(AndroidTerminalPrefs.from(this)).let { "${it.fontSizeSp} sp · ${it.scrollMode.label}" }
        ) { startActivity(android.content.Intent(this, TerminalSettingsActivity::class.java)) })

        // Terminal behavior
        addRow(
            getString(R.string.settings_extra_keys),
            switch(true) { prefs.edit().putBoolean("extra_keys", it).apply() }
        )
        addRow(
            getString(R.string.settings_volume_keys),
            switch(true) { prefs.edit().putBoolean("volume_keys", it).apply() }
        )
        addRow(
            getString(R.string.settings_bell),
            switch(false) { prefs.edit().putBoolean("bell", it).apply() }
        )

        // Quotas (resources.conf inside the sandbox)
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

        // Bootstrap / network
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
