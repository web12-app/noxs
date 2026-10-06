/*
 * Noxs terminal-view — original implementation.
 * Interactive Android terminal View: IME input, hardware keys, gestures
 * (scroll / pinch zoom / two-finger scroll / copy / paste), three scroll
 * modes with a new-output indicator, output search, configurable appearance,
 * resize propagation and renderer glue.
 *
 * Gesture priority (spec): selection handles > long-press selection >
 * pinch zoom > two-finger scroll > single-finger scroll. A decided
 * two-pointer gesture is sticky until every pointer lifts, pinch wins ties,
 * and touch gestures never send shell input.
 */
package com.crossberry.noxs.terminal.view

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.ActionMode
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.OverScroller
import android.widget.PopupMenu
import com.crossberry.noxs.terminal.emulator.KeyHandler
import com.crossberry.noxs.terminal.emulator.TerminalGesturePolicy
import com.crossberry.noxs.terminal.emulator.SelectionAutoScroller
import com.crossberry.noxs.terminal.emulator.TerminalMultiTouchPolicy
import com.crossberry.noxs.terminal.emulator.TerminalScrollModel
import com.crossberry.noxs.terminal.emulator.TerminalScrollMode
import com.crossberry.noxs.terminal.emulator.TerminalSearch
import com.crossberry.noxs.terminal.emulator.TerminalSearchMatch
import com.crossberry.noxs.terminal.emulator.TerminalSelection
import com.crossberry.noxs.terminal.emulator.TerminalSelectionPoint
import com.crossberry.noxs.terminal.emulator.TerminalSession
import com.crossberry.noxs.terminal.emulator.TerminalTextLinks
import com.crossberry.noxs.terminal.emulator.TerminalViewportState
import com.crossberry.noxs.terminal.emulator.copyText
import com.crossberry.noxs.terminal.emulator.resolveSelection
import com.crossberry.noxs.terminal.emulator.selectAllText
import com.crossberry.noxs.terminal.emulator.wordRangeAt
import kotlin.math.hypot
import kotlin.math.roundToInt

