/*
 * Noxs — original implementation.
 * Environment variables editor for /etc/environment (new sessions only).
 */
package com.crossberry.noxs.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.crossberry.noxs.R
import com.crossberry.noxs.databinding.ActivityManagerBinding
import com.crossberry.noxs.runtime.NoxsPaths
import com.crossberry.noxs.runtime.NoxsRuntimeFactory

class EnvVarsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityManagerBinding
    private lateinit var paths: NoxsPaths
    private var adapter: TwoLineAdapter? = null

    private val env by lazy { NoxsRuntimeFactory.env(paths) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        paths = (application as com.crossberry.noxs.NoxsApplication).paths

        binding.screenTitle.text = getString(R.string.title_env_vars)
        binding.screenHint.visibility = View.VISIBLE
        binding.screenHint.text = getString(R.string.env_scope)
        binding.inputBar.visibility = View.VISIBLE
        binding.etInput.hint = "KEY=value"
        binding.btnInputAction.text = getString(R.string.env_add)
        binding.btnAction.visibility = View.VISIBLE
        binding.btnAction.text = getString(R.string.action_refresh)

        adapter = TwoLineAdapter.bind(binding.recycler, emptyList())

        binding.btnInputAction.setOnClickListener {
            val kv = binding.etInput.text?.toString()?.trim().orEmpty()
            val idx = kv.indexOf('=')
            if (idx <= 0) return@setOnClickListener
            val ok = env.setGlobal(kv.substring(0, idx), kv.substring(idx + 1))
            Toast.makeText(this, if (ok) R.string.settings_saved else R.string.err_generic, Toast.LENGTH_SHORT).show()
            if (ok) { binding.etInput.setText(""); render() }
        }

        binding.btnAction.setOnClickListener { render() }
        render()
    }

    private fun render() {
        val map = env.global()
        adapter?.submit(map.map { (k, v) ->
            TwoLineRow(title = k, subtitle = v) {
                AlertDialog.Builder(this)
                    .setTitle(k)
                    .setMessage(v)
                    .setPositiveButton(R.string.action_delete) { _, _ ->
                        // remove by setting empty then filtering via re-write
                        val file = java.io.File(paths.rootfsEtc, "environment")
                        val kept = file.readLines().filter { !it.startsWith("$k=") }
                        file.writeText(kept.joinToString("\n") + "\n")
                        render()
                    }
                    .setNegativeButton(R.string.action_cancel, null)
                    .show()
            }
        })
        binding.screenEmpty.visibility = if (map.isEmpty()) View.VISIBLE else View.GONE
        binding.screenEmpty.text = getString(R.string.err_not_installed)
    }
}
