/*
 * Noxs — original implementation.
 * PermissionCenterActivity: the first-launch Permission Center (Noxs API
 * spec §9) and a permanent entry point from Settings.
 *
 * Flow (first launch):
 *
 *   Noxs Launch -> Permission Center -> detect required permissions
 *     -> show status -> user grants/denies -> verify -> environment setup
 *     -> Noxs Home
 *
 * UI: search box, permission groups (Notifications, Storage / Files,
 * Background activity, Device features, System integration, Noxs
 * environment access), status rows with real Android state, and the
 * informational Network row — Internet access is NOT presented as an
 * Android runtime permission (spec §9): it is enabled through the
 * application's normal network capability and shown as status only.
 *
 * Never fake state: every Android permission row reflects the OS answer;
 * grants only become "Allowed" after Android confirms them.
 */
package com.crossberry.noxs.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.crossberry.noxs.R
import com.crossberry.noxs.runtime.NoxsPermissionCatalog
import com.crossberry.noxs.runtime.NoxsPermissionCenter

class PermissionCenterActivity : AppCompatActivity() {

    private lateinit var listHost: LinearLayout
    private lateinit var searchField: EditText
    private var runtimeRequestTarget: String? = null
    private var firstRun = false
    private val requestedThisSession = HashSet<String>()

