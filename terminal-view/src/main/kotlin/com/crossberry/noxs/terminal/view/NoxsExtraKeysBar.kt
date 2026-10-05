/*
 * Noxs terminal-view — two-row, Termux-style terminal key strip with modifier
 * latches. Core navigation keys stay evenly spaced; legacy quick keys remain
 * reachable by horizontal scrolling the second row.
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
        setBackgroundColor(Color.BLACK)
        setPadding(0, dp(3), 0, dp(3))
        addView(firstRowScroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(secondRowScroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        // Primary keys mirror the compact two-row terminal layout in the
        // reference screenshot. Each group of seven fills the available width.
        addKey(firstRow, "ESC") { terminalView?.sendBytes(byteArrayOf(0x1b)) }
        addKey(firstRow, "/") { terminalView?.sendBytes("/".toByteArray()) }
        addKey(firstRow, "-") { terminalView?.sendBytes("-".toByteArray()) }
        addKey(firstRow, "HOME") { terminalView?.sendSpecialKey(KeyEvent.KEYCODE_MOVE_HOME) }
        addKey(firstRow, "↑") { terminalView?.sendSpecialKey(KeyEvent.KEYCODE_DPAD_UP) }
        addKey(firstRow, "END") { terminalView?.sendSpecialKey(KeyEvent.KEYCODE_MOVE_END) }
        addKey(firstRow, "PGUP") { terminalView?.sendSpecialKey(KeyEvent.KEYCODE_PAGE_UP) }

        addKey(secondRow, "⇥") { terminalView?.sendSpecialKey(KeyEvent.KEYCODE_TAB) }
        addLatch(secondRow, "CTRL")
        addLatch(secondRow, "ALT")
        addKey(secondRow, "←") { terminalView?.sendSpecialKey(KeyEvent.KEYCODE_DPAD_LEFT) }
        addKey(secondRow, "↓") { terminalView?.sendSpecialKey(KeyEvent.KEYCODE_DPAD_DOWN) }
        addKey(secondRow, "→") { terminalView?.sendSpecialKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
        addKey(secondRow, "PGDN") { terminalView?.sendSpecialKey(KeyEvent.KEYCODE_PAGE_DOWN) }

        // Keep the prior convenience keys without crowding the seven main
        // columns; they are available by swiping the lower row horizontally.
        addKey(secondRow, "^C") { terminalView?.sendBytes(byteArrayOf(0x03)) }
        addKey(secondRow, "^L") { terminalView?.sendBytes(byteArrayOf(0x0c)) }
        addKey(secondRow, "|") { terminalView?.sendBytes("|".toByteArray()) }
        addKey(secondRow, "~") { terminalView?.sendBytes("~".toByteArray()) }
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
        val keyWidth = ((w - paddingLeft - paddingRight) / 7).coerceAtLeast(dp(42))
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
        row.addView(button, LinearLayout.LayoutParams(dp(48), LayoutParams.MATCH_PARENT))
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
        row.addView(button, LinearLayout.LayoutParams(dp(48), LayoutParams.MATCH_PARENT))
    }

    private fun makeButton(label: String, latched: Boolean): Button = Button(context).apply {
        text = label
        textSize = if (label.length >= 4) 13f else 15f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        isAllCaps = false
        isSingleLine = true
        gravity = Gravity.CENTER
        setTextColor(if (latched) Color.BLACK else Color.WHITE)
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
            button.setTextColor(if (tv.ctrlLatch) Color.BLACK else Color.WHITE)
        }
        latchButtons["ALT"]?.let { button ->
            button.background = keyBackground(tv.altLatch)
            button.setTextColor(if (tv.altLatch) Color.BLACK else Color.WHITE)
        }
    }

    private fun keyBackground(latched: Boolean): RippleDrawable {
        val shape = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(if (latched) Color.WHITE else Color.TRANSPARENT)
        }
        return RippleDrawable(ColorStateList.valueOf(0x33ffffff), shape, null)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
