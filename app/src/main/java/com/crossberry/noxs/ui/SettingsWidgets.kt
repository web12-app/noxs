/*
 * Noxs — original implementation.
 * Reusable settings rows built in code (Noxs console-styled: dark surface,
 * monospace accents, grouped sections). Every row keeps a ≥48dp touch target,
 * a readable label, a description where useful and content descriptions for
 * accessibility. Sliders clamp, show the exact current value and offer
 * reset-to-default via long press.
 */
package com.crossberry.noxs.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat

object SettingsWidgets {

    // Noxs design system palette (mirrors res/values/colors.xml)
    private const val TEXT = 0xffffffff.toInt()
    private const val TEXT_DIM = 0xffaaaaaa.toInt()
    private const val ACCENT = 0xff20d866.toInt()
    private const val SURFACE = 0xff111111.toInt()
    private const val SURFACE_ALT = 0xff222222.toInt()

    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    fun sectionHeader(context: Context, title: String): TextView = TextView(context).apply {
        text = title
        setTextColor(ACCENT)
        textSize = 12f
        typeface = Typeface.MONOSPACE
        setPadding(dp(context, 20), dp(context, 18), dp(context, 20), dp(context, 6))
    }

    private fun rowContainer(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(context, 20), dp(context, 12), dp(context, 20), dp(context, 12))
        minimumHeight = dp(context, 56)
        background = SelectorDrawable.background(context)
    }

    private fun titleView(context: Context, title: String, description: String?): LinearLayout {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val titleText = TextView(context).apply {
            text = title
            setTextColor(TEXT)
            textSize = 15f
        }
        column.addView(titleText)
        if (!description.isNullOrBlank()) {
            column.addView(TextView(context).apply {
                text = description
                setTextColor(TEXT_DIM)
                textSize = 12.5f
                setPadding(0, dp(context, 2), 0, 0)
            })
        }
        return column
    }

    private fun valueView(context: Context, value: String): TextView = TextView(context).apply {
        text = value
        setTextColor(TEXT_DIM)
        textSize = 13f
        typeface = Typeface.MONOSPACE
        setPadding(dp(context, 12), 0, 0, 0)
    }

    /** Switch row: title + description left, switch right. */
    fun switchRow(
        context: Context,
        title: String,
        description: String,
        checked: Boolean,
        contentDescription: String = title,
        onChange: (Boolean) -> Unit
    ): View {
        val row = rowContainer(context)
        row.isClickable = true
        row.isFocusable = true
        row.contentDescription = "$contentDescription — ${if (checked) "on" else "off"}"
        val column = titleView(context, title, description)
        column.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(column)
        val switch = androidx.appcompat.widget.SwitchCompat(context)
        switch.isChecked = checked
        switch.contentDescription = contentDescription
        switch.setOnCheckedChangeListener { _, isChecked ->
            row.contentDescription = "$contentDescription — ${if (isChecked) "on" else "off"}"
            onChange(isChecked)
        }
        row.setOnClickListener { switch.isChecked = !switch.isChecked }
        row.addView(switch)
        return row
    }

    /**
     * Slider row: title, min/max labels, exact current value, reset on long
     * press of the value badge.
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
        val titleText = TextView(context).apply {
            text = title
            setTextColor(TEXT)
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(titleText)
        val valueText = TextView(context).apply {
            text = format(value.coerceIn(min, max))
            setTextColor(ACCENT)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setPadding(dp(context, 10), dp(context, 4), dp(context, 10), dp(context, 4))
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 6).toFloat()
                setColor(SURFACE_ALT)
            }
            isLongClickable = true
            setOnLongClickListener {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onChange(-1) // -1 = reset request; caller maps to its default
                true
            }
        }
        header.addView(valueText)
        column.addView(header)
        if (description.isNotBlank()) {
            column.addView(TextView(context).apply {
                text = description
                setTextColor(TEXT_DIM)
                textSize = 12.5f
                setPadding(0, dp(context, 2), 0, dp(context, 6))
            })
        }
        val seek = SeekBar(context)
        seek.max = (max - min) / step
        seek.progress = (value.coerceIn(min, max) - min) / step
        seek.contentDescription = contentDescription
        seek.setPadding(dp(context, 8), 0, dp(context, 8), 0)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val next = (min + progress * step).coerceIn(min, max)
                valueText.text = format(next)
                onChange(next)
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })
        column.addView(seek)
        val bounds = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        bounds.addView(TextView(context).apply {
            text = format(min)
            setTextColor(TEXT_DIM); textSize = 11f; typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        bounds.addView(TextView(context).apply {
            text = format(max)
            setTextColor(TEXT_DIM); textSize = 11f; typeface = Typeface.MONOSPACE
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        column.addView(bounds)
        return column
    }

    /** Selector row: shows current value, opens a single-choice dialog on tap. */
    fun selectorRow(
        context: Context,
        title: String,
        description: String,
        options: List<String>,
        selectedIndex: Int,
        contentDescription: String = title,
        onPick: (Int) -> Unit
    ): View {
        val row = rowContainer(context)
        row.isClickable = true
        row.isFocusable = true
        row.contentDescription = "$contentDescription — ${options.getOrNull(selectedIndex) ?: ""}"
        val column = titleView(context, title, description)
        column.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(column)
        row.addView(valueView(context, options.getOrNull(selectedIndex) ?: ""))
        row.addView(TextView(context).apply {
            text = "  ›"
            setTextColor(TEXT_DIM)
            textSize = 14f
            typeface = Typeface.MONOSPACE
        })
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

    /** Read-only info row (current values, debug facts). */
    fun infoRow(context: Context, title: String, description: String, value: String): View {
        val row = rowContainer(context)
        val column = titleView(context, title, description)
        column.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(column)
        row.addView(valueView(context, value))
        return row
    }

    /** Navigation row: current value + chevron; tap opens a sub page. */
    fun valueRow(
        context: Context,
        title: String,
        description: String,
        value: String,
        onClick: () -> Unit
    ): View {
        val row = rowContainer(context)
        row.isClickable = true
        row.isFocusable = true
        row.contentDescription = "$title — $value"
        val column = titleView(context, title, description)
        column.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(column)
        row.addView(valueView(context, value))
        row.addView(TextView(context).apply {
            text = "  ›"
            setTextColor(TEXT_DIM)
            textSize = 14f
            typeface = Typeface.MONOSPACE
        })
        row.setOnClickListener { onClick() }
        return row
    }

    /** Action row (e.g. Reset terminal settings). */
    fun actionRow(context: Context, title: String, description: String, color: Int = TEXT, onClick: () -> Unit): View {
        val row = rowContainer(context)
        row.isClickable = true
        row.isFocusable = true
        row.contentDescription = title
        val column = titleView(context, title, description)
        column.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        (column.getChildAt(0) as TextView).setTextColor(color)
        row.addView(column)
        row.setOnClickListener { onClick() }
        return row
    }

    /** Simple selector used by the sub-pages to build group blocks. */
    fun group(context: Context, vararg views: View): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, dp(context, 8))
        views.forEach { addView(it) }
    }

    private object SelectorDrawable {
        fun background(context: Context): GradientDrawable = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
        }
    }
}

/** Wraps a color resource lookup so widgets degrade gracefully in previews. */
internal fun Context.noxsColor(res: Int): Int = ContextCompat.getColor(this, res)
