/*
 * Noxs — original implementation.
 * PluginDetailsActivity: full metadata page for one plugin — logo, name,
 * version, description, author, license, category, permissions, commands,
 * minimum Noxs version, repository, the rendered README.md and the primary
 * action (Install / Update / Open / Enable / Disable / Uninstall).
 *
 * README rendering is hardened twice: PluginReadmeRenderer escapes and
 * sanitizes the markdown, and the WebView that shows the result runs with
 * JavaScript disabled.
 */
package com.crossberry.noxs.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.crossberry.noxs.R
import com.crossberry.noxs.runtime.NoxsUrlGuard
import com.crossberry.noxs.runtime.plugins.InstalledPlugin
import com.crossberry.noxs.runtime.plugins.NoxsPluginManager
import com.crossberry.noxs.runtime.plugins.NoxsPluginRuntime
import com.crossberry.noxs.runtime.plugins.PluginHttp
import com.crossberry.noxs.runtime.plugins.PluginReadmeRenderer
import com.crossberry.noxs.runtime.plugins.RegistryEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PluginDetailsActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var manager: NoxsPluginManager? = null
    private var pluginId: String = ""

    private lateinit var metaColumn: LinearLayout
    private lateinit var logoView: ImageView
    private lateinit var actionButton: Button
    private lateinit var secondaryButton: Button
    private lateinit var dangerButton: Button
    private lateinit var permissionsBox: LinearLayout
    private lateinit var readmeView: WebView
    private var current: InstalledPlugin? = null
    private var entry: RegistryEntry? = null
    private var busy = false

    private val dp: Float
        get() = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, resources.displayMetrics)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pluginId = intent?.getStringExtra(EXTRA_PLUGIN_ID).orEmpty()
        if (pluginId.isBlank()) {
            finish()
            return
        }
        manager = runCatching {
            val app = application as com.crossberry.noxs.NoxsApplication
            val paths = app.environments.activePaths()
            NoxsPluginManager(
                rootfsHome = paths.rootfsHomeNoxs,
                cacheDir = app.cacheDir,
                appVersion = runCatching {
                    packageManager.getPackageInfo(packageName, 0).versionName
                }.getOrNull() ?: "0.0.0",
                guestBinDir = java.io.File(paths.rootfs, "usr/local/bin")
            )
        }.getOrNull()
        buildUi()
        load()
    }

    private fun load() {
        scope.launch(Dispatchers.IO) {
            val entryLoaded = manager?.catalog(refresh = true)?.firstOrNull { it.id == pluginId }
            val installedLoaded = manager?.installed(pluginId)
            scope.launch {
                entry = entryLoaded
                current = installedLoaded
                renderMeta()
                loadReadme(entryLoaded, installedLoaded)
            }
        }
    }

    // ------------------------------------------------------------------ UI

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0B1016.toInt())
        }
        setContentView(root)

        // Header: back + title.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
        }
        header.addView(TextView(this).apply {
            text = getString(R.string.plugin_details_back)
            setTextColor(0xFF7CE8B8.toInt())
            textSize = 14f
            setPadding(0, 0, (12 * dp).toInt(), 0)
            setOnClickListener { finish() }
        })
        header.addView(TextView(this).apply {
            text = getString(R.string.plugin_details_title)
            setTextColor(0xFFE6EDF3.toInt())
            textSize = 17f
        })
        root.addView(header)

        val scroll = androidx.core.widget.NestedScrollView(this)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
        }
        scroll.addView(content)

        // Logo + name + version block.
        metaColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        logoView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams((84 * dp).toInt(), (84 * dp).toInt())
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = GradientDrawable().apply {
                cornerRadius = 18f * dp
                setColor(0xFF121922.toInt())
                setStroke((1 * dp).toInt(), 0xFF22303C.toInt())
            }
            setPadding((10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt())
        }
        metaColumn.addView(logoView)
        content.addView(metaColumn)

        // Primary / secondary / danger actions.
        actionButton = Button(this).apply {
            isAllCaps = false
            textSize = 14f
        }
        actionButton.setOnClickListener { primaryAction() }
        content.addView(actionButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, (10 * dp).toInt(), 0, (4 * dp).toInt()) })

        secondaryButton = Button(this).apply {
            isAllCaps = false
            textSize = 14f
            visibility = TextView.GONE
        }
        secondaryButton.setOnClickListener { secondaryAction() }
        content.addView(secondaryButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, (4 * dp).toInt()) })

        dangerButton = Button(this).apply {
            isAllCaps = false
            textSize = 14f
            setTextColor(0xFFF28B82.toInt())
            visibility = TextView.GONE
        }
        dangerButton.setOnClickListener { confirmUninstall() }
        content.addView(dangerButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // Permissions section.
        permissionsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 12f * dp
                setColor(0xFF121922.toInt())
                setStroke((1 * dp).toInt(), 0xFF22303C.toInt())
            }
        }
        content.addView(permissionsBox, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, (12 * dp).toInt(), 0, 0) })

        // README (JS disabled, sanitized HTML).
        content.addView(sectionLabel(getString(R.string.plugin_details_readme)))
        readmeView = WebView(this).apply {
            settings.javaScriptEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            setBackgroundColor(0xFF121922.toInt())
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    // README links open in the Noxs browser (http/https only).
                    val url = request.url.toString()
                    when (val decision = NoxsUrlGuard.check(url)) {
                        is NoxsUrlGuard.Decision.Allowed -> startActivity(
                            Intent(this@PluginDetailsActivity, WebWindowActivity::class.java)
                                .putExtra(WebWindowActivity.EXTRA_URL, decision.url)
                        )
                        else -> Unit
                    }
                    return true
                }
            }
        }
        content.addView(readmeView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (420 * dp).toInt()
        ).apply { setMargins(0, (6 * dp).toInt(), 0, 0) })
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(0xFFE6EDF3.toInt())
        textSize = 15f
        setPadding(0, (14 * dp).toInt(), 0, (2 * dp).toInt())
    }

    private fun metaLine(label: String, value: String?): TextView = TextView(this).apply {
        text = "$label  ${value?.takeIf { it.isNotBlank() } ?: "-"}"
        setTextColor(0xFF9AA7B4.toInt())
        textSize = 13f
        setPadding(0, (2 * dp).toInt(), 0, (2 * dp).toInt())
    }

    // -------------------------------------------------------------- render

    private fun renderMeta() {
        val e = entry
        metaColumn.removeAllViews()
        metaColumn.addView(logoView)
        when {
            e != null -> {
                metaColumn.addView(TextView(this).apply {
                    text = e.name
                    setTextColor(0xFFE6EDF3.toInt())
                    textSize = 19f
                    gravity = Gravity.CENTER
                    setPadding(0, (8 * dp).toInt(), 0, 0)
                })
                metaColumn.addView(TextView(this).apply {
                    text = "v${e.version}"
                    setTextColor(0xFF6B7A88.toInt())
                    textSize = 13f
                    gravity = Gravity.CENTER
                })
                metaColumn.addView(TextView(this).apply {
                    text = e.description
                    setTextColor(0xFF9AA7B4.toInt())
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setPadding(0, (4 * dp).toInt(), 0, 0)
                })
                metaColumn.addView(metaLine(getString(R.string.plugin_details_author), e.author))
                metaColumn.addView(metaLine(getString(R.string.plugin_details_license), e.license))
                metaColumn.addView(metaLine(getString(R.string.plugin_details_category), e.category))
                metaColumn.addView(
                    metaLine(
                        getString(R.string.plugin_details_minimum),
                        e.minimumNoxsVersion
                    )
                )
                metaColumn.addView(metaLine(getString(R.string.plugin_details_repository), e.repository))
                renderPermissions(e.permissions)
                renderCommands()
                renderActions(e)
            }
            current != null -> {
                val meta = current!!.meta
                metaColumn.addView(TextView(this).apply {
                    text = meta.name
                    setTextColor(0xFFE6EDF3.toInt())
                    textSize = 19f
                    setPadding(0, (8 * dp).toInt(), 0, 0)
                })
                metaColumn.addView(TextView(this).apply {
                    text = "v${meta.version}"
                    setTextColor(0xFF6B7A88.toInt())
                    textSize = 13f
                })
                metaColumn.addView(TextView(this).apply {
                    text = meta.description
                    setTextColor(0xFF9AA7B4.toInt())
                    textSize = 13f
                    setPadding(0, (4 * dp).toInt(), 0, 0)
                })
                renderPermissions(meta.permissions)
                renderCommands()
                renderActions(null)
            }
            else -> {
                metaColumn.addView(TextView(this).apply {
                    text = getString(R.string.plugin_store_no_results)
                    setTextColor(0xFF8FA0AF.toInt())
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setPadding(0, (16 * dp).toInt(), 0, 0)
                })
                actionButton.visibility = TextView.GONE
                secondaryButton.visibility = TextView.GONE
                dangerButton.visibility = TextView.GONE
            }
        }
    }

    private fun renderPermissions(permissions: List<String>) {
        permissionsBox.removeAllViews()
        permissionsBox.addView(TextView(this).apply {
            text = getString(R.string.plugin_details_permissions)
            setTextColor(0xFFE6EDF3.toInt())
            textSize = 14f
        })
        if (permissions.isEmpty()) {
            permissionsBox.addView(TextView(this).apply {
                text = getString(R.string.plugin_details_no_permissions)
                setTextColor(0xFF6B7A88.toInt())
                textSize = 12f
            })
            return
        }
        permissions.forEach { permission ->
            permissionsBox.addView(TextView(this).apply {
                text = getString(R.string.plugin_details_permission_row, permission)
                setTextColor(0xFF7CE8B8.toInt())
                textSize = 13f
                setPadding(0, (3 * dp).toInt(), 0, (3 * dp).toInt())
            })
        }
    }

    private fun renderCommands() {
        val commands = entry?.commands ?: current?.meta?.commands ?: emptyList()
        if (commands.isEmpty()) return
        permissionsBox.addView(TextView(this).apply {
            text = getString(R.string.plugin_details_commands, commands.joinToString(", "))
            setTextColor(0xFF9AA7B4.toInt())
            textSize = 12f
            setPadding(0, (8 * dp).toInt(), 0, 0)
        })
    }

    private fun renderActions(e: RegistryEntry?) {
        val local = current
        when {
            busy -> {
                actionButton.text = getString(R.string.plugin_action_working)
                actionButton.isEnabled = false
                secondaryButton.visibility = TextView.GONE
                dangerButton.visibility = TextView.GONE
            }
            local == null -> {
                actionButton.text = getString(R.string.plugin_action_install)
                actionButton.isEnabled = e?.artifact != null
                secondaryButton.visibility = TextView.GONE
                dangerButton.visibility = TextView.GONE
            }
            else -> {
                val update = e != null && manager?.installer?.updateAvailable(e, local) == true
                if (update) {
                    actionButton.text = getString(R.string.plugin_action_update)
                    actionButton.isEnabled = true
                } else {
                    actionButton.text = getString(R.string.plugin_action_open)
                    actionButton.isEnabled = true
                }
                secondaryButton.text =
                    getString(if (local.enabled) R.string.plugin_action_disable else R.string.plugin_action_enable)
                secondaryButton.visibility = TextView.VISIBLE
                dangerButton.text = getString(R.string.plugin_action_uninstall)
                dangerButton.visibility = TextView.VISIBLE
            }
        }
    }

    // ------------------------------------------------------------- actions

    private fun primaryAction() {
        val e = entry
        val local = current
        val mgr = manager ?: return
        when {
            busy -> return
            local == null && e != null -> install(mgr)
            local != null && e != null && mgr.installer.updateAvailable(e, local) -> install(mgr)
            local != null -> {
                NoxsPluginRuntime.activate(applicationContext, local)
                Toast.makeText(this, getString(R.string.plugin_opened_toast), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun secondaryAction() {
        val local = current ?: return
        val mgr = manager ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                if (local.enabled) mgr.disable(pluginId) else mgr.enable(pluginId)
            }
            scope.launch { load() }
        }
    }

    private fun confirmUninstall() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.plugin_uninstall_title, current?.meta?.name ?: pluginId))
            .setMessage(getString(R.string.plugin_uninstall_message))
            .setPositiveButton(R.string.plugin_action_uninstall) { _, _ -> uninstall() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun uninstall() {
        val mgr = manager ?: return
        busy = true
        renderActions(entry)
        scope.launch(Dispatchers.IO) {
            runCatching { mgr.uninstall(pluginId) }
            scope.launch {
                busy = false
                NoxsPluginRuntime.deactivate(pluginId)
                load()
            }
        }
    }

    private fun install(mgr: NoxsPluginManager) {
        busy = true
        renderActions(entry)
        scope.launch(Dispatchers.IO) {
            val outcome = runCatching { mgr.install(pluginId) { /* progress */ } }
            scope.launch {
                busy = false
                outcome
                    .onSuccess { installed ->
                        NoxsPluginRuntime.activate(applicationContext, installed)
                        Toast.makeText(
                            this@PluginDetailsActivity,
                            getString(R.string.plugin_installed_toast, installed.meta.name),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    .onFailure {
                        Toast.makeText(
                            this@PluginDetailsActivity,
                            getString(R.string.plugin_install_failed_toast),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                load()
            }
        }
    }

    private fun loadReadme(e: RegistryEntry?, local: InstalledPlugin?) {
        scope.launch(Dispatchers.IO) {
            val markdown: String? = when {
                local != null && e == null ->
                    runCatching {
                        java.io.File(local.dir, local.meta.readme ?: "README.md")
                            .takeIf { it.isFile }?.readText(Charsets.UTF_8)
                    }.getOrNull()
                else -> e?.readmeUrl?.takeIf { it.startsWith("https://") }?.let { url ->
                    runCatching {
                        String(PluginHttp.get(url, 12_000, 2L * 1024L * 1024L), Charsets.UTF_8)
                    }.getOrNull()
                }
            }
            val html = markdown?.let { PluginReadmeRenderer.render(it) }
            scope.launch {
                if (html.isNullOrBlank()) {
                    readmeView.loadData(
                        "<html><body style='color:#8FA0AF;background:#121922;font-family:sans-serif;padding:16px'>${getString(R.string.plugin_details_readme_missing)}</body></html>",
                        "text/html", "utf-8"
                    )
                } else {
                    readmeView.loadDataWithBaseURL(
                        null,
                        readmeShell(html),
                        "text/html", "utf-8", null
                    )
                }
            }
        }
    }

    private fun readmeShell(body: String): String = """
        |<!DOCTYPE html><html><head><meta charset="utf-8">
        |<meta name="viewport" content="width=device-width, initial-scale=1">
        |<style>
        |  body { background:#121922; color:#D5DEE7; font-family:sans-serif;
        |         padding:14px; font-size:14px; line-height:1.5; }
        |  h1,h2,h3 { color:#7CE8B8; }
        |  pre { background:#0B1016; border:1px solid #22303C; padding:10px;
        |        overflow-x:auto; border-radius:6px; }
        |  code { font-family:monospace; color:#9FE8C8; }
        |  table { border-collapse:collapse; }
        |  th,td { border:1px solid #22303C; padding:5px 9px; }
        |  a { color:#6CB6FF; }
        |</style></head><body>$body</body></html>
    """.trimMargin()

    override fun onDestroy() {
        scope.cancel()
        if (this::readmeView.isInitialized) {
            readmeView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PLUGIN_ID = "noxs.extra.PLUGIN_ID"
    }
}
