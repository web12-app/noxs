/*
 * Noxs — original implementation.
 * EnvironmentInstallActivity — live setup screen (spec §16, §17, §36, §42,
 * §46): a real checklist driven by the single SetupTask source of truth,
 * honest progress (no fake percentages), elapsed time, cancel at safe points,
 * password prompt via a masked in-memory dialog, and the optional-components
 * step after success.
 */
package com.crossberry.noxs.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.crossberry.noxs.R
import com.crossberry.noxs.environments.model.EnvironmentStatus
import com.crossberry.noxs.environments.model.SetupFormat
import com.crossberry.noxs.environments.model.SetupState
import com.crossberry.noxs.environments.model.SetupTask
import com.crossberry.noxs.environments.model.SetupStateMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class EnvironmentInstallActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROVIDER_ID = "providerId"
        const val EXTRA_VARIANT_ID = "variantId"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var container: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var operationText: TextView
    private lateinit var checklist: LinearLayout
    private lateinit var actionRow: LinearLayout

    private var providerId: String = ""
    private var variantId: String = ""
    @Volatile private var passwordAsked = false

    @Suppress("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        providerId = intent.getStringExtra(EXTRA_PROVIDER_ID) ?: intent.getStringExtra("environmentId") ?: ""
        variantId = intent.getStringExtra(EXTRA_VARIANT_ID) ?: ""

        val root = ScrollView(this)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        root.addView(container)
        setContentView(root)

        setTitle(getString(R.string.env_install_title))
        buildUi()
        maybeStartInstall()

        // Observe the single source of truth (spec §56).
        val app = application as com.crossberry.noxs.NoxsApplication
        scope.launch {
            app.environments.taskManager.tasks.collect { tasks ->
                val task = tasks.lastOrNull { it.environmentId == providerId || it.id.endsWith(providerId) }
                if (task != null) render(task)
            }
        }
    }

    private fun buildUi() {
        val app = application as com.crossberry.noxs.NoxsApplication
        val provider = app.environments.providers.firstOrNull { it.id == providerId }

        container.addView(TextView(this).apply {
            text = provider?.displayName ?: providerId
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        })

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = false
        }
        container.addView(progressBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        progressText = TextView(this).apply { textSize = 14f }
        operationText = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(6), 0, dp(6))
        }
        container.addView(progressText)
        container.addView(operationText)

        container.addView(TextView(this).apply {
            text = getString(R.string.env_install_steps)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(14), 0, dp(6))
        })

        checklist = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(checklist)

        actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        container.addView(actionRow)
    }

    private fun maybeStartInstall() {
        val app = application as com.crossberry.noxs.NoxsApplication
        val manager = app.environments
        val provider = manager.providers.firstOrNull { it.id == providerId } ?: run {
            Toast.makeText(this, getString(R.string.env_error_unknown), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (manager.taskManager.isRunning(providerId)) return // reconnect to the running task
        val variant = provider.variants().firstOrNull { it.id == variantId }
            ?: provider.variants().firstOrNull { it.isDefault }
            ?: provider.variants().firstOrNull()
            ?: return

        val env = com.crossberry.noxs.environments.model.Environment(
            id = providerId,
            providerId = provider.id,
            displayName = provider.displayName,
            version = "",
            architecture = "arm64-v8a",
            variant = variant.id,
            status = EnvironmentStatus.INSTALLING,
            storagePath = java.io.File(manager.environmentsRoot, providerId).absolutePath,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        manager.upsert(env)
        manager.taskManager.startInstall(
            provider = provider,
            variant = variant,
            environment = env,
            passwordProvider = { askPasswordBlocking() }
        )
    }

    /**
     * Masked in-memory password dialog (spec §30): returned array is zeroed by
     * the caller. The setup thread blocks on a latch while the main thread owns
     * the dialog. The install keeps running app-side, so the screen may already
     * be finished when the setup thread reaches the password step — showing a
     * dialog on a dead activity window token crashes the whole app
     * (WindowManager$BadTokenException), so the prompt is skipped and the task
     * fails gracefully instead ("a Noxs password is required").
     */
    @Volatile private var pendingPassword: CharArray? = null
    @Volatile private var passwordDialog: AlertDialog? = null
    @Volatile private var passwordLatch: java.util.concurrent.CountDownLatch? = null
    private val passwordResolved = java.util.concurrent.atomic.AtomicBoolean(false)

    /** First resolution wins; never double-counts the latch. */
    private fun resolvePassword(value: CharArray?, latch: java.util.concurrent.CountDownLatch) {
        if (!passwordResolved.compareAndSet(false, true)) return
        pendingPassword = value
        latch.countDown()
    }

    private fun askPasswordBlocking(): CharArray? {
        if (passwordAsked) return pendingPassword
        passwordAsked = true
        val latch = java.util.concurrent.CountDownLatch(1)
        passwordLatch = latch
        runOnUiThread {
            if (isFinishing || isDestroyed || passwordResolved.get()) {
                resolvePassword(null, latch)
                return@runOnUiThread
            }
            val input = EditText(this).apply {
               TransformationMethodHelper.applyPassword(this)
                hint = getString(R.string.env_password_hint)
            }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(12), dp(24), 0)
            }
            box.addView(TextView(this).apply {
                text = getString(R.string.env_password_message)
                textSize = 14f
            })
            box.addView(input)
            passwordDialog = AlertDialog.Builder(this)
                .setTitle(getString(R.string.env_password_title))
                .setView(box)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    resolvePassword(input.text?.toString()?.toCharArray(), latch)
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    resolvePassword(null, latch)
                }
                .setCancelable(false)
                .show()
        }
        latch.await()
        passwordLatch = null
        passwordDialog = null
        return pendingPassword
    }

    private object TransformationMethodHelper {
        fun applyPassword(editText: EditText) {
            editText.transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
        }
    }

    // ---------------------------------------------------------------- render

    private fun render(task: SetupTask) {
        if (task.progress in 0..100) {
            progressBar.isIndeterminate = false
            progressBar.progress = task.progress
            progressText.text = "${task.progress}%"
        } else {
            progressBar.isIndeterminate = true
            progressText.text = ""
        }
        operationText.text = buildString {
            append(task.currentOperation)
            append("  ·  ")
            append(SetupFormat.elapsed(task.startedAt, System.currentTimeMillis()))
        }
        renderChecklist(task)
        renderActions(task)
        if (task.state == SetupState.READY) renderOptionalComponents()
    }

    private fun renderChecklist(task: SetupTask) {
        val stages = listOf(
            Triple(SetupState.CHECKING, getString(R.string.env_stage_checking), true),
            Triple(SetupState.DOWNLOADING, getString(R.string.env_stage_downloading), true),
            Triple(SetupState.VERIFYING, getString(R.string.env_stage_verifying), true),
            Triple(SetupState.EXTRACTING, getString(R.string.env_stage_extracting), true),
            Triple(SetupState.CONFIGURING, getString(R.string.env_stage_configuring), true),
            Triple(SetupState.CREATING_USER, getString(R.string.env_stage_user), false),
            Triple(SetupState.CONFIGURING_SHELL, getString(R.string.env_stage_shell), false),
            Triple(SetupState.VERIFYING_ENVIRONMENT, getString(R.string.env_stage_verify_install), false)
        )
        checklist.removeAllViews()
        val order = stages.indexOfFirst { it.first == task.state }
        stages.forEachIndexed { index, (state, label, _) ->
            val mark = when {
                task.state == SetupState.READY -> "✓"
                index < order -> "✓"
                index == order -> "●"
                else -> "○"
            }
            checklist.addView(TextView(this).apply {
                text = "$mark  $label"
                textSize = 15f
                setPadding(dp(8), dp(4), 0, dp(4))
                if (index == order && task.state != SetupState.READY) {
                    setTypeface(typeface, Typeface.BOLD)
                }
            })
        }
        if (task.state == SetupState.FAILED) {
            checklist.addView(TextView(this).apply {
                text = getString(R.string.env_install_failed, task.error)
                textSize = 14f
                setPadding(dp(8), dp(10), 0, 0)
            })
        }
        if (task.state == SetupState.RECOVERY_REQUIRED) {
            checklist.addView(TextView(this).apply {
                text = getString(R.string.env_install_interrupted)
                textSize = 14f
                setPadding(dp(8), dp(10), 0, 0)
            })
        }
    }

    private fun renderActions(task: SetupTask) {
        actionRow.removeAllViews()
        when (task.state) {
            SetupState.READY -> {
                val open = Button(this).apply {
                    text = getString(R.string.env_open_terminal)
                    setOnClickListener {
                        NoxsServiceStart()
                        startActivity(Intent(this@EnvironmentInstallActivity, TerminalActivity::class.java))
                        finish()
                    }
                }
                actionRow.addView(open, LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            SetupState.FAILED, SetupState.RECOVERY_REQUIRED -> {
                actionRow.addView(Button(this).apply {
                    text = getString(R.string.env_remove_incomplete)
                    setOnClickListener { removeIncomplete() }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                actionRow.addView(Button(this).apply {
                    text = getString(R.string.env_close)
                    setOnClickListener { finish() }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            else -> {
                if (task.state.isWorking && task.cancellable) {
                    actionRow.addView(Button(this).apply {
                        text = getString(R.string.env_cancel)
                        setOnClickListener {
                            (application as com.crossberry.noxs.NoxsApplication)
                                .environments.taskManager.cancel(task.environmentId)
                        }
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                }
            }
        }
    }

    private var optionalShown = false
    private val installingComponents = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun renderOptionalComponents() {
        if (optionalShown) return
        optionalShown = true
        container.addView(section(getString(R.string.env_optional_title)))
        container.addView(TextView(this).apply {
            text = getString(R.string.env_optional_subtitle)
            textSize = 13f
            setPadding(dp(8), 0, 0, dp(6))
        })
        // Each component is independently installable via the environment's own
        // package manager (spec §36). OpenCode is honest: Noxs never executes
        // downloaded scripts automatically (spec §48) — the user runs the
        // official installer in the terminal.
        listOf("Git", "Python", "Node.js", "code-server").forEach { component ->
            val row = TextView(this).apply {
                text = "☐  $component"
                textSize = 15f
                setPadding(dp(8), dp(4), 0, dp(4))
            }
            row.setOnClickListener { installOptionalComponent(component, row) }
            container.addView(row)
        }
        container.addView(TextView(this).apply {
            text = getString(R.string.env_optional_opencode_note)
            textSize = 13f
            setPadding(dp(8), dp(6), 0, 0)
        })
    }

    private fun installOptionalComponent(component: String, row: TextView) {
        val app = application as com.crossberry.noxs.NoxsApplication
        val manager = app.environments
        val env = manager.environmentFor(providerId) ?: return
        if (!manager.taskManager.isRunning(providerId) && !java.io.File(env.storagePath, "environment.json").isFile) {
            Toast.makeText(this, getString(R.string.env_error_unknown), Toast.LENGTH_SHORT).show()
            return
        }
        if (!installingComponents.add(component)) return
        val kind = manager.packageManagerFor(env)
        val adapter = com.crossberry.noxs.environments.PackageManagerAdapters.forKind(kind)
        val pkg = com.crossberry.noxs.environments.PackageManagerAdapters.optionalComponentPackage(component, kind)
        if (adapter == null || pkg == null) {
            installingComponents.remove(component)
            return
        }
        row.text = "⏳  $component — installing…"
        Thread {
            val ok = runCatching {
                val paths = manager.pathsFor(env)
                val launcher = com.crossberry.noxs.runtime.ProotLauncher(
                    paths, com.crossberry.noxs.runtime.NoxsResources(paths))
                runOneShot(launcher, adapter.update()) &&
                    runOneShot(launcher, adapter.install(pkg))
            }.getOrDefault(false)
            runOnUiThread {
                installingComponents.remove(component)
                row.text = if (ok) "☑  $component" else "✗  $component — failed, tap to retry"
            }
        }.start()
    }

    private fun runOneShot(
        launcher: com.crossberry.noxs.runtime.ProotLauncher,
        command: List<String>
    ): Boolean {
        val pb = ProcessBuilder(launcher.oneShotArgv(command, asRoot = true))
        launcher.applyEnvTo(pb, mapOf("NOXS_ROOT_LOGIN" to "1", "DEBIAN_FRONTEND" to "noninteractive"))
        pb.redirectErrorStream(true)
        val proc = pb.start()
        proc.inputStream.bufferedReader().use { reader -> while (reader.readLine() != null) Unit }
        return proc.waitFor() == 0
    }

    private fun section(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(14), 0, dp(6))
    }

    private fun removeIncomplete() {
        val app = application as com.crossberry.noxs.NoxsApplication
        val env = app.environments.environmentFor(providerId)
        if (env != null) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.env_remove_incomplete))
                .setMessage(getString(R.string.env_remove_confirm, env.displayName))
                .setPositiveButton(getString(R.string.env_remove)) { _, _ ->
                    app.environments.remove(providerId)
                    finish()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else finish()
    }

    private fun NoxsServiceStart() {
        com.crossberry.noxs.runtime.NoxsService.start(this)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        // If the password prompt is still open (or posted but not yet shown),
        // release the blocked setup thread with "no password" and dismiss the
        // dialog on the main thread — prevents both the BadTokenException crash
        // and a setup thread waiting forever on the latch.
        passwordAsked = true
        passwordDialog?.dismiss()
        passwordDialog = null
        passwordLatch?.let { resolvePassword(null, it) }
        scope.cancel()
        super.onDestroy()
    }
}
