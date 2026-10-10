/*
 * Noxs — original implementation.
 * Settings redesign (spec §2-§9): a dark navy card interface — header with
 * back navigation and the Noxs brand, sections grouped into rounded cards
 * (General, Storage & Performance, Session, Bootstrap & Network, Linux
 * Environment, Terminal, Appearance), one uniform row anatomy built on
 * SettingsWidgets, and validated editors for numeric quotas, the optional
 * custom rootfs URL and guest DNS servers.
 *
 * Every control maps to the REAL backed preference it always did — the
 * "noxs_settings" SharedPreferences, terminal.* settings or
 * /etc/noxs/resources.conf. No key was renamed; values persist across
 * restarts exactly as before.
 */
package com.crossberry.noxs.ui

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.crossberry.noxs.R
import com.crossberry.noxs.databinding.ActivityStackBinding
import com.crossberry.noxs.runtime.NoxsPaths
import com.crossberry.noxs.runtime.NoxsResources
import java.io.File

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStackBinding
    private lateinit var paths: NoxsPaths

    // Icon badge tints (spec §4: colored containers, consistent family).
    private val tintGreen = 0xFF16D66A.toInt()
    private val tintBlue = 0xFF3D8BFF.toInt()
    private val tintPurple = 0xFF9B6BFF.toInt()
    private val tintOrange = 0xFFFF9A3D.toInt()
    private val tintTeal = 0xFF16C6D6.toInt()
    private val tintPink = 0xFFFF5B9D.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStackBinding.inflate(layoutInflater)
        setContentView(binding.root)
        paths = (application as com.crossberry.noxs.NoxsApplication).paths

        binding.screenTitle.text = getString(R.string.title_settings)
        binding.btnBack.setOnClickListener { finish() }

        // Edge-to-edge: never draw controls under the system bars (spec §8).
        ViewCompat.setOnApplyWindowInsetsListener(binding.settingsRoot) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        val prefs = getSharedPreferences("noxs_settings", Context.MODE_PRIVATE)
        val terminalPrefs = AndroidTerminalPrefs.from(this)
        val resources = NoxsResources(paths)
        val stack = binding.stack
        val terminal = TerminalSettingsStore.load(terminalPrefs)

        // ------------------------------------------------------ helpers

        fun addSection(title: String?, vararg rows: View) {
            title?.let { stack.addView(SettingsWidgets.sectionHeader(this, it)) }
            stack.addView(SettingsWidgets.card(this, *rows))
        }

        fun editNumberDialog(
            title: String,
            subtitle: String,
            current: String,
            min: Long,
            max: Long?,
            onValid: (Long) -> Unit
        ) {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(12), dp(24), 0)
            }
            val hint = TextView(this).apply {
                text = subtitle
                setTextColor(0xFFA5B1C2.toInt())
                textSize = 13f
            }
            val input = EditText(this).apply {
                setText(current)
                inputType = InputType.TYPE_CLASS_NUMBER
                setTextColor(0xFFF5F7FA.toInt())
                selectAll()
            }
            val error = TextView(this).apply {
                setTextColor(0xFFFF5B67.toInt())
                textSize = 13f
                visibility = View.GONE
                setPadding(0, dp(6), 0, 0)
            }
            box.addView(hint)
            box.addView(input)
            box.addView(error)
            AlertDialog.Builder(this)
                .setTitle(title)
                .setView(box)
                .setPositiveButton(R.string.action_apply, null)
                .setNegativeButton(android.R.string.cancel, null)
                .show()
                .also { dialog ->
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val raw = input.text.toString().trim()
                        val value = raw.toLongOrNull()
                        val valid = value != null && value >= min && (max == null || value <= max)
                        if (valid) {
                            onValid(value!!)
                            dialog.dismiss()
                        } else {
                            // Never silently clamp — state the allowed range (spec §6).
                            error.text = if (max == null) {
                                getString(R.string.settings_error_min_value, min)
                            } else {
                                getString(R.string.settings_error_range, min, max)
                            }
                            error.visibility = View.VISIBLE
                        }
                    }
                }
        }

        fun editUrlDialog(current: String, onValid: (String) -> Unit) {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(12), dp(24), 0)
            }
            val note = TextView(this).apply {
                text = getString(R.string.settings_url_note)
                setTextColor(0xFFA5B1C2.toInt())
                textSize = 13f
            }
            val input = EditText(this).apply {
                setText(current)
                inputType = InputType.TYPE_TEXT_VARIATION_URI
                setSingleLine(true)
                setTextColor(0xFFF5F7FA.toInt())
                hint = "https://example.com/rootfs.tar.gz"
            }
            val error = TextView(this).apply {
                setTextColor(0xFFFF5B67.toInt())
                textSize = 13f
                visibility = View.GONE
                setPadding(0, dp(6), 0, 0)
            }
            box.addView(note)
            box.addView(input)
            box.addView(error)
            AlertDialog.Builder(this)
                .setTitle(R.string.settings_edit_url)
                .setView(box)
                .setPositiveButton(R.string.action_apply, null)
                .setNegativeButton(android.R.string.cancel, null)
                .show()
                .also { dialog ->
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val url = input.text.toString().trim()
                        val uri = runCatching { Uri.parse(url) }.getOrNull()
                        val valid = url.isEmpty() ||
                            (uri != null && uri.scheme == "https" && !uri.host.isNullOrBlank())
                        if (valid) {
                            onValid(url)
                            dialog.dismiss()
                        } else {
                            error.setText(R.string.settings_error_url)
                            error.visibility = View.VISIBLE
                        }
                    }
                }
        }

        fun validIp(line: String): Boolean {
            val ipv4 = Regex(
                "^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$"
            )
            val ipv6 = Regex(
                "^([0-9A-Fa-f]{1,4}:){7}[0-9A-Fa-f]{1,4}$" +
                    "|^([0-9A-Fa-f]{1,4}:){1,7}:$" +
                    "|^([0-9A-Fa-f]{1,4}:){1,6}:[0-9A-Fa-f]{1,4}$" +
                    "|^([0-9A-Fa-f]{1,4}:){1,5}(:[0-9A-Fa-f]{1,4}){1,2}$" +
                    "|^([0-9A-Fa-f]{1,4}:){1,4}(:[0-9A-Fa-f]{1,4}){1,3}$" +
                    "|^([0-9A-Fa-f]{1,4}:){1,3}(:[0-9A-Fa-f]{1,4}){1,4}$" +
                    "|^([0-9A-Fa-f]{1,4}:){1,2}(:[0-9A-Fa-f]{1,4}){1,5}$" +
                    "|^[0-9A-Fa-f]{1,4}:((:[0-9A-Fa-f]{1,4}){1,6})$" +
                    "|^:((:[0-9A-Fa-f]{1,4}){1,7}|:)$"
            )
            return ipv4.matches(line) || ipv6.matches(line)
        }

        fun currentDnsServers(): List<String> =
            runCatching { File(paths.rootfs, "etc/resolv.conf").readText() }.getOrDefault("")
                .lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("nameserver ") }
                .map { it.removePrefix("nameserver ").trim() }
                .filter { it.isNotEmpty() }
                .toList()

        fun editDnsDialog(currentServers: List<String>, onValid: (List<String>) -> Unit) {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(12), dp(24), 0)
            }
            val note = TextView(this).apply {
                text = getString(R.string.settings_dns_note)
                setTextColor(0xFFA5B1C2.toInt())
                textSize = 13f
            }
            val input = EditText(this).apply {
                setText(currentServers.joinToString("\n"))
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 4
                gravity = Gravity.TOP
                setTextColor(0xFFF5F7FA.toInt())
                typeface = Typeface.MONOSPACE
                hint = getString(R.string.settings_dns_hint)
            }
            val error = TextView(this).apply {
                setTextColor(0xFFFF5B67.toInt())
                textSize = 13f
                visibility = View.GONE
                setPadding(0, dp(6), 0, 0)
            }
            box.addView(note)
            box.addView(input)
            box.addView(error)
            AlertDialog.Builder(this)
                .setTitle(R.string.settings_edit_dns)
                .setView(box)
                .setPositiveButton(R.string.action_apply, null)
                .setNegativeButton(android.R.string.cancel, null)
                .show()
                .also { dialog ->
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        // Blank lines and # comments are ignored (spec §7).
                        val servers = input.text.toString().lines()
                            .map { it.trim() }
                            .filter { it.isNotEmpty() && !it.startsWith("#") }
                        val invalid = servers.firstOrNull { !validIp(it) }
                        if (invalid != null) {
                            error.text = getString(R.string.settings_error_dns, invalid)
                            error.visibility = View.VISIBLE
                        } else {
                            // Empty input keeps the current configuration.
                            onValid(servers)
                            dialog.dismiss()
                        }
                    }
                }
        }

        // ------------------------------------------------------ GENERAL
        val quotas = resources.load()
        addSection(
            getString(R.string.settings_section_general),
            SettingsWidgets.switchRow(
                this, R.drawable.ic_settings_memory, tintGreen,
                getString(R.string.settings_header_stats_short),
                getString(R.string.settings_header_stats_short_desc),
                prefs.getBoolean("header_stats", true)
            ) { prefs.edit().putBoolean("header_stats", it).apply() },
            SettingsWidgets.switchRow(
                this, R.drawable.ic_settings_power, tintBlue,
                getString(R.string.settings_keep_awake),
                getString(R.string.settings_keep_awake_desc),
                prefs.getBoolean("keep_awake", true)
            ) {
                prefs.edit().putBoolean("keep_awake", it).apply()
                com.crossberry.noxs.runtime.RuntimeHolder.refreshKeepAwake()
            },
            SettingsWidgets.navRow(
                this, R.drawable.ic_settings_clock, tintPurple,
                getString(R.string.settings_max_sessions),
                getString(R.string.settings_max_sessions_desc),
                quotas.maxSessions.toString()
            ) {
                editNumberDialog(
                    getString(R.string.settings_max_sessions),
                    getString(R.string.settings_max_sessions_desc),
                    quotas.maxSessions.toString(), min = 1, max = 32
                ) { value ->
                    quotas.copy(maxSessions = value.toInt()).let { resources.save(it) }
                    recreate()
                }
            }
        )

        // ------------------------------------- STORAGE & PERFORMANCE
        addSection(
            getString(R.string.settings_section_storage),
            SettingsWidgets.navRow(
                this, R.drawable.ic_nav_processes, tintOrange,
                getString(R.string.settings_max_procs),
                getString(R.string.settings_max_procs_desc),
                quotas.maxProcessesPerSession.toString()
            ) {
                editNumberDialog(
                    getString(R.string.settings_max_procs),
                    getString(R.string.settings_max_procs_desc),
                    quotas.maxProcessesPerSession.toString(), min = 16, max = 4096
                ) { value ->
                    quotas.copy(maxProcessesPerSession = value.toInt()).let { resources.save(it) }
                    recreate()
                }
            },
            SettingsWidgets.navRow(
                this, R.drawable.ic_nav_storage, tintTeal,
                getString(R.string.settings_storage_warn),
                getString(R.string.settings_storage_warn_desc),
                quotas.storageWarnMb.toString()
            ) {
                editNumberDialog(
                    getString(R.string.settings_storage_warn),
                    getString(R.string.settings_storage_warn_desc),
                    quotas.storageWarnMb.toString(), min = 64, max = null
                ) { value ->
                    quotas.copy(storageWarnMb = value).let { resources.save(it) }
                    recreate()
                }
            }
        )

        // ---------------------------------------------------- SESSION
        addSection(
            getString(R.string.settings_section_session),
            SettingsWidgets.switchRow(
                this, R.drawable.ic_settings_power, tintPurple,
                getString(R.string.settings_confirm_exit),
                getString(R.string.settings_confirm_exit_desc),
                terminal.confirmExit
            ) { TerminalSettingsStore.putBoolean(terminalPrefs, "terminal.confirmExit", it) },
            SettingsWidgets.switchRow(
                this, R.drawable.ic_settings_restore, tintGreen,
                getString(R.string.settings_restore_sessions),
                getString(R.string.settings_restore_sessions_desc),
                terminal.restoreSessions
            ) { TerminalSettingsStore.putBoolean(terminalPrefs, "terminal.restoreSessions", it) },
            SettingsWidgets.switchRow(
                this, R.drawable.ic_settings_layers, tintBlue,
                getString(R.string.settings_keep_alive),
                getString(R.string.settings_keep_alive_desc),
                terminal.keepAlive
            ) { TerminalSettingsStore.putBoolean(terminalPrefs, "terminal.keepAlive", it) }
        )

        // ------------------------------------------ BOOTSTRAP & NETWORK
        val rootfsUrl = prefs.getString("rootfs_url", "") ?: ""
        val dnsServers = currentDnsServers()
        addSection(
            getString(R.string.settings_section_network),
            SettingsWidgets.navRow(
                this, R.drawable.ic_settings_public, tintPurple,
                getString(R.string.settings_rootfs_url),
                getString(R.string.settings_rootfs_url_desc),
                Uri.parse(rootfsUrl).host?.takeIf { it.isNotBlank() } ?: ""
            ) {
                editUrlDialog(rootfsUrl) { url ->
                    prefs.edit().putString("rootfs_url", url).apply()
                    Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
                    recreate()
                }
            },
            SettingsWidgets.navRow(
                this, R.drawable.ic_settings_public, tintGreen,
                getString(R.string.settings_dns),
                getString(R.string.settings_dns_desc),
                if (dnsServers.isEmpty()) "" else getString(R.string.settings_value_dns_servers, dnsServers.size)
            ) {
                editDnsDialog(dnsServers) { servers ->
                    if (servers.isNotEmpty()) {
                        runCatching {
                            File(paths.rootfs, "etc/resolv.conf").writeText(
                                servers.joinToString("\n") { "nameserver $it" } + "\n"
                            )
                        }
                        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
                    }
                    recreate()
                }
            }
        )

        // --------------------------------------------- LINUX ENVIRONMENT
        addSection(
            getString(R.string.settings_section_environment),
            SettingsWidgets.navRow(
                this, R.drawable.ic_nav_env, tintGreen,
                getString(R.string.settings_environment_title),
                getString(R.string.settings_environment_desc)
            ) { startActivity(Intent(this, EnvironmentManagerActivity::class.java)) },
            SettingsWidgets.navRow(
                this, R.drawable.ic_nav_security, tintBlue,
                getString(R.string.settings_permission_title),
                getString(R.string.settings_permission_desc)
            ) { startActivity(Intent(this, PermissionCenterActivity::class.java)) }
        )

        // ----------------------------------------------------- TERMINAL
        addSection(
            getString(R.string.settings_section_terminal),
            SettingsWidgets.navRow(
                this, R.drawable.ic_nav_terminal, tintPurple,
                getString(R.string.settings_terminal_title),
                getString(R.string.settings_terminal_entry_desc),
                "${terminal.fontSizeSp} sp · ${terminal.scrollMode.label}"
            ) { startActivity(Intent(this, TerminalSettingsActivity::class.java)) },
            SettingsWidgets.switchRow(
                this, R.drawable.ic_settings_keyboard, tintOrange,
                getString(R.string.settings_extra_keys),
                getString(R.string.settings_extra_keys_desc),
                prefs.getBoolean("extra_keys", true)
            ) { prefs.edit().putBoolean("extra_keys", it).apply() },
            SettingsWidgets.switchRow(
                this, R.drawable.ic_settings_volume, tintBlue,
                getString(R.string.settings_volume_keys),
                getString(R.string.settings_volume_keys_desc),
                prefs.getBoolean("volume_keys", true)
            ) { prefs.edit().putBoolean("volume_keys", it).apply() },
            SettingsWidgets.switchRow(
                this, R.drawable.ic_settings_bell, tintPurple,
                getString(R.string.settings_bell),
                getString(R.string.settings_bell_desc),
                prefs.getBoolean("bell", false)
            ) { prefs.edit().putBoolean("bell", it).apply() }
        )

        // --------------------------------------------------- APPEARANCE
        addSection(
            getString(R.string.settings_section_appearance),
            SettingsWidgets.navRow(
                this, R.drawable.ic_settings_palette, tintPink,
                getString(R.string.settings_appearance),
                getString(R.string.settings_appearance_desc),
                "${terminal.fontSizeSp} sp · ${terminal.theme.label}"
            ) {
                startActivity(
                    TerminalSettingsSubActivity.intent(this, TerminalSettingsSubActivity.SECTION_APPEARANCE)
                )
            },
            SettingsWidgets.switchRow(
                this, R.drawable.ic_nav_terminal, tintBlue,
                getString(R.string.settings_show_toolbar),
                getString(R.string.settings_show_toolbar_desc),
                terminal.showToolbar
            ) { TerminalSettingsStore.putBoolean(terminalPrefs, "terminal.showToolbar", it) },
            SettingsWidgets.infoRow(
                this, R.drawable.ic_settings_palette, tintTeal,
                getString(R.string.settings_dark_theme),
                getString(R.string.settings_dark_theme_desc),
                "✓"
            )
        )
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
