/*
 * Noxs terminal-view — original implementation.
 * Interactive Android terminal View: IME input, hardware keys, gestures
 * (scroll / copy / paste), resize propagation and renderer glue.
 */
package com.noxs.linux.terminal.view

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.ActionMode
import android.view.GestureDetector
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Scroller
import com.noxs.linux.terminal.emulator.KeyHandler
import com.noxs.linux.terminal.emulator.TerminalEmulator
import com.noxs.linux.terminal.emulator.TerminalSession

class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : android.view.View(context, attrs) {

    var session: TerminalSession? = null
        private set

    val renderer = TerminalRenderer()

    // Recomputed in updateSize(); NOT lazy — the first measurement would run
    // before onAttachedToWindow applied the real density scale.
    private var metrics = renderer.measure()
    private val scroller = Scroller(context)
    private var scrollRows = 0
    private var isFocusedVisual = false

    /** Latched modifiers from the extra-keys bar. */
    var ctrlLatch = false
    var altLatch = false

    var onScreenUpdated: (() -> Unit)? = null
    var onSessionResized: ((rows: Int, cols: Int) -> Unit)? = null

    private val clipboard by lazy { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    private val imm by lazy { context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            if (!scroller.isFinished) scroller.abortAnimation()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            requestFocus()
            showSoftInput()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            val emu = session?.emulator ?: return true
            val rowsScrolled = (dy / metrics.charHeight).toInt()
            if (rowsScrolled == 0) return true
            val maxScroll = emu.buffer.scrollbackSize
            scrollRows = (scrollRows + rowsScrolled).coerceIn(0, maxScroll)
            invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            scroller.fling(0, scrollRows, 0, -vy.toInt(), 0, 0, 0, (session?.emulator?.buffer?.scrollbackSize ?: 0))
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            startActionMode(callback, ActionMode.TYPE_FLOATING)
        }
    })

    private val callback = object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: android.view.Menu): Boolean {
            menu.add(0, 1, 0, "Copy screen")
            menu.add(0, 2, 1, "Paste")
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: android.view.Menu) = false

        override fun onActionItemClicked(mode: ActionMode, item: android.view.MenuItem): Boolean {
            when (item.itemId) {
                1 -> {
                    val text = session?.emulator?.screenText()?.joinToString("\n") ?: ""
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("noxs", text))
                }
                2 -> pasteFromClipboard()
            }
            mode.finish()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {}
    }

    fun attach(newSession: TerminalSession) {
        session = newSession
        scrollRows = 0
        renderer.scrollOffset = 0
        updateSize()
        invalidate()
    }

    fun setFontSizeSp(sp: Float) {
        renderer.fontSizeSp = sp
        updateSize()
        invalidate()
    }

    fun showSoftInput() {
        imm.showSoftInput(this, 0)
    }

    fun pasteFromClipboard() {
        val clip = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
        session?.write(session?.emulator?.paste(clip) ?: return)
        scrollToBottom()
    }

    fun scrollToBottom() {
        scrollRows = 0
        invalidate()
    }

    fun sendBytes(data: ByteArray) {
        session?.write(data)
        scrollToBottom()
    }

    override fun onDraw(canvas: Canvas) {
        val emu = session?.emulator ?: run {
            canvas.drawColor(0xff10141a.toInt())
            return
        }
        renderer.render(canvas, emu, metrics, scrollRows, isFocusedVisual, resources.displayMetrics.density)
        if (scrollRows == 0) renderer.drawCursor(canvas, emu, metrics)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP && scroller.isFinished) {
            // nothing extra
        }
        if (!scroller.isFinished) {
            scroller.computeScrollOffset()
            val max = session?.emulator?.buffer?.scrollbackSize ?: 0
            val newScroll = scroller.currY.coerceIn(0, max)
            if (newScroll != scrollRows) {
                scrollRows = newScroll
                invalidate()
            }
        }
        return handled
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateSize()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        isFocusedVisual = gainFocus
        invalidate()
    }

    private fun updateSize() {
        val m = renderer.measure().also { metrics = it }
        val w = width.takeIf { it > 0 } ?: 720
        val h = height.takeIf { it > 0 } ?: 1200
        val cols = (w / m.charWidth).toInt().coerceAtLeast(4)
        val rows = (h / m.charHeight).toInt().coerceAtLeast(2)
        session?.resize(rows, cols)
        onSessionResized?.invoke(rows, cols)
        invalidate()
    }

    override fun checkInputConnectionProxy(view: android.view.View): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (session == null) return null
        outAttrs.inputType = EditorInfo.TYPE_NULL // best compat with soft keyboards
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                sendBytes(text.toString().toByteArray(Charsets.UTF_8))
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength.coerceAtLeast(1)) {
                    sendBytes(byteArrayOf(0x7f))
                }
                return true
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                sendBytes(byteArrayOf('\r'.code.toByte()))
                return true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val emu = session?.emulator ?: return super.onKeyDown(keyCode, event)
        if (handleVolumeKeys(keyCode)) return true

        val meta = event.metaState
        val ctrl = ctrlLatch || meta and KeyEvent.META_CTRL_ON != 0
        val alt = altLatch || meta and KeyEvent.META_ALT_ON != 0
        val shift = meta and KeyEvent.META_SHIFT_ON != 0
        val mods = (if (ctrl) KeyHandler.MOD_CTRL else 0) or
            (if (alt) KeyHandler.MOD_ALT else 0) or
            (if (shift) KeyHandler.MOD_SHIFT else 0)

        // Ctrl+V paste (also Ctrl+Shift+V)
        if (ctrl && keyCode == KeyEvent.KEYCODE_V) {
            pasteFromClipboard()
            clearLatches()
            return true
        }

        // Arrow with modifiers → word jump
        KeyHandler.modifiedArrow(keyCode, ctrl, shift, emu.appCursorKeys)?.let {
            sendBytes(it)
            clearLatches()
            return true
        }

        KeyHandler.map(keyCode, mods, emu.appCursorKeys)?.let {
            sendBytes(it)
            clearLatches()
            return true
        }

        val chr = event.getUnicodeChar(meta)
        if (chr != 0) {
            if (ctrl) {
                // Control codes for punctuation (Ctrl+[ = ESC, Ctrl+Space = NUL…)
                sendBytes(byteArrayOf((chr and 0x1f).toByte()))
            } else {
                sendBytes(String(Character.toChars(chr)).toByteArray(Charsets.UTF_8))
            }
            clearLatches()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun handleVolumeKeys(keyCode: Int): Boolean {
        if (!volumeKeysEnabled) return false
        val emu = session?.emulator ?: return false
        val dir = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> 'A'
            KeyEvent.KEYCODE_VOLUME_DOWN -> 'B'
            else -> return false
        }
        sendBytes(if (emu.appCursorKeys) "\u001bO$dir".toByteArray() else "\u001b[$dir".toByteArray())
        return true
    }

    var volumeKeysEnabled: Boolean = false

    private fun clearLatches() {
        if (ctrlLatch || altLatch) {
            ctrlLatch = false
            altLatch = false
            (parent as? android.view.ViewGroup)?.findViewById<NoxsExtraKeysBar>(R.id.extra_keys)?.refreshLatches()
            invalidate()
        }
    }

    override fun onCheckIsTextEditor(): Boolean = true

    // Recompute metrics with the real density before first use.
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        renderer.densityScale = resources.displayMetrics.scaledDensity
    }
}
