/*
 * Noxs — original implementation.
 * User manager: lists /etc/passwd of the Noxs rootfs, creates users (Debian
 * useradd inside the sandbox), sets passwords via chpasswd stdin.
 */
package com.crossberry.noxs.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.crossberry.noxs.R
import com.crossberry.noxs.databinding.ActivityManagerBinding
import com.crossberry.noxs.runtime.NoxsPaths
import com.crossberry.noxs.runtime.NoxsResources
import com.crossberry.noxs.runtime.NoxsRuntimeFactory
import kotlinx.coroutines.launch

class UserManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityManagerBinding
    private lateinit var paths: NoxsPaths
    private var adapter: TwoLineAdapter? = null

    private val resources by lazy { NoxsResources(paths) }
    private val launcher by lazy { NoxsRuntimeFactory.launcher(paths, resources) }
    private val exec by lazy { NoxsRuntimeFactory.executor(launcher) }
    private val users by lazy { NoxsRuntimeFactory.users(exec, paths, launcher) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        paths = (application as com.crossberry.noxs.NoxsApplication).paths

        binding.screenTitle.text = getString(R.string.title_user_manager)
        binding.inputBar.visibility = View.VISIBLE
        binding.etInput.hint = getString(R.string.users_new_name)
        binding.btnInputAction.text = getString(R.string.users_create)
        binding.btnAction.visibility = View.VISIBLE
        binding.btnAction.text = getString(R.string.action_refresh)

        adapter = TwoLineAdapter.bind(binding.recycler, emptyList())

        binding.btnInputAction.setOnClickListener {
            val name = binding.etInput.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) return@setOnClickListener
            lifecycleScope.launch {
                val ok = users.createUser(name)
                Toast.makeText(
                    this@UserManagerActivity,
                    if (ok) R.string.action_confirm else R.string.err_generic,
                    Toast.LENGTH_SHORT
                ).show()
                binding.etInput.setText("")
                render()
            }
        }

        binding.btnAction.setOnClickListener { render() }
        render()
    }

    private fun render() {
        val list = users.listUsers()
        adapter?.submit(list.map { u ->
            TwoLineRow(
                title = "${u.name} (uid ${u.uid})",
                subtitle = "${getString(R.string.users_home)} ${u.home} · ${u.shell}",
                onClick = { userActions(u.name) },
                onLongClick = {
                    if (users.isNoxsUser(u)) {
                        Toast.makeText(this, R.string.users_noxs_protected, Toast.LENGTH_SHORT).show()
                    } else {
                        AlertDialog.Builder(this)
                            .setTitle(R.string.action_delete)
                            .setMessage(u.name)
                            .setPositiveButton(R.string.action_delete) { _, _ ->
                                lifecycleScope.launch {
                                    users.deleteUser(u.name)
                                    render()
                                }
                            }
                            .setNegativeButton(R.string.action_cancel, null)
                            .show()
                    }
                }
            )
        })
        binding.screenEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        binding.screenEmpty.text = getString(R.string.err_not_installed)
    }

    private fun userActions(name: String) {
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.setup_password_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.users_set_password) + " — $name")
            .setView(input)
            .setPositiveButton(R.string.action_apply) { _, _ ->
                val pw = input.text?.toString()?.toCharArray()
                if (pw != null && pw.size >= 4) {
                    lifecycleScope.launch {
                        val ok = users.setPassword(name, pw)
                        Toast.makeText(
                            this@UserManagerActivity,
                            if (ok) R.string.settings_saved else R.string.err_generic,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
}
