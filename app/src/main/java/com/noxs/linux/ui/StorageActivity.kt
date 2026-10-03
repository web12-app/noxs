/*
 * Noxs — original implementation.
 * Storage management: usage report, apt cache cleanup, full sandbox reset.
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StorageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityManagerBinding
    private lateinit var paths: NoxsPaths
    private var adapter: TwoLineAdapter? = null

    private val resources by lazy { NoxsResources(paths) }
    private val storage by lazy { NoxsRuntimeFactory.storage(paths, resources) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        paths = (application as com.noxs.linux.NoxsApplication).paths

        binding.screenTitle.text = getString(R.string.title_storage)
        binding.btnAction.text = getString(R.string.action_refresh)
        binding.btnAction.setOnClickListener { render() }
        binding.inputBar.visibility = View.GONE

        adapter = TwoLineAdapter.bind(binding.recycler, listOf())

        binding.btnInputAction.setOnClickListener { }
        render()
    }

    private fun render() {
        lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) { storage.report() }
            val rows = listOf(
                TwoLineRow(
                    title = "${getString(R.string.storage_rootfs)}: ${report.rootfsMb} MB",
                    subtitle = if (report.rootfsMb > report.warnMb) getString(R.string.storage_warn_exceeded) else "limit warning at ${report.warnMb} MB"
                ),
                TwoLineRow(
                    title = "${getString(R.string.storage_apt_cache)}: ${report.aptCacheMb} MB",
                    subtitle = getString(R.string.storage_clear_cache),
                    onClick = {
                        lifecycleScope.launch {
                            val n = withContext(Dispatchers.IO) { storage.clearAptCache() }
                            Toast.makeText(this@StorageActivity, "$n .deb removed", Toast.LENGTH_SHORT).show()
                            render()
                        }
                    }
                ),
                TwoLineRow(
                    title = "${getString(R.string.storage_logs)}: ${report.logsMb} MB",
                    subtitle = getString(R.string.diag_clear),
                    onClick = {
                        com.noxs.linux.shared.NoxsLog.clear()
                        render()
                    }
                ),
                TwoLineRow(
                    title = getString(R.string.storage_reset),
                    subtitle = getString(R.string.confirm_reset_msg),
                    onClick = {
                        AlertDialog.Builder(this@StorageActivity)
                            .setTitle(R.string.confirm_reset_title)
                            .setMessage(R.string.confirm_reset_msg)
                            .setPositiveButton(R.string.action_confirm) { _, _ ->
                                lifecycleScope.launch {
                                    val ok = withContext(Dispatchers.IO) { storage.resetSandbox() }
                                    Toast.makeText(
                                        this@StorageActivity,
                                        if (ok) R.string.settings_saved else R.string.err_generic,
                                        Toast.LENGTH_LONG
                                    ).show()
                                    render()
                                }
                            }
                            .setNegativeButton(R.string.action_cancel, null)
                            .show()
                    }
                )
            )
            adapter?.submit(rows)
            binding.screenEmpty.visibility = if (paths.rootfs.isDirectory) View.GONE else View.VISIBLE
            binding.screenEmpty.text = getString(R.string.err_not_installed)
        }
    }
}
