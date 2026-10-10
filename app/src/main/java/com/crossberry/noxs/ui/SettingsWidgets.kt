/*
 * Noxs — original implementation.
 * Reusable settings components (settings redesign spec §1, §5): rounded
 * cards with subtle borders, colored icon badges, one uniform row anatomy
 * (icon · title/description · trailing switch/value/chevron) and green
 * active switches. Every interactive row keeps a >=48dp touch target, a
 * ripple pressed state and content descriptions for accessibility.
 * Built with the platform SDK only — no new dependencies.
 */
package com.crossberry.noxs.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.crossberry.noxs.R

object SettingsWidgets {

    // ------------------------------------------------------------- palette
    // Settings design system (spec §1) — mirrors res/values/colors.xml.
    private val BG = 0xFF050B14.toInt()
    private val CARD = 0xFF0D1725.toInt()
    private val ELEVATED = 0xFF162335.toInt()
    private val ACCENT = 0xFF16D66A.toInt()
    private val TEXT = 0xFFF5F7FA.toInt()
    private val TEXT_SECONDARY = 0xFFA5B1C2.toInt()
    private val DIVIDER = 0xFF243347.toInt()
    private val DISABLED = 0xFF626B78.toInt()
    private val ERROR = 0xFFFF5B67.toInt()
    private val RIPPLE = 0x24FFFFFF

    const val CARD_RADIUS_DP = 16
    const val BADGE_RADIUS_DP = 10
    const val CHIP_RADIUS_DP = 9

    fun dp(context: Context, value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics).toInt()

    // ------------------------------------------------------------ sections

