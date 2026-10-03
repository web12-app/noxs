/*
 * Noxs — original implementation.
 * Security/sandbox information screen — honest capability reporting.
 */
package com.noxs.linux.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.noxs.linux.R
import com.noxs.linux.databinding.ActivityManagerBinding
import com.noxs.linux.runtime.NoxsDeviceCapabilities
import com.noxs.linux.runtime.NoxsPaths
import com.noxs.linux.shared.NoxsConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SecurityActivity : AppCompatActivity() {

    private lateinit var binding: ActivityManagerBinding
    private lateinit var paths: NoxsPaths
    private var adapter: TwoLineAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        paths = (application as com.noxs.linux.NoxsApplication).paths

        binding.screenTitle.text = getString(R.string.title_security)
        binding.screenHint.visibility = View.VISIBLE
        binding.screenHint.text = getString(R.string.security_notice)
        binding.btnAction.visibility = View.VISIBLE
        binding.btnAction.text = getString(R.string.action_refresh)
        binding.btnAction.setOnClickListener { render() }
        binding.inputBar.visibility = View.GONE

        adapter = TwoLineAdapter.bind(binding.recycler, emptyList())
        render()
    }

    private fun render() {
        lifecycleScope.launch {
            val caps = withContext(Dispatchers.IO) {
                NoxsDeviceCapabilities.probe(applicationContext, paths)
            }
            val rows = buildList {
                caps.toDisplayLines().forEach { (k, v) ->
                    add(TwoLineRow(title = k, subtitle = v))
                }
                add(TwoLineRow(title = "sudo scope", subtitle = "Noxs userspace only — never Android root"))
                add(
                    TwoLineRow(
                        title = "Unix sockets",
                        subtitle = "control: @${com.noxs.linux.runtime.NoxsSocketServer.CONTROL_SOCKET_ABSTRACT} · runtime: ${NoxsConstants.VAR_RUN_DIR}/noxs/"
                    )
                )
                addAll(caps.detail.map { (k, v) -> TwoLineRow(title = k, subtitle = v) })
            }
            adapter?.submit(rows)
        }
    }
}
