/*
 * Noxs terminal-view — two-row, Termux-style terminal key strip with modifier
 * latches. Row layout: ESC TAB / - HOME ↑ END and CTRL ALT ← ↓ → PGUP PGDN;
 * legacy quick keys remain reachable by horizontal scrolling the first row.
 */
package com.crossberry.noxs.terminal.view

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout

class NoxsExtraKeysBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private companion object {
        // Noxs design system (kept literal: this module has no app resources).
        private val PANEL = 0xFF111111.toInt()
        private val KEY = 0xFF222222.toInt()
        private val KEY_BORDER = 0xFF303030.toInt()
        private val KEY_TEXT = 0xFFFFFFFF.toInt()
        private val ACCENT = 0xFF20D866.toInt()
        private val ON_ACCENT = 0xFF04150A.toInt()
        private const val CORE_COLUMNS = 7
        private const val KEY_GAP_DP = 4
    }

    private val firstRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val secondRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val firstRowScroll = horizontalRow(firstRow)
    private val secondRowScroll = horizontalRow(secondRow)
    private val coreButtons = mutableListOf<Button>()
    private val latchButtons = mutableMapOf<String, Button>()

    var terminalView: TerminalView? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(PANEL)
        setPadding(dp(4), dp(4), dp(4), dp(4))
        addView(firstRowScroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(secondRowScroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        // Row 1 — required core keys; convenience keys scroll into view.
        addKey(firstRow, "ESC") { terminalView?.sendBytes(byteArrayOf(0x1b)) }
        addKey(firstRow, "TAB") { terminalView?.sendSpecialKey(KeyEvent.KEYCODE_TAB) }
        addKey(firstRow, "/") { terminalView?.sendBytes("/".toByteArray()) }
        addKey(firstRow, "-") { terminalView?.sendBytes("-".toByteArray()) }
        addKey(firstRow, "HOME") { terminalView?.sendSpecialKey(KeyEvent.KEYCODE_MOVE_HOME) }
        addKey(firstRow, "↑") { terminalView?.sendSpecialKey(KeyEvent.KEYCODE_DPAD_UP) }
        addKey(firstRow, "END") { terminalView?.sendSpecialKey(KeyEvent.KEYCODE_MOVE_END) }
        addKey(firstRow, "^C") { terminalView?.sendBytes(byteArrayOf(0x03)) }
        addKey(firstRow, "^L") { terminalView?.sendBytes(byteArrayOf(0x0c)) }
        addKey(firstRow, "|") { terminalView?.sendBytes("|".toByteArray()) }
        addKey(firstRow, "~") { terminalView?.sendBytes("~".toByteArray()) }

        // Row 2 — modifiers latch for the next key press.
        addLatch(secondRow, "CTRL")
        addLatch(secondRow, "ALT")
        addKey(secondRow, "←") { terminalView?.sendSpecialKey(KeyEvent.KEYCODE_DPAD_LEFT) }
        addKey(secondRow, "↓") { terminalView?.sendSpecialKey(KeyEvent.KEYCODE_DPAD_DOWN) }
        addKey(secondRow, "→") { terminalView?.sendSpecialKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
        addKey(secondRow, "PGUP") { terminalView?.sendSpecialKey(KeyEvent.KEYCODE_PAGE_UP) }
        addKey(secondRow, "PGDN") { terminalView?.sendSpecialKey(KeyEvent.KEYCODE_PAGE_DOWN) }
    }

    private fun horizontalRow(row: LinearLayout) = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        isFillViewport = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(row, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0) return
        val gaps = dp(KEY_GAP_DP) * (CORE_COLUMNS - 1)
        val keyWidth = ((w - paddingLeft - paddingRight - gaps) / CORE_COLUMNS).coerceAtLeast(dp(44))
        coreButtons.forEach { button ->
            val lp = button.layoutParams as? LinearLayout.LayoutParams ?: return@forEach
            if (lp.width != keyWidth) {
                lp.width = keyWidth
                button.layoutParams = lp
            }
        }
    }

    private fun addKey(row: LinearLayout, label: String, action: () -> Unit): Button {
        val button = makeButton(label, latched = false).apply {
            setOnClickListener { action() }
        }
        coreButtons += button
        attach(row, button)
        return button
    }

    private fun addLatch(row: LinearLayout, label: String) {
        val button = makeButton(label, latched = false).apply {
            setOnClickListener {
                when (label) {
                    "CTRL" -> terminalView?.let { it.ctrlLatch = !it.ctrlLatch }
                    "ALT" -> terminalView?.let { it.altLatch = !it.altLatch }
                }
                refreshLatches()
            }
        }
        latchButtons[label] = button
        coreButtons += button
        attach(row, button)
    }

    private fun attach(row: LinearLayout, button: Button) {
        row.addView(button, LinearLayout.LayoutParams(dp(48), LayoutParams.MATCH_PARENT).apply {
            marginEnd = dp(KEY_GAP_DP)
        })
    }

    private fun makeButton(label: String, latched: Boolean): Button = Button(context).apply {
        text = label
        textSize = if (label.length >= 4) 12.5f else 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        isAllCaps = false
        isSingleLine = true
        gravity = Gravity.CENTER
        setTextColor(if (latched) ON_ACCENT else KEY_TEXT)
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(0, 0, 0, 0)
        stateListAnimator = null
        backgroundTintList = null
        background = keyBackground(latched)
    }

    fun refreshLatches() {
        val tv = terminalView ?: return
        latchButtons["CTRL"]?.let { button ->
            button.background = keyBackground(tv.ctrlLatch)
            button.setTextColor(if (tv.ctrlLatch) ON_ACCENT else KEY_TEXT)
        }
        latchButtons["ALT"]?.let { button ->
            button.background = keyBackground(tv.altLatch)
            button.setTextColor(if (tv.altLatch) ON_ACCENT else KEY_TEXT)
        }
    }

    private fun keyBackground(latched: Boolean): RippleDrawable {
        val shape = GradientDrawable().apply {
            cornerRadius = dp(7).toFloat()
            setColor(if (latched) ACCENT else KEY)
            if (!latched) setStroke(dp(1), KEY_BORDER)
        }
        return RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape, null)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