class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var session: TerminalSession? = null
        private set

    val renderer = TerminalRenderer().apply {
        densityScale = resources.displayMetrics.scaledDensity
    }

    private var metrics = renderer.measure()
    private val scroller = OverScroller(context)
    private val viewport = TerminalViewportState()
    private val gesturePolicy = TerminalGesturePolicy()
    private val multiTouch = TerminalMultiTouchPolicy(
        ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    )
    private val edgeAutoScroller = SelectionAutoScroller(48f * resources.displayMetrics.density)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var scrollPixelRemainder = 0f
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var lastTouchY = 0f
    private var touchScrollActive = false
    private var isFocusedVisual = true
    private var selection: TerminalSelection? = null
    private var selectionGesture = false
    private var draggingEndpoint: SelectionEndpoint? = null
    private var pointerX = 0f
    private var pointerY = 0f
    private var lastAutoScrollFrameMs = 0L
    private var edgeAutoScrollScheduled = false
    private var actionMode: ActionMode? = null
    private var cursorBlinkOn = true
    private var composingText = ""

    // Two-pointer state ------------------------------------------------------
    private var multiLastX = 0f
    private var multiLastY = 0f
    private var suppressSingleFingerUntilUp = false
    private var pinchBaseSp = -1f
    private var pinchLastAppliedSp = -1f

    // Scroll mode / output indicator ----------------------------------------
    val scrollModel = TerminalScrollModel()
    private var lastSeenScrollbackSerial: Long? = null

    // Search state -----------------------------------------------------------
    private var searchMatches: List<TerminalSearchMatch> = emptyList()
    private var searchIndex = -1

    // Appearance -------------------------------------------------------------
    var minFontSizeSp: Float = 10f
    var maxFontSizeSp: Float = 28f
    var hapticsEnabled: Boolean = false
    var longPressSelectionEnabled: Boolean = true
    var cursorBlinkEnabled: Boolean = true
    var cursorBlinkPeriodMs: Long = CURSOR_BLINK_MS
    var reduceAnimations: Boolean = false

    private enum class SelectionEndpoint { ANCHOR, FOCUS }

    private val cursorBlinkRunnable = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow || !hasFocus()) return
            cursorBlinkOn = !cursorBlinkOn
            invalidate()
            postDelayed(this, cursorBlinkPeriodMs)
        }
    }

    private data class Cell(val point: TerminalSelectionPoint, val column: Int)

    private val edgeAutoScrollRunnable = object : Runnable {
        override fun run() {
            edgeAutoScrollScheduled = false
            if (selection == null || (draggingEndpoint == null && !selectionGesture)) {
                stopEdgeAutoScroll()
                return
            }
            val now = SystemClock.uptimeMillis()
            val elapsed = if (lastAutoScrollFrameMs == 0L) 16L else (now - lastAutoScrollFrameMs).coerceIn(1L, 50L)
            lastAutoScrollFrameMs = now
            val rowDelta = edgeAutoScroller.step(pointerY, contentHeightPx(), elapsed)
            if (rowDelta != 0) scrollViewportByRows(rowDelta, byUser = true)
            updateSelectionFromPointer(pointerX, pointerY)
            if (edgeAutoScroller.rowsPerSecond(pointerY, contentHeightPx()) != 0f) {
                scheduleEdgeAutoScroll()
            } else {
                stopEdgeAutoScroll()
            }
        }
    }

    private val placeholderPaint = Paint().apply {
        typeface = Typeface.MONOSPACE
        isAntiAlias = true
        color = 0xffbdbdbd.toInt()
        textSize = 14f * resources.displayMetrics.scaledDensity
    }

    /** Latched modifiers from the extra-keys bar. */
    var ctrlLatch = false
    var altLatch = false

    var onScreenUpdated: (() -> Unit)? = null
    var onSessionResized: ((rows: Int, cols: Int) -> Unit)? = null
    var onTerminalLinkClick: ((uri: String) -> Boolean)? = null
    /** Ctrl+F pressed at the view level — the host toggles full screen. */
    var onToggleFullscreen: (() -> Unit)? = null
    /** true when attached to the live bottom; used for the "Latest" affordance. */
    var onScrollStateChanged: ((atLiveBottom: Boolean) -> Unit)? = null
    /** Indicator text from the scroll model ("18 new lines" / "124 lines behind") or null. */
    var onIndicatorChanged: ((label: String?) -> Unit)? = null
    /** Font size settled (pinch end / explicit change) — persist the preference here. */
    var onFontSizeChanged: ((sp: Float) -> Unit)? = null
    /** Search result summary: total matches + current index (−1 = none active). */
    var onSearchResult: ((count: Int, currentIndex: Int) -> Unit)? = null

    private val clipboard by lazy { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    private val imm by lazy { context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            // Base font is anchored on first USE so a late pinch decision
            // (after the detector internally began) never jumps the size.
            pinchBaseSp = -1f
            pinchLastAppliedSp = renderer.fontSizeSp
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (multiTouch.mode != TerminalMultiTouchPolicy.Mode.PINCH) return true
            if (pinchBaseSp <= 0f) {
                pinchBaseSp = renderer.fontSizeSp / detector.scaleFactor
            }
            val target = pinchBaseSp * detector.scaleFactor
            // Snap to 0.5sp steps: smooth enough to feel continuous, cheap
            // enough that PTY resizes stay rare during a pinch.
            val snapped = (target * 2f).roundToInt() / 2f
            if (snapped != pinchLastAppliedSp) {
                pinchLastAppliedSp = snapped
                setFontSizeSp(snapped)
            }
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            if (hapticsEnabled) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onFontSizeChanged?.invoke(renderer.fontSizeSp)
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            if (!scroller.isFinished) scroller.abortAnimation()
            scrollPixelRemainder = 0f
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val cell = cellAt(e.x, e.y)
            // 1. Real OSC 8 hyperlinks, 2. plain printed URLs (localhost
            // servers etc.), 3. otherwise a normal focus/keyboard tap.
            val uri = cell?.let { terminalHyperlinkAt(it) } ?: cell?.let { plainUrlAt(it) }
            if (uri != null && onTerminalLinkClick?.invoke(uri) == true) return true
            requestFocus()
            showSoftInput()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (!longPressSelectionEnabled) return false
            val cell = cellAt(e.x, e.y) ?: return false
            return selectWordAt(cell)
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (selectionGesture && gesturePolicy.isSelecting) {
                updatePointer(e2.x, e2.y)
                updateSelectionFromPointer(pointerX, pointerY)
                updateEdgeAutoScroll()
                return true
            }
            if (draggingEndpoint != null || gesturePolicy.isDraggingHandle) return true
            // Raw MotionEvent deltas are applied once in onTouchEvent; using
            // GestureDetector's distance here as well can double-scroll and is
            // sensitive to gesture sign conventions across Android versions.
            if (gesturePolicy.shouldStartVerticalScroll(dx, dy, touchSlop)) {
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (selectionGesture || draggingEndpoint != null || gesturePolicy.isSelecting || gesturePolicy.isDraggingHandle) return true
            startFling(vy)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (!longPressSelectionEnabled) return
            val cell = cellAt(e.x, e.y) ?: return
            if (!gesturePolicy.onLongPress()) return
            if (hapticsEnabled) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            // Long-press selects the whole word under the finger, then drag
            // adjusts — a plain tap-and-hold yields an immediately copyable
            // word instead of a single character cell.
            if (!beginWordSelection(cell, e.x, e.y)) beginSelection(cell, e.x, e.y)
        }
    })

    private val callback = object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: android.view.Menu): Boolean {
            actionMode = mode
            menu.add(0, ACTION_COPY, 0, "Copy")
            menu.add(0, ACTION_PASTE, 1, "Paste")
            menu.add(0, ACTION_SHARE, 2, "Share")
            menu.add(0, ACTION_MORE, 3, "More…")
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: android.view.Menu) = false

        override fun onActionItemClicked(mode: ActionMode, item: android.view.MenuItem): Boolean = when (item.itemId) {
            ACTION_COPY -> {
                copySelectionToClipboard()
                mode.finish()
                true
            }
            ACTION_PASTE -> {
                pasteFromClipboard()
                mode.finish()
                true
            }
            ACTION_SHARE -> {
                shareSelection()
                mode.finish()
                true
            }
            ACTION_MORE -> {
                showMoreSelectionMenu()
                true
            }
            else -> false
        }

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            val locations = currentHandleLocations()
            val location = when (draggingEndpoint) {
                SelectionEndpoint.ANCHOR -> locations.anchor
                SelectionEndpoint.FOCUS -> locations.focus
                null -> locations.focus ?: locations.anchor
            }
            if (location == null) {
                outRect.set(0, 0, width.coerceAtLeast(1), height.coerceAtLeast(1))
            } else {
                // Keep the floating Copy/Paste/More… pill beside the active end
                // of a long selection, like the platform text-selection toolbar.
                val pad = (24f * resources.displayMetrics.density).toInt()
                val left = (location.x.toInt() - pad).coerceAtLeast(0)
                val top = (location.y.toInt() - pad).coerceAtLeast(0)
                val right = (location.x.toInt() + pad).coerceAtMost(width)
                val bottom = (location.y.toInt() + pad).coerceAtMost(height)
                outRect.set(left, top, right.coerceAtLeast(left + 1), bottom.coerceAtLeast(top + 1))
            }
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            if (actionMode === mode) actionMode = null
            selectionGesture = false
            draggingEndpoint = null
            stopEdgeAutoScroll()
            selection = null
            invalidate()
        }
    }

    private fun terminalHyperlinkAt(cell: Cell): String? {
        val emu = session?.emulator ?: return null
        return synchronized(emu) { emu.hyperlinkAtDocumentPosition(cell.point.lineIdentity, cell.column) }
    }

    private fun contentPaddingPx(): Float = renderer.contentPaddingPx

    private fun contentHeightPx(): Float = (height - 2f * contentPaddingPx()).coerceAtLeast(1f)

    private fun contentWidthPx(): Float = (width - 2f * contentPaddingPx()).coerceAtLeast(1f)

    private fun visibleRowCount(buffer: com.crossberry.noxs.terminal.emulator.TerminalBuffer? = session?.emulator?.buffer): Int {
        if (buffer == null) return 1
        val measured = if (metrics.charHeight > 0f) (contentHeightPx() / metrics.charHeight).toInt() else buffer.rows
        return measured.coerceAtLeast(1).coerceAtMost(buffer.rows.coerceAtLeast(1))
    }

    private fun cellAt(x: Float, y: Float): Cell? {
        if (metrics.charWidth <= 0f || metrics.charHeight <= 0f) return null
        val emu = session?.emulator ?: return null
        return synchronized(emu) {
            val buffer = emu.buffer
            val rowCount = visibleRowCount(buffer)
            val visibleRow = ((y - contentPaddingPx()) / metrics.charHeight).toInt().coerceIn(0, rowCount - 1)
            val firstRow = buffer.viewportStartDocumentRow(viewport.scrollOffsetFromBottom, rowCount)
            val documentRow = firstRow + visibleRow
            val line = buffer.documentLineAt(documentRow) ?: return@synchronized null
            val colCount = minOf(buffer.cols, line.chars.size)
            if (colCount <= 0) return@synchronized null
            val column = ((x - contentPaddingPx()) / metrics.charWidth).toInt().coerceIn(0, colCount - 1)
            Cell(TerminalSelectionPoint(line.identity, column), column)
        }
    }

    private fun currentHandleLocations(): TerminalRenderer.HandleLocations {
        val selected = selection ?: return TerminalRenderer.HandleLocations(null, null)
        val emu = session?.emulator ?: return TerminalRenderer.HandleLocations(null, null)
        return synchronized(emu) {
            renderer.selectionHandleLocations(
                emu.buffer,
                selected,
                viewport.scrollOffsetFromBottom,
                visibleRowCount(emu.buffer),
                metrics.charWidth,
                metrics.charHeight,
                width,
                height,
                resources.displayMetrics.density
            )
        }
    }

    private fun handleAt(x: Float, y: Float): SelectionEndpoint? {
        if (selection == null) return null
        val locations = currentHandleLocations()
        val radius = HANDLE_HIT_RADIUS_DP * resources.displayMetrics.density
        fun distance(location: TerminalRenderer.HandleLocation?): Float = location?.let {
            val dx = x - it.x
            val dy = y - it.y
            dx * dx + dy * dy
        } ?: Float.POSITIVE_INFINITY
        val anchorDistance = distance(locations.anchor)
        val focusDistance = distance(locations.focus)
        val maxDistance = radius * radius
        if (anchorDistance > maxDistance && focusDistance > maxDistance) return null
        return if (anchorDistance <= focusDistance) SelectionEndpoint.ANCHOR else SelectionEndpoint.FOCUS
    }

    private fun beginSelection(cell: Cell, x: Float, y: Float) {
        val point = cell.point
        selection = TerminalSelection(point, point)
        selectionGesture = true
        draggingEndpoint = null
        updatePointer(x, y)
        parent?.requestDisallowInterceptTouchEvent(true)
        actionMode?.finish()
        startActionMode(callback, ActionMode.TYPE_FLOATING)?.let { actionMode = it }
        actionMode?.invalidateContentRect()
        invalidate()
    }

    /**
     * Long-press/double-tap entry: select the word under [cell] and raise the
     * floating Copy toolbar right away. Returns false when the cell holds no
     * word (empty padding area) so the caller can fall back to cell selection.
     */
    private fun beginWordSelection(cell: Cell, x: Float, y: Float): Boolean {
        val emu = session?.emulator ?: return false
        val range = synchronized(emu) {
            emu.buffer.wordRangeAt(cell.point.lineIdentity, cell.column)
        } ?: return false
        selection = TerminalSelection(
            TerminalSelectionPoint(cell.point.lineIdentity, range.first),
            TerminalSelectionPoint(cell.point.lineIdentity, range.last)
        )
        // Keep the gesture live so dragging the same finger extends the
        // selection from the nearer word end (updateSelectionFromPointer).
        selectionGesture = true
        draggingEndpoint = null
        updatePointer(x, y)
        parent?.requestDisallowInterceptTouchEvent(true)
        actionMode?.finish()
        startActionMode(callback, ActionMode.TYPE_FLOATING)?.let { actionMode = it }
        actionMode?.invalidateContentRect()
        invalidate()
        return true
    }

    /** Double-tap word selection (reuses the long-press path). */
    private fun selectWordAt(cell: Cell): Boolean = beginWordSelection(cell, 0f, 0f)

    /**
     * Detects a plain printed URL (http/https or loopback shorthand such as
     * "localhost:8080") at [cell] by scanning the real line text. Only text
     * genuinely present in the output is returned.
     */
    private fun plainUrlAt(cell: Cell): String? {
        val emu = session?.emulator ?: return null
        return synchronized(emu) {
            val buffer = emu.buffer
            val row = buffer.documentRowOf(cell.point.lineIdentity) ?: return@synchronized null
            val line = buffer.documentLineAt(row) ?: return@synchronized null
            val textIndex = TerminalTextLinks.cellColumnToTextIndex(line.chars, line.styles, cell.column)
            TerminalTextLinks.findUrlAt(line.text(), textIndex)
        }
    }

    private fun updatePointer(x: Float, y: Float) {
        pointerX = x
        pointerY = y
    }

    private fun updateSelectionFromPointer(x: Float, y: Float) {
        val cell = cellAt(x, y) ?: return
        val current = selection ?: return
        selection = when (draggingEndpoint) {
            SelectionEndpoint.ANCHOR -> current.copy(anchor = cell.point)
            SelectionEndpoint.FOCUS -> current.copy(focus = cell.point)
            null -> {
                // Free finger drag after a long-press word selection: extend
                // whichever word end the finger has moved past, the way a
                // platform text view behaves.
                val emu = session?.emulator ?: return
                val extendAnchor = synchronized(emu) {
                    val pointerRow = emu.buffer.documentRowOf(cell.point.lineIdentity)
                        ?: return@synchronized false
                    val anchorRow = emu.buffer.documentRowOf(current.anchor.lineIdentity) ?: pointerRow
                    val focusRow = emu.buffer.documentRowOf(current.focus.lineIdentity) ?: pointerRow
                    fun atOrBefore(row: Int, col: Int) =
                        row < pointerRow || (row == pointerRow && col <= cell.point.column)
                    val focusBeforeFinger = atOrBefore(focusRow, current.focus.column)
                    val anchorAfterFinger = !atOrBefore(anchorRow, current.anchor.column)
                    anchorAfterFinger && !focusBeforeFinger
                }
                if (extendAnchor) current.copy(anchor = cell.point) else current.copy(focus = cell.point)
            }
        }
        actionMode?.invalidateContentRect()
        invalidate()
    }

    private fun updateEdgeAutoScroll() {
        if (selection == null || (draggingEndpoint == null && !selectionGesture) ||
            edgeAutoScroller.rowsPerSecond(pointerY, contentHeightPx()) == 0f) {
            stopEdgeAutoScroll()
            return
        }
        if (lastAutoScrollFrameMs == 0L) lastAutoScrollFrameMs = SystemClock.uptimeMillis()
        scheduleEdgeAutoScroll()
    }

    private fun scheduleEdgeAutoScroll() {
        if (edgeAutoScrollScheduled) return
        edgeAutoScrollScheduled = true
        postOnAnimation(edgeAutoScrollRunnable)
    }

    private fun stopEdgeAutoScroll() {
        removeCallbacks(edgeAutoScrollRunnable)
        edgeAutoScrollScheduled = false
        lastAutoScrollFrameMs = 0L
        edgeAutoScroller.reset()
    }

    private fun scrollByPixels(distanceY: Float) {
        if (metrics.charHeight <= 0f) return
        scrollPixelRemainder += distanceY
        val rows = (scrollPixelRemainder / metrics.charHeight).toInt()
        if (rows == 0) return
        scrollPixelRemainder -= rows * metrics.charHeight
        scrollViewportByRows(rows, byUser = true)
    }

    private fun scrollViewportByRows(rows: Int, byUser: Boolean) {
        val emu = session?.emulator ?: return
        synchronized(emu) { viewport.scrollByRows(rows, emu.buffer, visibleRowCount(emu.buffer)) }
        if (byUser) markUserScroll()
        else notifyScrollState()
        invalidate()
    }

    /** Feed user-driven viewport motion into the scroll-mode policy. */
    private fun markUserScroll() {
        val emu = session?.emulator
        val atBottom = if (emu == null) true else synchronized(emu) {
            viewport.synchronize(emu.buffer, visibleRowCount(emu.buffer))
            viewport.scrollOffsetFromBottom == 0
        }
        scrollModel.onUserScroll(atBottom)
        notifyScrollState()
    }

    private fun startFling(velocityY: Float) {
        val emu = session?.emulator ?: return
        val maxOffset: Int
        val startOffset: Int
        synchronized(emu) {
            val rows = visibleRowCount(emu.buffer)
            viewport.synchronize(emu.buffer, rows)
            maxOffset = viewport.maxScrollOffset(emu.buffer, rows)
            startOffset = viewport.scrollOffsetFromBottom
        }
        if (maxOffset <= 0 || metrics.charHeight <= 0f) return
        if (reduceAnimations) {
            // Reduced-motion preference: skip the physics animation entirely.
            viewport.scrollByRows(if (velocityY < 0) 12 else -12, emu.buffer, visibleRowCount(emu.buffer))
            markUserScroll()
            invalidate()
            return
        }
        // OverScroller coordinates are rows here, while GestureDetector reports
        // pixels per second; convert units to avoid extremely fast terminal flings.
        val velocityRowsPerSecond = (velocityY / metrics.charHeight).toInt()
        scroller.fling(0, startOffset, 0, -velocityRowsPerSecond, 0, 0, 0, maxOffset)
        postInvalidateOnAnimation()
    }

    private fun selectedText(): String? {
        val selected = selection ?: return null
        val emu = session?.emulator ?: return null
        return synchronized(emu) { selected.copyText(emu.buffer) }
    }

    private fun copySelectionToClipboard() {
        val text = selectedText()
        if (text.isNullOrEmpty()) {
            android.widget.Toast.makeText(context, "Nothing selected to copy", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("noxs-selection", text))
        android.widget.Toast.makeText(
            context,
            "Copied " + text.length + (if (text.length == 1) " character" else " characters"),
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    private fun shareSelection() {
        val text = selectedText() ?: return
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, text)
        }
        runCatching {
            context.startActivity(android.content.Intent.createChooser(send, null))
        }
    }

    private fun showMoreSelectionMenu() {
        val popup = PopupMenu(context, this)
        popup.menu.add(0, MORE_SELECT_ALL, 0, "Select all output")
        popup.menu.add(0, MORE_COPY_ALL, 1, "Copy all output")
        popup.menu.add(0, MORE_CLEAR_SCROLLBACK, 2, "Clear scrollback")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MORE_SELECT_ALL -> {
                    val emu = session?.emulator
                    if (emu != null) {
                        val all = synchronized(emu) { emu.buffer.selectAllText() }
                        if (all != null) {
                            selection = all
                            actionMode?.invalidateContentRect()
                            invalidate()
                        }
                    }
                    true
                }
                MORE_COPY_ALL -> {
                    val text = session?.emulator?.transcriptText()?.replace("█", "").orEmpty()
                    clipboard.setPrimaryClip(ClipData.newPlainText("noxs-terminal", text))
                    actionMode?.finish()
                    true
                }
                MORE_CLEAR_SCROLLBACK -> {
                    clearScrollback()
                    actionMode?.finish()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    /** Clears history rows only — never the screen, shell, filesystem or shell history. */
    fun clearScrollback() {
        val emu = session?.emulator ?: return
        synchronized(emu) { emu.buffer.clearScrollback() }
        markUserScroll()
        invalidate()
    }

    // ------------------------------------------------------------ scroll state

    private fun notifyScrollState() {
        val emu = session?.emulator
        var atBottom = true
        if (emu != null) synchronized(emu) {
            viewport.synchronize(emu.buffer, visibleRowCount(emu.buffer))
            atBottom = viewport.scrollOffsetFromBottom == 0
        }
        onScrollStateChanged?.invoke(atBottom)
        onIndicatorChanged?.invoke(scrollModel.indicatorLabel(atBottom, viewport.scrollOffsetFromBottom))
    }

    /** Scroll position (rows above the live bottom) for state restoration. */
    fun currentScrollOffset(): Int = viewport.scrollOffsetFromBottom

    /** Restores a saved scroll position (session re-attach, activity recreate). */
    fun restoreScrollOffset(offset: Int) {
        if (offset <= 0) return
        val emu = session?.emulator ?: return
        synchronized(emu) {
            viewport.setOffsetFromBottom(offset, emu.buffer, visibleRowCount(emu.buffer))
        }
        scrollModel.onUserScroll(false)
        notifyScrollState()
        invalidate()
    }

    // ----------------------------------------------------------------- search

    /**
     * Searches the whole document (scrollback + screen) for [query]. Pure
     * buffer reads: never writes shell input, never interrupts processes.
     */
    fun setSearchQuery(query: String) {
        val emu = session?.emulator
        if (emu == null) {
            searchMatches = emptyList()
            searchIndex = -1
            onSearchResult?.invoke(0, -1)
            return
        }
        synchronized(emu) {
            searchMatches = if (query.isBlank()) {
                emptyList()
            } else {
                TerminalSearch.find(emu.buffer, query.trim())
            }
            // Anchor on the newest match so the user lands near their prompt.
            searchIndex = searchMatches.lastIndex
            if (searchIndex >= 0) jumpToMatchLocked(emu)
        }
        onSearchResult?.invoke(searchMatches.size, searchIndex)
        invalidate()
    }

    fun searchNextMatch() {
        if (searchMatches.isEmpty()) return
        searchIndex = TerminalSearch.step(searchMatches, searchIndex, forward = true)
        val emu = session?.emulator ?: return
        synchronized(emu) { jumpToMatchLocked(emu) }
        onSearchResult?.invoke(searchMatches.size, searchIndex)
        invalidate()
    }

    fun searchPreviousMatch() {
        if (searchMatches.isEmpty()) return
        searchIndex = TerminalSearch.step(searchMatches, searchIndex, forward = false)
        val emu = session?.emulator ?: return
        synchronized(emu) { jumpToMatchLocked(emu) }
        onSearchResult?.invoke(searchMatches.size, searchIndex)
        invalidate()
    }

    fun clearSearch() {
        if (searchMatches.isEmpty() && searchIndex < 0) return
        searchMatches = emptyList()
        searchIndex = -1
        onSearchResult?.invoke(0, -1)
        invalidate()
    }

    /** Caller must hold the emulator monitor. */
    private fun jumpToMatchLocked(emu: com.crossberry.noxs.terminal.emulator.TerminalEmulator) {
        val match = searchMatches.getOrNull(searchIndex) ?: return
        val rows = visibleRowCount(emu.buffer)
        val documentRows = emu.buffer.documentRowCount()
        // Place the match roughly one third from the top of the viewport.
        val offset = (documentRows - match.row - rows / 3).coerceIn(0, emu.buffer.maxScrollOffset(rows))
        viewport.setOffsetFromBottom(offset, emu.buffer, rows)
    }

    // ---------------------------------------------------------------- gestures

    private fun spanOf(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        return hypot(
            event.getX(0) - event.getX(1),
            event.getY(0) - event.getY(1)
        )
    }

    private fun centroidOf(event: MotionEvent): Pair<Float, Float> {
        val n = event.pointerCount
        if (n == 0) return 0f to 0f
        var sx = 0f
        var sy = 0f
        for (i in 0 until n) {
            sx += event.getX(i)
            sy += event.getY(i)
        }
        return sx / n to sy / n
    }

    private fun handleMultiTouchMove(event: MotionEvent): Boolean {
        val (cx, cy) = centroidOf(event)
        val dx = cx - multiLastX
        val dyUp = multiLastY - cy // fingers moving up → toward older output
        val mode = multiTouch.onMove(spanOf(event), dx, dyUp)
        multiLastX = cx
        multiLastY = cy
        when (mode) {
            TerminalMultiTouchPolicy.Mode.PINCH -> scaleDetector.onTouchEvent(event)
            TerminalMultiTouchPolicy.Mode.TWO_FINGER_SCROLL -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                scrollByPixels(dyUp)
            }
            else -> Unit
        }
        return true
    }

    /** Called by the PTY session's coalesced main-thread output notification. */
    fun onSessionOutputChanged(changedSession: TerminalSession) {
        if (session !== changedSession) return
        val emu = changedSession.emulator
        synchronized(emu) {
            val buffer = emu.buffer
            val rows = visibleRowCount(buffer)
            val before = lastSeenScrollbackSerial
            val pushed = if (before == null) 0L else (buffer.scrollbackSerial - before).coerceAtLeast(0L)
            lastSeenScrollbackSerial = buffer.scrollbackSerial
            val wasAtBottom = viewport.scrollOffsetFromBottom == 0
            val follow = scrollModel.onOutputPushed(
                pushed.toInt(), wasAtBottom, changedSession.isRunning, buffer.usingAlt
            )
            // Follow disabled (or user away): hold the reading position against
            // the freshly pushed rows. The viewport already compensates when it
            // is displaced; this covers the anchored-but-not-following case.
            if (!follow && pushed > 0 && viewport.scrollOffsetFromBottom == 0 && !buffer.usingAlt) {
                viewport.scrollByRows(pushed.toInt(), buffer, rows)
            }
            if (selection != null && buffer.resolveSelection(selection!!) == null) {
                selection = null
                actionMode?.finish()
            }
        }
        if (!scroller.isFinished) scroller.abortAnimation()
        notifyScrollState()
        invalidate()
    }

    fun attach(newSession: TerminalSession) {
        actionMode?.finish()
        session = newSession
        selection = null
        selectionGesture = false
        draggingEndpoint = null
        touchScrollActive = false
        gesturePolicy.finish()
        multiTouch.onEnd()
        suppressSingleFingerUntilUp = false
        scroller.abortAnimation()
        stopEdgeAutoScroll()
        scrollPixelRemainder = 0f
        searchMatches = emptyList()
        searchIndex = -1
        scrollModel.reset()
        lastSeenScrollbackSerial = null
        synchronized(newSession.emulator) {
            viewport.attach(newSession.emulator.buffer, visibleRowCount(newSession.emulator.buffer))
            lastSeenScrollbackSerial = newSession.emulator.buffer.scrollbackSerial
        }
        updateSize()
        notifyScrollState()
        invalidate()
    }

    fun setFontSizeSp(sp: Float) {
        val clamped = sp.coerceIn(minFontSizeSp, maxFontSizeSp)
        if (renderer.fontSizeSp == clamped) return
        renderer.fontSizeSp = clamped
        updateSize()
        invalidate()
    }

    fun currentFontSizeSp(): Float = renderer.fontSizeSp

    fun nudgeFontSize(deltaSp: Float) {
        val target = (renderer.fontSizeSp + deltaSp).coerceIn(minFontSizeSp, maxFontSizeSp)
        setFontSizeSp(target)
        onFontSizeChanged?.invoke(renderer.fontSizeSp)
    }

    fun resetFontSize() {
        setFontSizeSp(DEFAULT_FONT_SIZE_SP)
        onFontSizeChanged?.invoke(renderer.fontSizeSp)
    }

    fun showSoftInput() {
        requestFocus()
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun pasteFromClipboard() {
        val clip = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
        if (clip.isNullOrEmpty()) {
            android.widget.Toast.makeText(context, "Clipboard is empty", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        session?.write(session?.emulator?.paste(clip) ?: return)
        android.widget.Toast.makeText(context, "Pasted", android.widget.Toast.LENGTH_SHORT).show()
    }

    fun selectionOrTranscriptText(): String =
        selectedText() ?: session?.emulator?.transcriptText()?.replace("█", "").orEmpty()

    /** Explicitly returns the viewport to the live bottom without moving the PTY cursor. */
    fun scrollToBottom() {
        scroller.abortAnimation()
        scrollPixelRemainder = 0f
        val emu = session?.emulator
        if (emu != null) synchronized(emu) {
            viewport.scrollToBottom(emu.buffer, visibleRowCount(emu.buffer))
        }
        scrollModel.onUserScroll(true)
        notifyScrollState()
        invalidate()
    }

    /** Jumps to the oldest retained output row (Scroll to top). */
    fun scrollToTop() {
        scroller.abortAnimation()
        val emu = session?.emulator ?: return
        synchronized(emu) {
            val rows = visibleRowCount(emu.buffer)
            viewport.setOffsetFromBottom(emu.buffer.maxScrollOffset(rows), emu.buffer, rows)
        }
        scrollModel.onUserScroll(false)
        notifyScrollState()
        invalidate()
    }

    fun sendBytes(data: ByteArray) {
        if (ctrlLatch && data.size == 1) {
            val ch = data[0].toInt() and 0xff
            val ctrlByte = (ch and 0x1f).toByte()
            session?.write(byteArrayOf(ctrlByte))
            clearLatches()
        } else if (altLatch && data.isNotEmpty()) {
            session?.write(byteArrayOf(0x1b) + data)
            clearLatches()
        } else {
            session?.write(data)
        }
    }

    /** Send a terminal navigation key while honoring the extra-key modifier latches. */
    fun sendSpecialKey(keyCode: Int) {
        val emu = session?.emulator ?: return
        val modifiers = (if (ctrlLatch) KeyHandler.MOD_CTRL else 0) or
            (if (altLatch) KeyHandler.MOD_ALT else 0)
        val sequence = KeyHandler.map(keyCode, modifiers, emu.appCursorKeys) ?: return
        session?.write(sequence)
        clearLatches()
    }

    override fun onDraw(canvas: Canvas) {
        renderer.densityScale = resources.displayMetrics.scaledDensity
        val emu = session?.emulator ?: run {
            canvas.drawColor(renderer.theme.defaultBg)
            val pad = 16f * resources.displayMetrics.density
            canvas.drawText("Starting Noxs terminal…", pad, pad * 2f, placeholderPaint)
            return
        }
        // PTY output is parsed on a reader thread while Canvas rendering runs on
        // the main thread. Use the emulator monitor so both frame traversal and
        // cursor drawing see one consistent screen after writes/resizes.
        synchronized(emu) {
            val rows = visibleRowCount(emu.buffer)
            viewport.synchronize(emu.buffer, rows)
            renderer.scrollOffset = viewport.scrollOffsetFromBottom
            val firstRow = emu.buffer.viewportStartDocumentRow(viewport.scrollOffsetFromBottom, rows)
            val lastRow = firstRow + rows - 1
            var visibleMatches: List<TerminalSearchMatch> = emptyList()
            var currentMatch: TerminalSearchMatch? = null
            if (searchMatches.isNotEmpty()) {
                // Matches are ordered by row; binary-search the visible window.
                val from = searchMatches.binarySearch { it.row.compareTo(firstRow) }
                    .let { if (it < 0) -(it + 1) else it }
                currentMatch = searchMatches.getOrNull(searchIndex)
                val until = searchMatches.indexOfFirst(from, lastRow + 1)
                if (from < until) visibleMatches = searchMatches.subList(from, until)
            }
            renderer.render(
                canvas,
                emu,
                metrics,
                viewport.scrollOffsetFromBottom,
                isFocusedVisual,
                resources.displayMetrics.density,
                selection,
                visibleMatches,
                currentMatch
            )
            if (viewport.scrollOffsetFromBottom == 0 && (cursorBlinkOn || !hasFocus() || !cursorBlinkEnabled)) {
                renderer.drawCursor(canvas, emu, metrics)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val handle = handleAt(event.x, event.y)
                gesturePolicy.onDown(onSelectionHandle = handle != null)
                touchDownX = event.x
                touchDownY = event.y
                lastTouchY = event.y
                touchScrollActive = false
                suppressSingleFingerUntilUp = false
                if (handle != null) {
                    scroller.abortAnimation()
                    draggingEndpoint = handle
                    selectionGesture = false
                    updatePointer(event.x, event.y)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    updateEdgeAutoScroll()
                    return true
                }
                if (selection != null) {
                    actionMode?.finish()
                    selection = null
                    selectionGesture = false
                    invalidate()
                }
                draggingEndpoint = null
                stopEdgeAutoScroll()
                parent?.requestDisallowInterceptTouchEvent(false)
                return gestureDetector.onTouchEvent(event)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger lands while a single-finger stream is active.
                // Selection/handle ownership always wins; otherwise start the
                // two-pointer decision (pinch vs two-finger scroll).
                if (!selectionGesture && draggingEndpoint == null && event.pointerCount == 2) {
                    scroller.abortAnimation()
                    touchScrollActive = false
                    val (cx, cy) = centroidOf(event)
                    multiLastX = cx
                    multiLastY = cy
                    multiTouch.onBegin(spanOf(event))
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (multiTouch.active) {
                    if (event.pointerCount - 1 <= 1) {
                        multiTouch.onEnd()
                        // The remaining finger must not become an instant
                        // scroll or a phantom tap after a pinch/scroll.
                        suppressSingleFingerUntilUp = true
                        gesturePolicy.finish()
                    }
                    return true
                }
            }
        }

        // Two-pointer stream (pinch zoom / two-finger scroll)
        if (multiTouch.active && event.pointerCount >= 2 && !selectionGesture && draggingEndpoint == null) {
            return handleMultiTouchMove(event)
        }

        val handled = gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_MOVE && !selectionGesture &&
            draggingEndpoint == null && gesturePolicy.owner == TerminalGesturePolicy.Owner.SCROLL_OR_TAP) {
            val deltaX = event.x - touchDownX
            val deltaY = event.y - touchDownY
            if (!touchScrollActive && gesturePolicy.shouldStartVerticalScroll(deltaX, deltaY, touchSlop)) {
                touchScrollActive = true
                scrollPixelRemainder = 0f
                scroller.abortAnimation()
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            if (touchScrollActive) {
                scrollByPixels(gesturePolicy.verticalScrollDelta(lastTouchY, event.y))
            }
            lastTouchY = event.y
        }
        if (selectionGesture) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    updatePointer(event.x, event.y)
                    updateSelectionFromPointer(pointerX, pointerY)
                    updateEdgeAutoScroll()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    selectionGesture = false
                    touchScrollActive = false
                    gesturePolicy.finish()
                    stopEdgeAutoScroll()
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            return true
        }
        if (draggingEndpoint != null) {
            when (event.actionMasked) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    draggingEndpoint = null
                    touchScrollActive = false
                    gesturePolicy.finish()
                    stopEdgeAutoScroll()
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (suppressSingleFingerUntilUp) {
                suppressSingleFingerUntilUp = false
                multiTouch.onEnd()
                gesturePolicy.finish()
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
            touchScrollActive = false
            gesturePolicy.finish()
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return handled
    }

    override fun computeScroll() {
        super.computeScroll()
        if (!scroller.computeScrollOffset()) return
        val emu = session?.emulator
        if (emu != null) synchronized(emu) {
            viewport.setOffsetFromBottom(scroller.currY, emu.buffer, visibleRowCount(emu.buffer))
        }
        markUserScroll()
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateSize()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        isFocusedVisual = gainFocus
        cursorBlinkOn = true
        removeCallbacks(cursorBlinkRunnable)
        if (gainFocus && isAttachedToWindow) postDelayed(cursorBlinkRunnable, cursorBlinkPeriodMs)
        invalidate()
    }

    private fun updateSize() {
        renderer.densityScale = resources.displayMetrics.scaledDensity
        val m = renderer.measure().also { metrics = it }
        val w = contentWidthPx().takeIf { width > 0 } ?: 720f
        val h = contentHeightPx().takeIf { height > 0 } ?: 1200f
        val cols = (w / m.charWidth).toInt().coerceAtLeast(4)
        val rows = (h / m.charHeight).toInt().coerceAtLeast(2)
        session?.resize(rows, cols)
        val emu = session?.emulator
        if (emu != null) synchronized(emu) {
            viewport.synchronize(emu.buffer, visibleRowCount(emu.buffer))
            if (selection != null && emu.buffer.resolveSelection(selection!!) == null) {
                selection = null
                actionMode?.finish()
            }
        }
        scroller.abortAnimation()
        onSessionResized?.invoke(rows, cols)
        notifyScrollState()
        invalidate()
    }

    override fun checkInputConnectionProxy(view: View): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT or
            EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                composingText = ""
                val committed = text.toString().replace("\r\n", "\r").replace("\n", "\r")
                if (committed.isNotEmpty()) sendBytes(committed.toByteArray(Charsets.UTF_8))
                return true
            }

            override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
                // Keep IME composition local until it commits; sending every
                // composing update duplicates characters in predictive keyboards.
                composingText = text.toString()
                return true
            }

            override fun finishComposingText(): Boolean {
                composingText = ""
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (composingText.isNotEmpty()) {
                    composingText = when {
                        beforeLength > 0 -> composingText.dropLast(beforeLength.coerceAtMost(composingText.length))
                        afterLength > 0 -> composingText.drop(afterLength.coerceAtMost(composingText.length))
                        else -> composingText
                    }
                    return true
                }
                if (beforeLength > 0) repeat(beforeLength) { sendBytes(byteArrayOf(0x7f)) }
                else if (afterLength > 0) repeat(afterLength) { sendBytes("\u001b[3~".toByteArray()) }
                return true
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                sendBytes(byteArrayOf('\r'.code.toByte()))
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    onKeyDown(event.keyCode, event)
                    return true
                }
                return super.sendKeyEvent(event)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val emu = session?.emulator ?: return super.onKeyDown(keyCode, event)
        if (handleVolumeKeys(keyCode)) return true

        val meta = event.metaState
        val ctrl = ctrlLatch || (meta and KeyEvent.META_CTRL_ON) != 0
        val alt = altLatch || (meta and KeyEvent.META_ALT_ON) != 0
        val shift = (meta and KeyEvent.META_SHIFT_ON) != 0
        val mods = (if (ctrl) KeyHandler.MOD_CTRL else 0) or
            (if (alt) KeyHandler.MOD_ALT else 0) or
            (if (shift) KeyHandler.MOD_SHIFT else 0)

        // Ctrl+V paste (also Ctrl+Shift+V)
        if (ctrl && keyCode == KeyEvent.KEYCODE_V) {
            pasteFromClipboard()
            clearLatches()
            return true
        }

        // Ctrl+F toggles full screen at the app level (never sent to the
        // shell): first press enters, next press exits. The host decides.
        if (ctrl && keyCode == KeyEvent.KEYCODE_F) {
            clearLatches()
            onToggleFullscreen?.invoke()
            return true
        }

        // Arrow with modifiers → word jump
        KeyHandler.modifiedArrow(keyCode, ctrl, shift, emu.appCursorKeys)?.let {
            session?.write(it)
            clearLatches()
            return true
        }

        KeyHandler.map(keyCode, mods, emu.appCursorKeys)?.let {
            session?.write(it)
            clearLatches()
            return true
        }

        val chr = event.getUnicodeChar(meta)
        if (chr != 0) {
            if (ctrl) {
                // Control codes for punctuation (Ctrl+[ = ESC, Ctrl+Space = NUL…)
                session?.write(byteArrayOf((chr and 0x1f).toByte()))
            } else {
                session?.write(String(Character.toChars(chr)).toByteArray(Charsets.UTF_8))
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
        session?.write(if (emu.appCursorKeys) "\u001bO$dir".toByteArray() else "\u001b[$dir".toByteArray())
        return true
    }

    var volumeKeysEnabled: Boolean = false

    private fun clearLatches() {
        if (ctrlLatch || altLatch) {
            ctrlLatch = false
            altLatch = false
            (parent?.parent as? android.view.ViewGroup)?.findViewById<NoxsExtraKeysBar>(R.id.extra_keys)?.refreshLatches()
            (parent as? android.view.ViewGroup)?.findViewById<NoxsExtraKeysBar>(R.id.extra_keys)?.refreshLatches()
            invalidate()
        }
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        renderer.densityScale = resources.displayMetrics.scaledDensity
        metrics = renderer.measure()
        if (hasFocus()) postDelayed(cursorBlinkRunnable, cursorBlinkPeriodMs)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(cursorBlinkRunnable)
        stopEdgeAutoScroll()
        scroller.abortAnimation()
        actionMode?.finish()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val CURSOR_BLINK_MS = 550L
        const val ACTION_COPY = 101
        const val ACTION_PASTE = 102
        const val ACTION_SHARE = 103
        const val ACTION_MORE = 104
        const val MORE_SELECT_ALL = 201
        const val MORE_COPY_ALL = 202
        const val MORE_CLEAR_SCROLLBACK = 203
        const val HANDLE_HIT_RADIUS_DP = 28f
        const val DEFAULT_FONT_SIZE_SP = 14f
    }

    /**
     * Applies the persisted terminal appearance in one call. Every field takes
     * effect live — no shell restart, no session loss. Font size is applied
     * through the same clamped path pinch zoom uses.
     */
    fun applyAppearance(appearance: TerminalViewAppearance) {
        renderer.theme = appearance.theme
        renderer.fontFamily = appearance.fontFamily.typeface
        renderer.lineSpacing = appearance.lineSpacing
        renderer.letterSpacingEm = appearance.letterSpacingEm
        renderer.cursorStyle = appearance.cursorStyle
        renderer.cursorWidth = appearance.cursorWidth
        renderer.contentPaddingPx = appearance.paddingDp * resources.displayMetrics.density
        alpha = appearance.opacity
        hapticsEnabled = appearance.haptics
        longPressSelectionEnabled = appearance.longPressSelection
        multiTouch.pinchZoomEnabled = appearance.pinchZoom
        multiTouch.twoFingerScrollEnabled = appearance.twoFingerScroll
        minFontSizeSp = appearance.minFontSizeSp
        maxFontSizeSp = appearance.maxFontSizeSp
        scrollModel.mode = appearance.scrollMode
        scrollModel.followLiveOutput = appearance.followLiveOutput
        scrollModel.showNewOutputIndicator = appearance.showNewOutputIndicator
        scrollModel.autoFollowWhileRunning = appearance.autoFollowWhileRunning
        scrollModel.reset()
        cursorBlinkEnabled = appearance.cursorBlink
        cursorBlinkPeriodMs = appearance.cursorBlinkPeriodMs
        reduceAnimations = appearance.reduceAnimations
        renderer.textAntialias = appearance.textAntialias
        setFontSizeSp(appearance.fontSizeSp)
        restartCursorBlink()
    }

    private fun restartCursorBlink() {
        cursorBlinkOn = true
        removeCallbacks(cursorBlinkRunnable)
        if (isAttachedToWindow) postDelayed(cursorBlinkRunnable, cursorBlinkPeriodMs)
        invalidate()
    }
}

private fun List<TerminalSearchMatch>.indexOfFirst(fromIndex: Int, rowBoundExclusive: Int): Int {
    if (fromIndex >= size) return size
    for (i in fromIndex until size) {
        if (this[i].row >= rowBoundExclusive) return i
    }
    return size
}

