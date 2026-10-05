/*
 * Noxs — original implementation.
 * Activity Center screen: compact, real-time view of every tracked activity
 * (sessions, commands, services, code-server, Docker), with safe per-activity
 * stop, logs/details, and the global "Stop Noxs" action.
 */
package com.crossberry.noxs.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.crossberry.noxs.R
import com.crossberry.noxs.databinding.ActivityActivityCenterBinding
import com.crossberry.noxs.runtime.NoxsActivityFormat
import com.crossberry.noxs.runtime.NoxsActivityKind
import com.crossberry.noxs.runtime.NoxsActivityRecord
import com.crossberry.noxs.runtime.NoxsActivityStatus
import com.crossberry.noxs.runtime.NoxsService
import com.crossberry.noxs.shared.NoxsLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ActivityCenterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityActivityCenterBinding
    private lateinit var adapter: NoxsActivityAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityActivityCenterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = NoxsActivityAdapter.bind(binding.activityList, emptyList(), ::openDetails)
        binding.btnBack.setOnClickListener { finish() }
        binding.btnClearFinished.setOnClickListener {
            (application as com.crossberry.noxs.NoxsApplication).activityCenter.clearFinished()
        }
        binding.btnStopNoxs.setOnClickListener { confirmStopNoxs() }

        val center = (application as com.crossberry.noxs.NoxsApplication).activityCenter
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                center.records.collect { records -> render(records) }
            }
        }
    }

    private fun render(records: List<NoxsActivityRecord>) {
        val now = System.currentTimeMillis()
        val running = records.filter { it.status.isActive || it.status == NoxsActivityStatus.QUEUED }
        val completed = records.filter { it.status == NoxsActivityStatus.COMPLETED }
        val failed = records.filter { it.status == NoxsActivityStatus.FAILED }
        val stopped = records.filter { it.status == NoxsActivityStatus.STOPPED }

        val rows = buildList {
            if (running.isNotEmpty()) {
                add(ActivityRow.Header(getString(R.string.activity_section_running)))
                running.forEach { add(ActivityRow.Item(it, it.command, it.uptimeText(now))) }
            }
            if (completed.isNotEmpty()) {
                add(ActivityRow.Header(getString(R.string.activity_section_completed)))
                completed.forEach {
                    add(ActivityRow.Item(it, exitText(it), formatDuration(it)))
                }
            }
            if (failed.isNotEmpty()) {
                add(ActivityRow.Header(getString(R.string.activity_section_failed)))
                failed.forEach {
                    add(ActivityRow.Item(it, exitText(it), it.outputSummary.ifBlank { "—" }))
                }
            }
            if (stopped.isNotEmpty()) {
                add(ActivityRow.Header(getString(R.string.activity_section_stopped)))
                stopped.forEach { add(ActivityRow.Item(it, it.command, formatDuration(it))) }
            }
        }

        adapter.submit(rows)
        binding.emptyState.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        binding.activityList.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun exitText(record: NoxsActivityRecord): String =
        record.exitCode?.let { getString(R.string.activity_exit_code, it) } ?: "✓"

    private fun formatDuration(record: NoxsActivityRecord): String =
        NoxsActivityFormat.uptime(((record.finishedAt ?: System.currentTimeMillis()) - record.startedAt).coerceAtLeast(0L) / 1000L)

    // -------------------------------------------------------------- actions

    private fun openDetails(record: NoxsActivityRecord) {
        val now = System.currentTimeMillis()
        val isActive = !record.status.isTerminal
        val message = buildString {
            append(FloatingStatusPanel.statusText(record.status)).append('\n')
            append("Session: ").append(record.sessionId.ifBlank { "—" }).append('\n')
            append("Process: ").append(record.command.ifBlank { record.title }).append('\n')
            append("PID: ").append(record.pid?.toString() ?: "—").append('\n')
            append("Directory: ").append(record.workingDirectory.ifBlank { "—" }).append('\n')
            append("Uptime: ").append(record.uptimeText(now)).append('\n')
            record.exitCode?.let { append("Exit code: ").append(it).append('\n') }
            val summary = record.outputSummary
            if (summary.isNotBlank()) append("\nLast output:\n").append(summary)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(record.title)
            .setMessage(message)
            .setPositiveButton(R.string.action_cancel, null)
        dialog
            .setNegativeButton(R.string.activity_logs_title) { _, _ -> showLogs(record) }
        if (record.kind == NoxsActivityKind.SESSION || record.sessionId.isNotBlank()) {
            dialog.setNeutralButton(R.string.float_terminal) { _, _ -> openTerminal(record.activityId) }
        }
        if (isActive) {
            dialog.setNegativeButton(R.string.activity_stop) { _, _ -> confirmStopActivity(record) }
        }
        dialog.show()
    }

    private fun showLogs(record: NoxsActivityRecord) {
        val center = (application as com.crossberry.noxs.NoxsApplication).activityCenter
        val lines = center.recentOutput(record.activityId)
        val text = if (lines.isEmpty()) "No recent output." else lines.joinToString("\n")
        AlertDialog.Builder(this)
            .setTitle("${getString(R.string.activity_logs_title)} — ${record.title}")
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun confirmStopActivity(record: NoxsActivityRecord) {
        AlertDialog.Builder(this)
            .setTitle(R.string.activity_stop_confirm_title)
            .setMessage(getString(R.string.activity_stop_confirm_msg, record.title))
            .setPositiveButton(R.string.activity_stop) { _, _ ->
                NoxsLog.i("ActivityCenter", "user stop ${record.activityId}")
                lifecycleScope.launch(Dispatchers.IO) {
                    (application as com.crossberry.noxs.NoxsApplication).activityCenter.requestStop(record.activityId)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun confirmStopNoxs() {
        AlertDialog.Builder(this)
            .setTitle(R.string.stop_all_confirm_title)
            .setMessage(R.string.stop_all_confirm_msg)
            .setPositiveButton(R.string.stop_all_positive) { _, _ ->
                NoxsService.requestStopAll(this)
                finishAffinity()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun openTerminal(activityId: String) {
        startActivity(
            Intent(this, TerminalActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(NoxsService.EXTRA_FOCUS_ACTIVITY, activityId)
            }
        )
    }
}
