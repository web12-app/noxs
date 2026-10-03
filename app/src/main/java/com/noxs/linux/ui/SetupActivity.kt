/*
 * Noxs — original implementation.
 * Setup wizard + bootstrap progress screen (spec first-launch flow).
 */
package com.noxs.linux.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.noxs.linux.R
import com.noxs.linux.databinding.ActivitySetupBinding
import com.noxs.linux.runtime.NoxsInstaller
import com.noxs.linux.runtime.NoxsPaths
import com.noxs.linux.runtime.NoxsResources
import com.noxs.linux.runtime.NoxsRuntimeFactory
import com.noxs.linux.runtime.NoxsService
import kotlinx.coroutines.launch

class SetupActivity : AppCompatActivity(), NoxsInstaller.Progress {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var paths: NoxsPaths

    private var collectedPassword: CharArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        paths = (application as com.noxs.linux.NoxsApplication).paths

        if (paths.isInstalled()) {
            showDone("cached")
            return
        }

        binding.btnStart.setOnClickListener {
            val p1 = binding.etPassword.text?.toString()?.toCharArray()
            val p2 = binding.etPassword2.text?.toString()?.toCharArray()
            if (p1 == null || p2 == null || p1.size < 4) {
                Toast.makeText(this, getString(R.string.setup_password_hint), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!p1.contentEquals(p2)) {
                Toast.makeText(this, getString(R.string.setup_password_mismatch), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            collectedPassword = p1
            p2.fill('0')
            binding.passwordBlock.visibility = View.GONE
            binding.btnStart.visibility = View.GONE
            binding.progressBlock.visibility = View.VISIBLE

            val resources = NoxsResources(paths)
            val launcher = NoxsRuntimeFactory.launcher(paths, resources)
            val installer = NoxsInstaller(this, paths, launcher)
            lifecycleScope.launch {
                when (val r = installer.install(this@SetupActivity)) {
                    is NoxsInstaller.InstallResult.Success -> showDone(r.rootfsSha256)
                    is NoxsInstaller.InstallResult.Failure -> {
                        val msg = getString(r.userMessageRes)
                        Toast.makeText(this@SetupActivity, "$msg\n${r.detail}", Toast.LENGTH_LONG).show()
                        binding.tvLog.append("> failed: ${r.detail}\n")
                        binding.passwordBlock.visibility = View.VISIBLE
                        binding.btnStart.visibility = View.VISIBLE
                    }
                }
            }
        }

        binding.btnOpenTerminal.setOnClickListener {
            NoxsService.start(this)
            startActivity(Intent(this, TerminalActivity::class.java))
            finish()
        }
    }

    private fun showDone(sha: String) {
        binding.passwordBlock.visibility = View.GONE
        binding.btnStart.visibility = View.GONE
        binding.progressBlock.visibility = View.GONE
        binding.doneBlock.visibility = View.VISIBLE
        binding.tvSha.text = getString(R.string.setup_sha_prefix) + "\n" + sha
    }

    // ---- NoxsInstaller.Progress ----

    override fun onStep(step: Int, titleRes: Int, detail: String) {
        runOnUiThread {
            binding.tvStep.text = "[$step/11] " + getString(titleRes) +
                (if (detail.isNotEmpty()) " — $detail" else "")
        }
    }

    override fun onProgressBytes(downloaded: Long, total: Long) {
        runOnUiThread {
            binding.tvBytes.text =
                "${downloaded / (1024 * 1024)} MB" + (if (total > 0) " / ${total / (1024 * 1024)} MB" else "")
        }
    }

    override fun onLog(line: String) {
        runOnUiThread {
            binding.tvLog.append("> $line\n")
        }
    }

    override fun onPasswordRequired(): CharArray? = collectedPassword
}
