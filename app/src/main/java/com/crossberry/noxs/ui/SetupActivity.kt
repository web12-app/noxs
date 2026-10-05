/*
 * Noxs setup screen: masked password entry and a curated live progress view.
 * Raw bootstrap output is filtered; only failure diagnostics are kept privately.
 */
package com.crossberry.noxs.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.crossberry.noxs.R
import com.crossberry.noxs.databinding.ActivitySetupBinding
import com.crossberry.noxs.runtime.NoxsInstaller
import com.crossberry.noxs.runtime.NoxsPaths
import com.crossberry.noxs.runtime.NoxsResources
import com.crossberry.noxs.runtime.NoxsRuntimeFactory
import com.crossberry.noxs.runtime.NoxsService
import com.crossberry.noxs.shared.NoxsLog
import kotlinx.coroutines.launch

class SetupActivity : AppCompatActivity(), NoxsInstaller.Progress {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var paths: NoxsPaths

    /** Password is held only in memory until chpasswd consumes it. */
    private var collectedPassword: CharArray? = null
    private var lastProgressLine: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        paths = (application as com.crossberry.noxs.NoxsApplication).paths

        binding.etPassword.doAfterTextChanged { binding.passwordLayout.error = null }
        binding.etPassword2.doAfterTextChanged { binding.passwordConfirmLayout.error = null }
        binding.btnOpenTerminal.setOnClickListener {
            NoxsService.start(this)
            startActivity(Intent(this, TerminalActivity::class.java))
            finish()
        }

        if (paths.isInstalled()) {
            showDone()
            return
        }

        binding.btnStart.setOnClickListener { beginSetup() }
    }

    private fun beginSetup() {
        val password = copyToCharArray(binding.etPassword.text)
        val confirmation = copyToCharArray(binding.etPassword2.text)

        if (password == null || confirmation == null || password.size < MIN_PASSWORD_LENGTH) {
            password?.fill('\u0000')
            confirmation?.fill('\u0000')
            binding.passwordLayout.error = getString(R.string.setup_password_min_length)
            return
        }
        if (password.any { it == ':' || Character.isISOControl(it) }) {
            password.fill('\u0000')
            confirmation.fill('\u0000')
            binding.passwordLayout.error = getString(R.string.setup_password_invalid)
            return
        }
        if (!password.contentEquals(confirmation)) {
            password.fill('\u0000')
            confirmation.fill('\u0000')
            binding.passwordConfirmLayout.error = getString(R.string.setup_password_mismatch)
            return
        }
        confirmation.fill('\u0000')

        collectedPassword?.fill('\u0000')
        collectedPassword = password
        binding.etPassword.text?.clear()
        binding.etPassword2.text?.clear()
        currentFocus?.let { focused ->
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(focused.windowToken, 0)
            focused.clearFocus()
        }

        binding.tvLog.text = ""
        lastProgressLine = null
        binding.tvStep.text = getString(R.string.setup_subtitle)
        binding.progress.isIndeterminate = true
        binding.progressBlock.visibility = View.VISIBLE
        binding.passwordBlock.visibility = View.GONE
        binding.btnStart.visibility = View.GONE

        val resources = NoxsResources(paths)
        val launcher = NoxsRuntimeFactory.launcher(paths, resources)
        val installer = NoxsInstaller(this, paths, launcher)
        lifecycleScope.launch {
            try {
                when (val result = installer.install(this@SetupActivity)) {
                    is NoxsInstaller.InstallResult.Success -> showDone()
                    is NoxsInstaller.InstallResult.Failure -> {
                        // Full details stay in the private diagnostics buffer, not on
                        // the setup screen or in the user's terminal transcript.
                        NoxsLog.e("Setup", "setup failed: ${result.detail}")
                        binding.progress.isIndeterminate = false
                        binding.progress.progress = 0
                        binding.tvStep.text = getString(R.string.setup_failed_generic)
                        appendProgressLine(getString(R.string.setup_failed_generic))
                        binding.passwordBlock.visibility = View.VISIBLE
                        binding.btnStart.visibility = View.VISIBLE
                        binding.btnStart.text = getString(R.string.setup_retry)
                    }
                }
            } finally {
                clearCollectedPassword()
            }
        }
    }

    private fun showDone() {
        clearCollectedPassword()
        binding.passwordBlock.visibility = View.GONE
        binding.btnStart.visibility = View.GONE
        binding.progressBlock.visibility = View.GONE
        binding.doneBlock.visibility = View.VISIBLE
    }

    private fun copyToCharArray(source: CharSequence?): CharArray? =
        source?.let { text -> CharArray(text.length) { index -> text[index] } }

    private fun clearCollectedPassword() {
        collectedPassword?.fill('\u0000')
        collectedPassword = null
        binding.etPassword.text?.clear()
        binding.etPassword2.text?.clear()
    }

    override fun onDestroy() {
        if (::binding.isInitialized) clearCollectedPassword()
        super.onDestroy()
    }

    private fun messageForStep(step: Int): String = when (step) {
        1 -> "Getting Noxs ready"
        2 -> "Preparing secure storage"
        3 -> "Checking setup"
        4 -> "Preparing your environment"
        5 -> "Downloading your Linux environment"
        6 -> "Preparing your files"
        7 -> "Setting up your environment"
        8 -> "Creating your Linux account"
        9 -> "Preparing your workspace"
        10 -> "Preparing secure connections"
        11 -> "Finishing setup"
        else -> "Setting up Noxs"
    }

    private fun appendProgressLine(message: String) {
        if (message == lastProgressLine) return
        lastProgressLine = message
        binding.tvLog.append("› $message\n")
        binding.setupLogScroll.post { binding.setupLogScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ---- NoxsInstaller.Progress ----

    override fun onStep(step: Int, titleRes: Int, detail: String) {
        runOnUiThread {
            val message = messageForStep(step)
            binding.tvStep.text = message
            if (step != 5) binding.progress.isIndeterminate = true
            appendProgressLine(message)
        }
    }

    override fun onProgressBytes(downloaded: Long, total: Long) {
        runOnUiThread {
            if (total <= 0L) {
                binding.progress.isIndeterminate = true
            } else {
                binding.progress.isIndeterminate = false
                val percentage = ((downloaded.coerceAtLeast(0L) * 100L) / total)
                    .coerceIn(0L, 100L).toInt()
                binding.progress.setProgressCompat(percentage, true)
            }
        }
    }

    override fun onLog(line: String) {
        // Internal URLs, package output, extraction counts and native-library
        // details are intentionally never echoed into the setup view.
        val safeMessage = SetupProgressSanitizer.message(line) ?: return
        runOnUiThread { appendProgressLine(safeMessage) }
    }

    override fun onPasswordRequired(): CharArray? = collectedPassword

    private companion object {
        const val MIN_PASSWORD_LENGTH = 4
    }
}
