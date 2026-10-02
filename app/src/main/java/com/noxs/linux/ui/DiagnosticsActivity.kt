/*
 * Noxs — original implementation.
 * Diagnostics: developer-facing log ring + capability snapshot. Normal users
 * never see stack traces here — only sanitized messages.
 */
package com.noxs.linux.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.noxs.linux.R
import com.noxs.linux.databinding.ActivityManagerBinding
import com.noxs.linux.runtime.NoxsDeviceCapabilities
import com.noxs.linux.runtime.NoxsPaths
import com.noxs.linux.shared.NoxsLog

class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityManagerBinding
    private lateinit var paths: NoxsPaths

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        paths = (application as com.noxs.linux.NoxsApplication).paths

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
            appendLine("=== log ring ===")
            NoxsLog.snapshot().forEach { appendLine(it) }
        }
    }

    private fun copyLogs() {
        val text = NoxsLog.snapshot().joinToString("\n")
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("noxs-logs", text))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }
}
