/*
 * Noxs terminal-view — original implementation.
 * Extra-keys bar: ESC / CTRL / ALT / TAB / arrows / shortcuts, with CTRL+ALT
 * latches that apply to the next key press.
 */
package com.noxs.linux.terminal.view

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout

class NoxsExtraKeysBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : HorizontalScrollView(context, attrs) {

    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(4), dp(8), dp(4))
    }

    private val latchButtons = mutableMapOf<String, Button>()
    var terminalView: TerminalView? = null

    init {
        isHorizontalScrollBarEnabled = false
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        setBackgroundColor(0xff1a1f27.toInt())
        addKey("ESC") { terminalView?.sendBytes(byteArrayOf(0x1b)) }
        addKey("TAB") { terminalView?.sendBytes(byteArrayOf(0x09)) }
        addLatch("CTRL")
        addLatch("ALT")
        addKey("▲") { sendArrow('A', KeyEvent.KEYCODE_DPAD_UP) }
        addKey("▼") { sendArrow('B', KeyEvent.KEYCODE_DPAD_DOWN) }
        addKey("◀") { sendArrow('D', KeyEvent.KEYCODE_DPAD_LEFT) }
        addKey("▶") { sendArrow('C', KeyEvent.KEYCODE_DPAD_RIGHT) }
        addKey("^C") { terminalView?.session?.write(byteArrayOf(0x03)) }
        addKey("^L") { terminalView?.session?.write(byteArrayOf(0x0c)) }
        addKey("-") { terminalView?.sendBytes("-".toByteArray()) }
        addKey("/") { terminalView?.sendBytes("/".toByteArray()) }
        addKey("|") { terminalView?.sendBytes("|".toByteArray()) }
        addKey("~") { terminalView?.sendBytes("~".toByteArray()) }
    }

    private fun sendArrow(letter: Char, keyCode: Int) {
        val tv = terminalView ?: return
        val app = tv.session?.emulator?.appCursorKeys ?: false
        tv.sendBytes(if (app) "\u001bO$letter".toByteArray() else "\u001b[$letter".toByteArray())
    }

    private fun addKey(label: String, action: () -> Unit) {
        row.addView(Button(context).apply {
            text = label
            textSize = 12f
            typeface = Typeface.MONOSPACE
            isAllCaps = false
            setTextColor(0xffe6e6e6.toInt())
            background = keyBackground(false)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(10), 0, dp(10), 0)
            stateListAnimator = null
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34)).apply {
            marginEnd = dp(5)
        })
    }

    private fun addLatch(label: String) {
        val btn = Button(context).apply {
            text = label
            textSize = 12f
            typeface = Typeface.MONOSPACE
            isAllCaps = false
            setTextColor(0xffe6e6e6.toInt())
            background = keyBackground(latched = false)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(10), 0, dp(10), 0)
            stateListAnimator = null
            setOnClickListener {
                when (label) {
                    "CTRL" -> terminalView?.let { it.ctrlLatch = !it.ctrlLatch }
                    "ALT" -> terminalView?.let { it.altLatch = !it.altLatch }
                }
                refreshLatches()
            }
        }
        latchButtons[label] = btn
        row.addView(btn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34)).apply {
            marginEnd = dp(5)
        })
    }

    fun refreshLatches() {
        val tv = terminalView ?: return
        latchButtons["CTRL"]?.background = keyBackground(tv.ctrlLatch)
        latchButtons["CTRL"]?.setTextColor(if (tv.ctrlLatch) Color.BLACK else 0xffe6e6e6.toInt())
        latchButtons["ALT"]?.background = keyBackground(tv.altLatch)
        latchButtons["ALT"]?.setTextColor(if (tv.altLatch) Color.BLACK else 0xffe6e6e6.toInt())
    }

    private fun keyBackground(latched: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(6).toFloat()
        setColor(if (latched) 0xff3ddc84.toInt() else 0xff232a35.toInt())
        setStroke(dp(1), 0xff2f3946.toInt())
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