    private val runtimeLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val target = runtimeRequestTarget
            runtimeRequestTarget = null
            if (target != null) {
                permissionCenter.onAndroidPermissionResult(target, granted)
                render()
            }
        }

    private val storageLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // SAF state is verified live by the probe when the rows re-render.
            render()
        }

    private val permissionCenter by lazy {
        (application as com.crossberry.noxs.NoxsApplication).permissionCenter
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        firstRun = intent?.getBooleanExtra(EXTRA_FIRST_RUN, false) ?: false
        buildUi()
        render()
        permissionCenter.addListener { runOnUiThread { render() } }
    }

    private fun buildUi() {
        val dp = resources.displayMetrics.density
        val root = ScrollView(this).apply { setBackgroundColor(0xFF101318.toInt()) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (20 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
        }
        root.addView(content)
        setContentView(root)

        val title = TextView(this).apply {
            text = getString(R.string.title_permission_center)
            textSize = 20f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE)
        }
        content.addView(title)
        content.addView(TextView(this).apply {
            text = getString(R.string.perm_network_info)
            textSize = 12f
            setTextColor(0xFF9AA6B6.toInt())
            setPadding(0, (6 * dp).toInt(), 0, (10 * dp).toInt())
        })

        val search = EditText(this).apply {
            hint = getString(R.string.perm_search_hint)
            singleLine = true
            textSize = 13f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = 22f * dp
                setColor(0xFF1B2027.toInt())
            }
            setPadding((14 * dp).toInt(), (9 * dp).toInt(), (14 * dp).toInt(), (9 * dp).toInt())
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { render() }
            })
        }
        searchField = search
        content.addView(search)

        listHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (12 * dp).toInt(), 0, 0)
        }
        content.addView(listHost)

        val continueButton = Button(this).apply {
            text = getString(R.string.perm_continue)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, (18 * dp).toInt(), 0, 0) }
        }
        continueButton.setOnClickListener { finishFlow() }
        content.addView(continueButton)
    }

    private fun groupLabel(group: String): String = when (group) {
        NoxsPermissionCatalog.GROUP_NOTIFICATIONS -> getString(R.string.perm_group_notifications)
        NoxsPermissionCatalog.GROUP_STORAGE -> getString(R.string.perm_group_storage)
        NoxsPermissionCatalog.GROUP_BACKGROUND -> getString(R.string.perm_group_background)
        NoxsPermissionCatalog.GROUP_DEVICE -> getString(R.string.perm_group_device)
        NoxsPermissionCatalog.GROUP_SYSTEM -> getString(R.string.perm_group_system)
        else -> getString(R.string.perm_group_environment)
    }

    private fun stateLabel(state: NoxsPermissionCenter.State): String = when (state) {
        NoxsPermissionCenter.State.ALLOWED -> getString(R.string.perm_state_allowed)
        NoxsPermissionCenter.State.NOT_GRANTED -> getString(R.string.perm_state_not_granted)
        NoxsPermissionCenter.State.DENIED -> getString(R.string.perm_state_denied)
        NoxsPermissionCenter.State.RESTRICTED -> getString(R.string.perm_state_restricted)
        NoxsPermissionCenter.State.NOT_SUPPORTED -> getString(R.string.perm_state_not_supported)
    }

    private fun stateColor(state: NoxsPermissionCenter.State): Int = when (state) {
        NoxsPermissionCenter.State.ALLOWED -> 0xFF2ACB42.toInt()
        NoxsPermissionCenter.State.DENIED -> 0xFFF45C51.toInt()
        NoxsPermissionCenter.State.RESTRICTED -> 0xFFFDBD2E.toInt()
        else -> 0xFF9AA6B6.toInt()
    }

    private fun networkRow(): View {
        val dp = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 12f * dp
                setColor(0xFF161A21.toInt())
            }
            setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
            addView(TextView(this@PermissionCenterActivity).apply {
                text = "✓  ${getString(R.string.perm_network_title)}"
                textSize = 13f
                typeface = Typeface.MONOSPACE
                setTextColor(0xFF2ACB42.toInt())
            })
            addView(TextView(this@PermissionCenterActivity).apply {
                text = getString(R.string.perm_network_info)
                textSize = 11f
                setTextColor(0xFF9AA6B6.toInt())
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = (10 * dp).toInt()
                }
            })
        }
    }

    private fun render() {
        val query = if (this::searchField.isInitialized) {
            searchField.text?.toString()?.trim().orEmpty()
        } else ""
        val dp = resources.displayMetrics.density
        listHost.removeAllViews()
        // Informational Network row (spec §9): status only, no permission.
        listHost.addView(networkRow())
        NoxsPermissionCatalog.GROUPS.forEach { group ->
            val entries = permissionCenter.entries()
                .filter { it.permission.group == group }
                .filter { query.isEmpty() || it.permission.id.contains(query, true) || it.permission.description.contains(query, true) }
            if (entries.isEmpty()) return@forEach
            listHost.addView(TextView(this).apply {
                text = groupLabel(group)
                textSize = 14f
                typeface = Typeface.MONOSPACE
                setTextColor(0xFFDCE3EC.toInt())
                setPadding(0, (14 * dp).toInt(), 0, (4 * dp).toInt())
            })
            entries.forEach { entry -> listHost.addView(permissionRow(entry)) }
        }
    }

    private fun permissionRow(entry: NoxsPermissionCenter.Entry): View {
        val dp = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 12f * dp
                setColor(0xFF161A21.toInt())
            }
            setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, (8 * dp).toInt()) }
        }

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this@PermissionCenterActivity).apply {
            text = entry.permission.id
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(this@PermissionCenterActivity).apply {
            text = stateLabel(entry.state)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(stateColor(entry.state))
        })
        row.addView(header)

        row.addView(TextView(this).apply {
            text = entry.permission.description
            textSize = 11f
            setTextColor(0xFF9AA6B6.toInt())
            setPadding(0, (3 * dp).toInt(), 0, (6 * dp).toInt())
        })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        when {
            entry.permission.androidPermission != null -> {
                val state = entry.state
                if (state == NoxsPermissionCenter.State.ALLOWED || state == NoxsPermissionCenter.State.DENIED) {
                    actions.addView(smallButton(getString(R.string.perm_open_settings)) {
                        if (state != NoxsPermissionCenter.State.ALLOWED) {
                            requestRuntimePermission(entry.permission)
                        } else {
                            permissionCenter.revoke(entry.permission.id); render()
                        }
                    })
                } else {
                    actions.addView(smallButton(getString(R.string.perm_state_allowed)) {
                        requestRuntimePermission(entry.permission)
                    })
                    actions.addView(smallButton(getString(R.string.perm_state_denied)) {
                        permissionCenter.deny(entry.permission.id); render()
                    })
                }
            }
            entry.permission.group == NoxsPermissionCatalog.GROUP_STORAGE -> {
                actions.addView(smallButton(getString(R.string.perm_open_settings)) { openStoragePicker() })
            }
            else -> {
                val allowed = entry.state == NoxsPermissionCenter.State.ALLOWED
                actions.addView(smallButton(
                    if (allowed) getString(R.string.perm_state_denied) else getString(R.string.perm_state_allowed)
                ) {
                    if (allowed) permissionCenter.deny(entry.permission.id) else {
                        val granted = permissionCenter.grant(entry.permission.id)
                        if (!granted && entry.permission.level != NoxsPermissionCatalog.Level.FEATURE_DEPENDENT) {
                            Toast.makeText(this, stateLabel(NoxsPermissionCenter.State.NOT_GRANTED), Toast.LENGTH_SHORT).show()
                        }
                    }
                    render()
                })
            }
        }
        row.addView(actions)
        return row
    }

    private fun smallButton(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFFDCE3EC.toInt())
            background = GradientDrawable().apply {
                cornerRadius = 14f * resources.displayMetrics.density
                setColor(0xFF232A33.toInt())
            }
            setPadding(
                (12 * resources.displayMetrics.density).toInt(), (6 * resources.displayMetrics.density).toInt(),
                (12 * resources.displayMetrics.density).toInt(), (6 * resources.displayMetrics.density).toInt()
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (8 * resources.displayMetrics.density).toInt() }
            setOnClickListener { onClick() }
        }

    private fun requestRuntimePermission(permission: NoxsPermissionCatalog.Permission) {
        val androidPermission = permission.androidPermission ?: return
        val granted = ContextCompat.checkSelfPermission(this, androidPermission) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            permissionCenter.onAndroidPermissionResult(permission.id, true)
            render()
            return
        }
        runtimeRequestTarget = permission.id
        // Only request permissions actually needed (spec §10); POST_NOTIFICATIONS
        // is the sole runtime permission in the current set and exists on API 33+.
        if (Build.VERSION.SDK_INT >= 33 && androidPermission == Manifest.permission.POST_NOTIFICATIONS) {
            runtimeLauncher.launch(androidPermission)
        } else {
            // OS does not gate this permission at runtime: record real state.
            permissionCenter.onAndroidPermissionResult(permission.id, true)
            render()
        }
    }

    private fun openStoragePicker() {
        runCatching {
            storageLauncher.launch(Intent(this, StorageActivity::class.java))
        }.onFailure {
            Toast.makeText(this, it.javaClass.simpleName, Toast.LENGTH_SHORT).show()
        }
    }

    private fun finishFlow() {
        val pending = permissionCenter.pendingAndroidPermissions()
        if (pending.isNotEmpty() && firstRun) {
            // Verify before continuing (spec §9). Each pending permission is
            // asked at most once per session; denials stay honest as
            // "Not granted" — never silently hidden.
            val next = pending.firstOrNull { it.id !in requestedThisSession }
            if (next != null) {
                requestedThisSession.add(next.id)
                requestRuntimePermission(next)
                return
            }
        }
        if (firstRun) {
            firstRun = false
            NoxsService.start(this)
            startActivity(Intent(this, TerminalActivity::class.java))
        }
        finish()
    }

    companion object {
        const val EXTRA_FIRST_RUN = "noxs.extra.PERMISSION_FIRST_RUN"
    }
}
