/*
 * Noxs — original implementation.
 * Welcome screen: entry point. Routes to setup wizard or terminal.
 */
package com.noxs.linux.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.noxs.linux.R
import com.noxs.linux.databinding.ActivityWelcomeBinding
import com.noxs.linux.runtime.NoxsService

class WelcomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWelcomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWelcomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = application as com.noxs.linux.NoxsApplication
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
