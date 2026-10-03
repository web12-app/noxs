/*
 * Noxs — original implementation.
 * About screen: identity, licensing, non-affiliation statement.
 */
package com.noxs.linux.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.noxs.linux.R
import com.noxs.linux.databinding.ActivityManagerBinding
import com.noxs.linux.shared.NoxsConstants

class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityManagerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.screenTitle.text = getString(R.string.title_about)
        binding.screenHint.visibility = View.VISIBLE
        binding.screenHint.text = getString(R.string.about_body)
        binding.btnAction.visibility = View.GONE
        binding.inputBar.visibility = View.GONE
        binding.recycler.visibility = View.GONE

        binding.screenEmpty.visibility = View.VISIBLE
        binding.screenEmpty.text = getString(R.string.about_version, NoxsConstants.VERSION_NAME) +
            "\nApplication ID: ${NoxsConstants.APP_ID}" +
            "\nDefault userspace: Debian 12 ${NoxsConstants.DEFAULT_DEBIAN_SUITE}" +
            "\nEngine: proot (userland, no root required)"
    }
}
