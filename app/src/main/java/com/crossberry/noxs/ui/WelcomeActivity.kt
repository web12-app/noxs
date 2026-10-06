/*
 * Noxs — original implementation.
 * Welcome screen: entry point. Routes first-time users to the environment
 * picker (spec §1, §51), existing users to the terminal, and never silently
 * installs anything (spec §4).
 */
package com.crossberry.noxs.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.crossberry.noxs.R
import com.crossberry.noxs.databinding.ActivityWelcomeBinding
import com.crossberry.noxs.environments.model.EnvironmentStatus
import com.crossberry.noxs.runtime.NoxsService

class WelcomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWelcomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWelcomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = application as com.crossberry.noxs.NoxsApplication
        // An environment is ready when a fresh install finished OR the legacy
        // Debian install was imported on upgrade (spec §63).
        val ready = app.environments.environments.value.any { it.status == EnvironmentStatus.READY }

        binding.btnTerminal.isEnabled = ready
        binding.btnSetup.text = getString(
            if (ready) R.string.nav_settings else R.string.welcome_setup
        )

        binding.btnSetup.setOnClickListener {
            startActivity(
                Intent(
                    this,
                    if (ready) SettingsActivity::class.java else EnvironmentPickerActivity::class.java
                )
            )
        }
        binding.btnTerminal.setOnClickListener {
            if (permissionOnboardingPending()) {
                // First-launch flow (Noxs API spec §9): Permission Center ->
                // verify -> Noxs Home. Runs once; afterwards the terminal
                // opens directly.
                NoxsService.start(this)
                startActivity(
                    Intent(this, PermissionCenterActivity::class.java)
                        .putExtra(PermissionCenterActivity.EXTRA_FIRST_RUN, true)
                )
                return@setOnClickListener
            }
            NoxsService.start(this)
            startActivity(Intent(this, TerminalActivity::class.java))
        }
    }

    private fun permissionOnboardingPending(): Boolean {
        val prefs = getSharedPreferences("noxs_settings", MODE_PRIVATE)
        if (prefs.getBoolean("permission_onboarding_done", false)) return false
        prefs.edit().putBoolean("permission_onboarding_done", true).apply()
        return true
    }
}
