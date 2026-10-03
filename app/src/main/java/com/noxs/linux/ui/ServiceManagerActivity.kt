/*
 * Noxs — original implementation.
 * Service manager: noxs-service controls + code-server status (noxs code).
 */
package com.noxs.linux.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.noxs.linux.R
import com.noxs.linux.databinding.ActivityManagerBinding
import com.noxs.linux.runtime.NoxsPaths
import com.noxs.linux.runtime.NoxsResources
import com.noxs.linux.runtime.NoxsRuntimeFactory
import kotlinx.coroutines.launch

class ServiceManagerActivity : AppCompatActivity() {

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
        paths = (application as com.noxs.linux.NoxsApplication).paths

        binding.screenTitle.text = getString(R.string.title_service_manager)
        binding.btnAction.setOnClickListener { render() }
        binding.inputBar.visibility = View.GONE

        adapter = TwoLineAdapter.bind(binding.recycler, emptyList())
        render()
    }

    private fun render() {
        lifecycleScope.launch {
            val rows = mutableListOf<TwoLineRow>()

            // code-server integration (noxs code status)
            val code = exec.runShell("noxs code status 2>/dev/null", timeoutSec = 15)
            rows.add(
                TwoLineRow(
                    title = getString(R.string.svc_code_title),
                    subtitle = code.stdout.trim().ifBlank { "noxs CLI unavailable" },
                    onClick = { codeMenu() }
                )
            )

            // /etc/noxs/services definitions
            val statuses = NoxsRuntimeFactory.services(exec, paths).statuses()
            statuses.forEach { s ->
                rows.add(
                    TwoLineRow(
                        title = "${s.definition.name} — ${if (s.running) "running" else "stopped"}",
                        subtitle = s.definition.description,
                        onClick = { serviceMenu(s.definition.name) }
                    )
                )
            }

            adapter?.submit(rows)
            binding.screenEmpty.visibility = if (rows.size <= 1) View.VISIBLE else View.GONE
            binding.screenEmpty.text = getString(R.string.svc_none)
        }
    }

    private fun codeMenu() {
        val actions = arrayOf("start", "stop", "restart", "status")
        AlertDialog.Builder(this)
            .setTitle("noxs code …")
            .setItems(actions) { _, which ->
                lifecycleScope.launch {
                    val r = exec.runShell("noxs code ${actions[which]}", timeoutSec = 60)
                    Toast.makeText(this@ServiceManagerActivity, r.stdout.trim().ifBlank { "done" }, Toast.LENGTH_LONG).show()
                    render()
                }
            }
            .show()
    }

    private fun serviceMenu(name: String) {
        val actions = arrayOf("start", "stop", "restart")
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(actions) { _, which ->
                lifecycleScope.launch {
                    NoxsRuntimeFactory.services(exec, paths).control(actions[which], name)
                    render()
                }
            }
            .show()
    }
}
