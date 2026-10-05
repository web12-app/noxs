/*
 * Noxs — original implementation.
 * Process manager: Android sessions + Debian processes (ps inside the sandbox).
 */
package com.crossberry.noxs.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.crossberry.noxs.R
import com.crossberry.noxs.databinding.ActivityManagerBinding
import com.crossberry.noxs.runtime.NoxsPaths
import com.crossberry.noxs.runtime.NoxsResources
import com.crossberry.noxs.runtime.NoxsRuntimeFactory
import com.crossberry.noxs.runtime.RuntimeHolder
import kotlinx.coroutines.launch

class ProcessManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityManagerBinding
    private lateinit var paths: NoxsPaths
    private var adapter: TwoLineAdapter? = null

    private val exec by lazy {
        NoxsRuntimeFactory.executor(NoxsRuntimeFactory.launcher(paths, NoxsResources(paths)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        paths = (application as com.crossberry.noxs.NoxsApplication).paths

        binding.screenTitle.text = getString(R.string.title_process_manager)
        binding.btnAction.setOnClickListener { render() }
        binding.inputBar.visibility = View.GONE

        adapter = TwoLineAdapter.bind(binding.recycler, emptyList())
        render()
    }

    private fun render() {
        lifecycleScope.launch {
            val rows = mutableListOf<TwoLineRow>()

            // Android-side sessions
            RuntimeHolder.sessions?.sessions?.value?.forEach { entry ->
                rows.add(
                    TwoLineRow(
                        title = "▣ ${entry.label} (host pid ${entry.session.pid})",
                        subtitle = "Noxs terminal session · ${entry.session.emulator.buffer.rows}×${entry.session.emulator.buffer.cols}" +
                            " · pty=${entry.session.isPty}",
                        onClick = {
                            AlertDialog.Builder(this@ProcessManagerActivity)
                                .setTitle(R.string.action_close)
                                .setMessage(entry.label)
                                .setPositiveButton(R.string.action_confirm) { _, _ ->
                                    RuntimeHolder.sessions?.closeSession(entry)
                                    render()
                                }
                                .setNegativeButton(R.string.action_cancel, null)
                                .show()
                        }
                    )
                )
            }

            // Debian-side processes
            val procs = NoxsRuntimeFactory.processes(exec).list()
            procs.forEach { p ->
                rows.add(
                    TwoLineRow(
                        title = "${p.pid} ${p.user} ${p.command}",
                        subtitle = "cpu ${p.cpu}% · mem ${p.mem}% · ${p.state} · up ${p.elapsed}",
                        onClick = {
                            AlertDialog.Builder(this@ProcessManagerActivity)
                                .setTitle(getString(R.string.proc_kill))
                                .setMessage(getString(R.string.proc_kill_confirm, p.pid))
                                .setPositiveButton(R.string.action_confirm) { _, _ ->
                                    lifecycleScope.launch {
                                        val ok = NoxsRuntimeFactory.processes(exec).kill(p.pid)
                                        if (!ok) Toast.makeText(
                                            this@ProcessManagerActivity, R.string.err_permission_denied,
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        render()
                                    }
                                }
                                .setNegativeButton(R.string.action_cancel, null)
                                .show()
                        }
                    )
                )
            }

            adapter?.submit(rows)
            binding.screenEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            binding.screenEmpty.text = getString(R.string.err_not_installed)
        }
    }
}
