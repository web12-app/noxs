/*
 * Noxs — original implementation.
 * Package manager screen: search, installed list, apt update; install runs in
 * a terminal session (with sudo) so the user sees and controls everything.
 */
package com.noxs.linux.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.noxs.linux.R
import com.noxs.linux.databinding.ActivityManagerBinding
import com.noxs.linux.runtime.NoxsPaths
import com.noxs.linux.runtime.NoxsResources
import com.noxs.linux.runtime.NoxsRuntimeFactory
import com.noxs.linux.runtime.RuntimeHolder
import kotlinx.coroutines.launch

class PackageManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityManagerBinding
    private lateinit var paths: NoxsPaths
    private var adapter: TwoLineAdapter? = null

    private val exec by lazy {
        val resources = NoxsResources(paths)
        NoxsRuntimeFactory.executor(NoxsRuntimeFactory.launcher(paths, resources))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        paths = (application as com.noxs.linux.NoxsApplication).paths

        binding.screenTitle.text = getString(R.string.title_package_manager)
        binding.screenHint.visibility = View.VISIBLE
        binding.screenHint.text = getString(R.string.pkg_install_hint)
        binding.inputBar.visibility = View.VISIBLE
        binding.etInput.hint = getString(R.string.pkg_search_hint)
        binding.btnInputAction.text = getString(R.string.action_search)
        binding.btnAction.text = getString(R.string.pkg_update_lists)

        adapter = TwoLineAdapter.bind(binding.recycler, emptyList())

        binding.btnInputAction.setOnClickListener {
            val term = binding.etInput.text?.toString().orEmpty()
            if (term.isBlank()) return@setOnClickListener
            lifecycleScope.launch {
                val results = exec.let { NoxsRuntimeFactory.packages(it).search(term) }
                adapter?.submit(results.map {
                    TwoLineRow(
                        title = it.name,
                        subtitle = it.section,
                        onClick = { confirmInstall(it.name) }
                    )
                })
                binding.screenEmpty.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
            }
        }

        binding.btnAction.setOnClickListener {
            lifecycleScope.launch {
                Toast.makeText(this@PackageManagerActivity, "apt update…", Toast.LENGTH_SHORT).show()
                val r = NoxsRuntimeFactory.packages(exec).update()
                Toast.makeText(
                    this@PackageManagerActivity,
                    if (r.success) "done" else getString(R.string.err_package_install),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        lifecycleScope.launch { loadInstalled() }
    }

    private suspend fun loadInstalled() {
        val pkgs = NoxsRuntimeFactory.packages(exec).installed()
        adapter?.submit(pkgs.map {
            TwoLineRow(title = it.name, subtitle = "${it.version} · ${it.section}")
        })
        binding.screenEmpty.visibility = if (pkgs.isEmpty()) View.VISIBLE else View.GONE
        binding.screenEmpty.text = getString(R.string.err_not_installed)
    }

    private fun confirmInstall(pkg: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.action_install))
            .setMessage("sudo apt install -y $pkg\n\nRuns in a new terminal session.")
            .setPositiveButton(R.string.action_install) { _, _ ->
                val cmd = NoxsRuntimeFactory.packages(exec).installCommand(pkg)
                // Open a session and type the command for the user to confirm.
                val mgr = RuntimeHolder.sessions
                if (mgr == null) {
                    Toast.makeText(this, R.string.err_not_installed, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                mgr.createSession("install-$pkg")
                    .onSuccess { entry ->
                        entry.session.write((cmd + "\n").toByteArray())
                        startActivity(Intent(this, TerminalActivity::class.java))
                    }
                    .onFailure {
                        Toast.makeText(this, it.message, Toast.LENGTH_LONG).show()
                    }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
}
