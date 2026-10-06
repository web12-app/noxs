/*
 * Noxs — original implementation.
 * Diagnostics: developer-facing log ring + capability snapshot. Normal users
 * never see stack traces here — only sanitized messages.
 */
package com.crossberry.noxs.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.crossberry.noxs.R
import com.crossberry.noxs.databinding.ActivityManagerBinding
import com.crossberry.noxs.runtime.NoxsDeviceCapabilities
import com.crossberry.noxs.runtime.NoxsDockerCompat
import com.crossberry.noxs.runtime.NoxsDockerRuntime
import com.crossberry.noxs.runtime.NoxsDockerProbe
import com.crossberry.noxs.runtime.NoxsDockerSetup
import com.crossberry.noxs.runtime.NoxsPaths
import com.crossberry.noxs.runtime.NoxsResources
import com.crossberry.noxs.runtime.OneShotExecutor
import com.crossberry.noxs.runtime.ProotLauncher
import com.crossberry.noxs.shared.NoxsLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityManagerBinding
    private lateinit var paths: NoxsPaths

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        paths = (application as com.crossberry.noxs.NoxsApplication).paths

        binding.screenTitle.text = getString(R.string.title_diagnostics)
        binding.btnAction.text = getString(R.string.diag_copy)
        binding.btnAction.setOnClickListener { copyLogs() }
        binding.inputBar.visibility = View.GONE

        val tv = TextView(this).apply {
            setTextColor(0xffe6e6e6.toInt())
            textSize = 11f
            setPadding(32, 16, 32, 32)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        binding.recycler.visibility = View.GONE
        (binding.screenEmpty.parent as? android.view.ViewGroup)?.addView(tv)
        binding.screenEmpty.visibility = View.GONE

        val caps = NoxsDeviceCapabilities.probe(this, paths)
        tv.text = buildString {
            appendLine("=== capabilities ===")
            caps.toDisplayLines().forEach { (k, v) -> appendLine("$k: $v") }
            caps.detail.forEach { (k, v) -> appendLine("$k: $v") }
            appendLine()
            appendLine("=== docker (live probe) ===")
            appendLine("probing real docker state inside Debian…")
            appendLine()
            appendLine("=== log ring ===")
            NoxsLog.snapshot().forEach { appendLine(it) }
        }

        // Real probe: docker --version + docker info inside the sandbox,
        // enriched with the persisted Noxs Docker mode (safe facts only).
        lifecycleScope.launch {
            val report = runCatching {
                val launcher = ProotLauncher(paths, NoxsResources(paths))
                NoxsDockerProbe.probe(OneShotExecutor(launcher))
            }.getOrElse {
                NoxsDockerProbe.Report(false, "", false, "probe failed: ${it.message}")
            }
            if (isFinishing || isDestroyed) return@launch
            val lines = NoxsDockerProbe.displayLines(report).toMutableList()
            runCatching {
                val stateFile = java.io.File(paths.rootfsNoxsRun, NoxsDockerSetup.STATE_FILE)
                if (stateFile.isFile) {
                    val persisted = NoxsDockerCompat.parseState(stateFile.readText())
                    persisted.state?.let { lines.add("Noxs Docker state: ${it.key} (${it.label})") }
                    if (persisted.mode.isNotBlank()) lines.add("Noxs Docker mode: ${persisted.mode}")
                    lines.add("Managed daemon process: " +
                        if (NoxsDockerRuntime.isDaemonProcessAlive) "alive" else "not running")
                }
            }
            if (lines.isNotEmpty()) lines.add("  /var/log/noxs/dockerd.log holds the real daemon log")
            tv.text = tv.text.toString().replace(
                "probing real docker state inside Debian…",
                lines.joinToString("\n"))
        }
    }

    private fun copyLogs() {
        val text = NoxsLog.snapshot().joinToString("\n")
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("noxs-logs", text))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }
}
