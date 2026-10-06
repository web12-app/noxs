/*
 * Noxs — original implementation.
 * EnvironmentPickerActivity — first-launch wizard (spec §1, §3, §4, §5, §6,
 * §7, §9, §52): Welcome → Choose Environment → Details → Variant → Storage →
 * Confirm. The user explicitly selects an environment; nothing installs
 * automatically. Compatibility and storage numbers are real.
 */
package com.crossberry.noxs.ui

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.crossberry.noxs.R
import com.crossberry.noxs.environments.AndroidDeviceProfile
import com.crossberry.noxs.environments.DeviceProfile
import com.crossberry.noxs.environments.StorageCalculator
import com.crossberry.noxs.environments.model.CompatibilityLevel
import com.crossberry.noxs.environments.model.EnvironmentStatus
import com.crossberry.noxs.environments.model.EnvironmentVariant
import com.crossberry.noxs.environments.model.StoragePlan
import com.crossberry.noxs.environments.providers.RootfsTarballProvider
import com.crossberry.noxs.environments.model.SetupFormat

/**
 * Single-activity wizard with a simple two-page state:
 * page 1 = environment cards, page 2 = details + variant + storage + confirm.
 */
class EnvironmentPickerActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var device: DeviceProfile

    private var selectedProviderId: String? = null
    private var selectedVariant: EnvironmentVariant? = null

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as com.crossberry.noxs.NoxsApplication
        val storageRoot = app.environments.environmentsRoot
        device = AndroidDeviceProfile.probe(this).copy(
            availableStorageBytes = storageRoot.usableSpace
        )

        val root = ScrollView(this)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        root.addView(container)
        setContentView(root)

        showEnvironmentCards()
    }

    // ------------------------------------------------------------ page 1

    private fun showEnvironmentCards() {
        selectedProviderId = null
        selectedVariant = null
        container.removeAllViews()

        setTitle(getString(R.string.env_welcome_title))

        val header = TextView(this).apply {
            text = getString(R.string.env_welcome_title)
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        }
        val subtitle = TextView(this).apply {
            text = getString(R.string.env_welcome_subtitle)
            textSize = 15f
            setPadding(0, dp(8), 0, dp(16))
        }
        container.addView(header)
        container.addView(subtitle)

        val app = application as com.crossberry.noxs.NoxsApplication
        // One environment per distro (spec §33): providers with an installed
        // environment are not offered again — the imported legacy Debian is
        // never silently reinstalled or overwritten (spec §63).
        val readyProviderIds = app.environments.environments.value
            .filter { it.status == EnvironmentStatus.READY }
            .map { it.providerId }
            .toSet()
        for (provider in app.environments.providers) {
            if (provider.id in readyProviderIds) continue
            container.addView(environmentCard(provider))
        }
    }

    private fun environmentCard(provider: com.crossberry.noxs.environments.EnvironmentProvider): View {
        val report = provider.checkCompatibility(device)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground()
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, dp(12)) }
        card.layoutParams = params

        val nameRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        nameRow.addView(TextView(this@EnvironmentPickerActivity).apply {
            text = provider.displayName
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (provider.recommended) {
            nameRow.addView(badge("⭐ " + getString(R.string.env_recommended), COLOR_RECOMMEND))
        }
        nameRow.addView(badge(report.badge, levelColor(report.level)))
        card.addView(nameRow)

        card.addView(TextView(this).apply {
            text = provider.description
            textSize = 14f
            setPadding(0, dp(4), 0, dp(6))
        })

        val badgeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (provider.variants().isNotEmpty()) {
            badgeRow.addView(badge(pmShortLabel(provider), COLOR_NEUTRAL))
            badgeRow.addView(badge("ARM64", COLOR_NEUTRAL))
        }
        card.addView(badgeRow)

        val select = Button(this).apply {
            text = getString(R.string.env_select)
            isEnabled = provider.canInstall(device) || report.level == CompatibilityLevel.LIMITED
            setOnClickListener {
                if (!provider.canInstall(device)) {
                    showNotInstallable(provider, report)
                } else {
                    showDetails(provider)
                }
            }
        }
        card.addView(select)
        return card
    }

    private fun showNotInstallable(
        provider: com.crossberry.noxs.environments.EnvironmentProvider,
        report: com.crossberry.noxs.environments.model.CompatibilityReport
    ) {
        val reasons = report.reasons.joinToString("\n• ", prefix = "• ")
        AlertDialog.Builder(this)
            .setTitle(provider.displayName)
            .setMessage(getString(R.string.env_not_installable, reasons))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ------------------------------------------------------------ page 2

    private fun showDetails(provider: com.crossberry.noxs.environments.EnvironmentProvider) {
        selectedProviderId = provider.id
        selectedVariant = null
        container.removeAllViews()

        val back = Button(this).apply {
            text = getString(R.string.env_back)
            setOnClickListener { showEnvironmentCards() }
        }
        container.addView(back, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        container.addView(TextView(this).apply {
            text = provider.displayName
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(4))
        })

        val variants = provider.variants()
        val report = provider.checkCompatibility(device)
        val defaultVariant = variants.firstOrNull { it.isDefault } ?: variants.firstOrNull()

        // Details grid (spec §7).
        container.addView(detailRow(getString(R.string.env_detail_version),
            (provider as? RootfsTarballProvider)?.distro?.banner ?: getString(R.string.env_detail_current)))
        container.addView(detailRow(getString(R.string.env_detail_arch), device.abi))
        container.addView(detailRow(getString(R.string.env_detail_pm), pmShortLabel(provider)))
        container.addView(detailRow(getString(R.string.env_detail_runtime), runtimeLabel(provider)))
        container.addView(detailRow(getString(R.string.env_detail_status), report.badge))

        // Compatibility checks — actual results (spec §5).
        container.addView(sectionLabel(getString(R.string.env_compatibility)))
        report.checks.forEach { check ->
            container.addView(TextView(this).apply {
                text = "${check.passedText} ${check.label}" +
                    (if (check.detail.isNotBlank()) " — ${check.detail}" else "")
                textSize = 14f
                setPadding(dp(8), dp(2), 0, dp(2))
            })
        }

        // Variant selection (spec §9) — default preselected.
        if (variants.size > 1) {
            container.addView(sectionLabel(getString(R.string.env_variants)))
            variants.forEach { variant ->
                container.addView(variantRow(variant))
                container.addView(TextView(this).apply {
                    text = "${variant.description} · ~${SetupFormat.bytes(variant.downloadBytesEstimate)} download"
                    textSize = 13f
                    setPadding(dp(24), 0, 0, dp(6))
                })
            }
            selectedVariant = defaultVariant
        } else {
            selectedVariant = defaultVariant
        }

        // Storage check (spec §6) — real numbers.
        selectedVariant?.let { variant -> addStorageSection(variant) }

        // Actions.
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val continueBtn = Button(this).apply {
            text = getString(R.string.env_continue)
            setOnClickListener { confirmAndInstall(provider) }
        }
        actions.addView(continueBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        container.addView(actions)
    }

    private fun addStorageSection(variant: EnvironmentVariant) {
        val app = application as com.crossberry.noxs.NoxsApplication
        val usable = app.environments.environmentsRoot.usableSpace
        val plan = StoragePlan.compute(variant.downloadBytesEstimate, variant.extractedBytesEstimate)
        val check = StorageCalculator.check(plan, usable)

        container.addView(sectionLabel(getString(R.string.env_storage)))
        container.addView(TextView(this).apply {
            text = if (check.enough) {
                getString(
                    R.string.env_storage_ok,
                    SetupFormat.bytes(plan.totalRequired), SetupFormat.bytes(usable)
                )
            } else {
                getString(
                    R.string.env_storage_insufficient,
                    SetupFormat.bytes(plan.totalRequired), SetupFormat.bytes(usable)
                )
            }
            textSize = 14f
            setPadding(dp(8), 0, 0, dp(8))
        })
    }

    private fun confirmAndInstall(provider: com.crossberry.noxs.environments.EnvironmentProvider) {
        val variant = selectedVariant ?: return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.env_confirm_title, provider.displayName))
            .setMessage(getString(R.string.env_confirm_message, provider.displayName, variant.name))
            .setPositiveButton(getString(R.string.env_install)) { _, _ ->
                startActivity(
                    android.content.Intent(this, EnvironmentInstallActivity::class.java)
                        .putExtra(EnvironmentInstallActivity.EXTRA_PROVIDER_ID, provider.id)
                        .putExtra(EnvironmentInstallActivity.EXTRA_VARIANT_ID, variant.id)
                )
                finish()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------ widgets

    private fun detailRow(label: String, value: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(4), 0, dp(4))
        addView(TextView(this@EnvironmentPickerActivity).apply {
            text = label
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(TextView(this@EnvironmentPickerActivity).apply {
            text = value
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
        })
    }

    private fun variantRow(variant: EnvironmentVariant): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val name = TextView(this).apply {
            text = (if (variant.isDefault) "★ " else "○ ") + variant.name
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8), dp(6), 0, dp(2))
        }
        row.addView(name)
        row.setOnClickListener {
            selectedProviderId?.let { pid ->
                val provider = (application as com.crossberry.noxs.NoxsApplication)
                    .environments.providers.firstOrNull { it.id == pid }
                provider?.let { showDetails(it) }
            }
        }
        return row
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(14), 0, dp(6))
    }

    private fun badge(text: String, color: Int): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(0xFFFFFFFF.toInt())
        background = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(10).toFloat()
        }
        setPadding(dp(8), dp(2), dp(8), dp(2))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(4), 0, 0, 0) }
    }

    private fun cardBackground(): GradientDrawable = GradientDrawable().apply {
        setColor(0x1F888888)
        cornerRadius = dp(12).toFloat()
    }

    private fun pmShortLabel(provider: com.crossberry.noxs.environments.EnvironmentProvider): String = when {
        provider.capabilities().contains(com.crossberry.noxs.environments.model.EnvironmentCapability.PACKAGE_MANAGER) &&
            provider is RootfsTarballProvider -> provider.distro.packageManager.name.lowercase()
        provider.capabilities().contains(com.crossberry.noxs.environments.model.EnvironmentCapability.PACKAGE_MANAGER) -> "apt"
        else -> getString(R.string.env_none)
    }

    private fun runtimeLabel(provider: com.crossberry.noxs.environments.EnvironmentProvider): String =
        when (provider) {
            is RootfsTarballProvider -> when (provider.distro.family) {
                com.crossberry.noxs.environments.model.EnvironmentFamily.ARCH -> "proot Linux userspace (pacman)"
                else -> "proot Linux userspace (rootless)"
            }
            else -> if (provider.id == "termux") "Termux external integration" else "proot Linux userspace"
        }

    private fun levelColor(level: CompatibilityLevel): Int = when (level) {
        CompatibilityLevel.SUPPORTED -> COLOR_OK
        CompatibilityLevel.LIMITED -> COLOR_WARN
        CompatibilityLevel.UNSUPPORTED -> COLOR_BAD
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val COLOR_OK = 0xFF2E7D32.toInt()
        private const val COLOR_WARN = 0xFFF9A825.toInt()
        private const val COLOR_BAD = 0xFFC62828.toInt()
        private const val COLOR_RECOMMEND = 0xFF6A1B9A.toInt()
        private const val COLOR_NEUTRAL = 0xFF455A64.toInt()
    }
}
