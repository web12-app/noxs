/*
 * Noxs — original implementation.
 * Welcome screen: entry point. Routes to setup wizard or terminal.
 */
package com.crossberry.noxs.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.crossberry.noxs.R
import com.crossberry.noxs.databinding.ActivityWelcomeBinding
import com.crossberry.noxs.runtime.NoxsService

class WelcomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWelcomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWelcomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = application as com.crossberry.noxs.NoxsApplication
        val installed = app.paths.isInstalled()

        binding.btnTerminal.isEnabled = installed
        binding.btnSetup.text = getString(
            if (installed) R.string.nav_settings else R.string.welcome_setup
        )

        binding.btnSetup.setOnClickListener {
            startActivity(Intent(this, if (installed) SettingsActivity::class.java else SetupActivity::class.java))
        }
        binding.btnTerminal.setOnClickListener {
            NoxsService.start(this)
            startActivity(Intent(this, TerminalActivity::class.java))
        }
    }
}