    /** Section heading rendered OUTSIDE the cards (spec §3, screenshot). */
    fun sectionHeader(context: Context, title: String): TextView = TextView(context).apply {
        text = title
        setTextColor(TEXT_SECONDARY)
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(context, 4), dp(context, 18), dp(context, 4), dp(context, 8))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    /** Rounded card container; rows are stacked without dividers (screenshot style). */
    fun card(context: Context, vararg rows: View): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(context, CARD_RADIUS_DP).toFloat()
                setColor(CARD)
                setStroke(dp(context, 1), DIVIDER)
            }
        }
        rows.forEach { card.addView(it) }
        return card
    }

    // --------------------------------------------------------------- atoms

    /**
     * Rounded colored icon badge (screenshot: colored square + white glyph).
     * Decorative — marked unimportant for accessibility; the row itself
     * carries the content description.
     */
    fun iconBadge(context: Context, iconRes: Int, tintColor: Int): LinearLayout =
        LinearLayout(context).apply {
            val size = dp(context, 38)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginEnd = dp(context, 14)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(context, BADGE_RADIUS_DP).toFloat()
                setColor(tintColor)
            }
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(ImageView(context).apply {
                val glyph = dp(context, 20)
                setImageResource(iconRes)
                imageTintList = ColorStateList.valueOf(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(glyph, glyph)
            })
        }

    /** Current-value chip (e.g. "8", "256", "4096") used before a chevron. */
    fun valueChip(context: Context, value: String): TextView = TextView(context).apply {
        text = value
        setTextColor(TEXT)
        textSize = 14f
        typeface = Typeface.MONOSPACE
        gravity = Gravity.CENTER
        minWidth = dp(context, 52)
        setPadding(dp(context, 12), dp(context, 6), dp(context, 12), dp(context, 6))
        background = GradientDrawable().apply {
            cornerRadius = dp(context, CHIP_RADIUS_DP).toFloat()
            setColor(ELEVATED)
            setStroke(dp(context, 1), DIVIDER)
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER_VERTICAL }
    }

    private fun chevron(context: Context): ImageView = ImageView(context).apply {
        setImageResource(R.drawable.ic_settings_chevron)
        imageTintList = ColorStateList.valueOf(TEXT_SECONDARY)
        val size = dp(context, 20)
        layoutParams = LinearLayout.LayoutParams(size, size).apply {
            gravity = Gravity.CENTER_VERTICAL
            marginStart = dp(context, 10)
        }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun rowBase(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10))
        minimumHeight = dp(context, 56)
        isClickable = true
        isFocusable = true
    }

    private fun ripple(context: Context, fill: Int = Color.TRANSPARENT): RippleDrawable =
        RippleDrawable(
            ColorStateList.valueOf(RIPPLE),
            GradientDrawable().apply {
                cornerRadius = dp(context, CARD_RADIUS_DP).toFloat()
                setColor(fill)
            },
            null
        )

    private fun textColumn(context: Context, title: String, description: String?, weight: Float = 1f): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
            addView(TextView(context).apply {
                text = title
                setTextColor(TEXT)
                textSize = 15.5f
                setTypeface(typeface, Typeface.BOLD)
                // Long titles wrap under the icon column without colliding
                // with the trailing controls (spec §5).
            })
            if (!description.isNullOrBlank()) {
                addView(TextView(context).apply {
                    text = description
                    setTextColor(TEXT_SECONDARY)
                    textSize = 12.5f
                    setPadding(0, dp(context, 2), 0, 0)
                })
            }
        }

    private fun switch(context: Context, checked: Boolean, contentDescription: String): SwitchCompat =
        SwitchCompat(context).apply {
            isChecked = checked
            contentDescription = contentDescription
            // Green active switch, gray inactive track (spec §1).
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(ACCENT, DISABLED)
            )
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.WHITE, TEXT_SECONDARY)
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_VERTICAL; marginStart = dp(context, 8) }
        }

    // --------------------------------------------------------------- rows

    /** Switch row: icon · title/description · green switch. Whole row toggles. */
    fun switchRow(
        context: Context,
        iconRes: Int,
        iconTint: Int,
        title: String,
        description: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit
    ): View {
        val row = rowBase(context)
        row.background = ripple(context)
        row.contentDescription = "$title — ${if (checked) "on" else "off"}"
        row.addView(iconBadge(context, iconRes, iconTint))
        val column = textColumn(context, title, description)
        row.addView(column)
        val sw = switch(context, checked, title)
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        sw.setOnCheckedChangeListener { _, isChecked ->
            row.contentDescription = "$title — ${if (isChecked) "on" else "off"}"
            onChange(isChecked)
        }
        return row
    }

    /**
     * Navigation row: icon · title/description · optional value chip ·
     * chevron. Blank [value] renders the chevron only. Tap opens [onClick].
     */
    fun navRow(
        context: Context,
        iconRes: Int,
        iconTint: Int,
        title: String,
        description: String,
        value: String = "",
        onClick: () -> Unit
    ): View {
        val row = rowBase(context)
        row.background = ripple(context)
        row.contentDescription = if (value.isBlank()) title else "$title — $value"
        row.addView(iconBadge(context, iconRes, iconTint))
        row.addView(textColumn(context, title, description))
        if (value.isNotBlank()) row.addView(valueChip(context, value))
        row.addView(chevron(context))
        row.setOnClickListener { onClick() }
        return row
    }

    /** Read-only info row (e.g. dark theme note): no ripple, not clickable. */
    fun infoRow(
        context: Context,
        iconRes: Int,
        iconTint: Int,
        title: String,
        description: String,
        value: String
    ): View {
        val row = rowBase(context)
        row.isClickable = false
        row.isFocusable = false
        row.background = ripple(context, fill = Color.TRANSPARENT)
        row.addView(iconBadge(context, iconRes, iconTint))
        row.addView(textColumn(context, title, description))
        if (value.isNotBlank()) {
            row.addView(TextView(context).apply {
                text = value
                setTextColor(ACCENT)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(context, 10), 0, 0, 0)
            })
        }
        return row
    }

    /** Shared color accessor for host activities (dialogs, headers). */
    fun color(context: Context, res: Int): Int = ContextCompat.getColor(context, res)

    // ------------------------------------------------------------- legacy
    // API kept for the terminal settings pages (they render rows directly
    // into their own stack without icon badges). Same signatures as before;
    // visuals follow the new palette.

    /** Legacy switch row (no icon badge): title + description + switch. */
    fun switchRow(
        context: Context,
        title: String,
        description: String,
        checked: Boolean,
        contentDescription: String = title,
        onChange: (Boolean) -> Unit
    ): View {
        val row = rowBase(context)
        row.background = ripple(context)
        row.contentDescription = "$contentDescription — ${if (checked) "on" else "off"}"
        val column = textColumn(context, title, description)
        row.addView(column)
        val sw = switch(context, checked, contentDescription)
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        sw.setOnCheckedChangeListener { _, isChecked ->
            row.contentDescription = "$contentDescription — ${if (isChecked) "on" else "off"}"
            onChange(isChecked)
        }
        return row
    }

    /**
     * Legacy slider row: title, exact current value badge (long-press sends
     * the -1 reset request, preserved) and live min/max bounds.
     */
    fun sliderRow(
        context: Context,
        title: String,
        description: String,
        min: Int,
        max: Int,
        step: Int,
        value: Int,
        format: (Int) -> String,
        contentDescription: String = title,
        onChange: (Int) -> Unit
    ): View {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 12), dp(context, 20), dp(context, 12))
            minimumHeight = dp(context, 64)
        }
        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(context).apply {
            text = title
            setTextColor(TEXT)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(context).apply {
            text = format(value.coerceIn(min, max))
            setTextColor(ACCENT)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setPadding(dp(context, 10), dp(context, 4), dp(context, 10), dp(context, 4))
            background = GradientDrawable().apply {
                cornerRadius = dp(context, CHIP_RADIUS_DP).toFloat()
                setColor(ELEVATED)
                setStroke(dp(context, 1), DIVIDER)
            }
            isLongClickable = true
            setOnLongClickListener {
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                onChange(-1) // -1 = reset request; caller maps to its default
                true
            }
        })
        column.addView(header)
        if (description.isNotBlank()) {
            column.addView(TextView(context).apply {
                text = description
                setTextColor(TEXT_SECONDARY)
                textSize = 12.5f
                setPadding(0, dp(context, 2), 0, dp(context, 6))
            })
        }
        column.addView(android.widget.SeekBar(context).apply {
            max = (max - min) / step
            progress = (value.coerceIn(min, max) - min) / step
            contentDescription = contentDescription
            setPadding(dp(context, 8), 0, dp(context, 8), 0)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val next = (min + progress * step).coerceIn(min, max)
                    onChange(next)
                }
                override fun onStartTrackingTouch(bar: android.widget.SeekBar) {}
                override fun onStopTrackingTouch(bar: android.widget.SeekBar) {}
            })
        })
        val bounds = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        bounds.addView(TextView(context).apply {
            text = format(min)
            setTextColor(TEXT_SECONDARY); textSize = 11f; typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        bounds.addView(TextView(context).apply {
            text = format(max)
            setTextColor(TEXT_SECONDARY); textSize = 11f; typeface = Typeface.MONOSPACE
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        column.addView(bounds)
        return column
    }

    /** Legacy selector row: shows current value, opens a single-choice dialog. */
    fun selectorRow(
        context: Context,
        title: String,
        description: String,
        options: List<String>,
        selectedIndex: Int,
        contentDescription: String = title,
        onPick: (Int) -> Unit
    ): View {
        val row = rowBase(context)
        row.background = ripple(context)
        row.contentDescription = "$contentDescription — ${options.getOrNull(selectedIndex) ?: ""}"
        row.addView(textColumn(context, title, description))
        row.addView(TextView(context).apply {
            text = options.getOrNull(selectedIndex) ?: ""
            setTextColor(TEXT_SECONDARY)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setPadding(dp(context, 12), 0, 0, 0)
        })
        row.addView(chevron(context))
        row.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle(title)
                .setSingleChoiceItems(options.toTypedArray(), selectedIndex) { dialog, which ->
                    dialog.dismiss()
                    onPick(which)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        return row
    }

    /** Legacy navigation row: current value + chevron; tap opens a sub page. */
    fun valueRow(
        context: Context,
        title: String,
        description: String,
        value: String,
        onClick: () -> Unit
    ): View {
        val row = rowBase(context)
        row.background = ripple(context)
        row.contentDescription = if (value.isBlank()) title else "$title — $value"
        row.addView(textColumn(context, title, description))
        if (value.isNotBlank()) row.addView(TextView(context).apply {
            text = value
            setTextColor(TEXT_SECONDARY)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setPadding(dp(context, 12), 0, 0, 0)
        })
        row.addView(chevron(context))
        row.setOnClickListener { onClick() }
        return row
    }

    /** Legacy action row (e.g. Reset terminal settings). */
    fun actionRow(
        context: Context,
        title: String,
        description: String,
        color: Int = TEXT,
        onClick: () -> Unit
    ): View {
        val row = rowBase(context)
        row.background = ripple(context)
        row.contentDescription = title
        val column = textColumn(context, title, description)
        (column.getChildAt(0) as TextView).setTextColor(color)
        row.addView(column)
        row.setOnClickListener { onClick() }
        return row
    }

    /** Groups rows into one vertical block (legacy sub-page helper). */
    fun group(context: Context, vararg views: View): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, dp(context, 8))
        views.forEach { addView(it) }
    }
}
