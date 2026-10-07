/*
 * Noxs — original implementation.
 * PluginWindowActivity: the visible surface of a plugin window created
 * through noxs.ui.createWindow(). Renders plugin HTML inside a sandboxed
 * WebView (no file/content access, no navigation) inside a Noxs floating
 * window card. Buffered operations (setHTML/setText) are replayed when the
 * window attaches, so plugins can configure a window before show().
 */
package com.crossberry.noxs.runtime.plugins

import android.annotation.SuppressLint
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.crossberry.noxs.R
import com.crossberry.noxs.shared.NoxsLog

class PluginWindowActivity : AppCompatActivity() {

    private var windowId: String = ""
    private var pluginId: String = ""
    private var webView: WebView? = null
    private var titleView: TextView? = null
    private var pageReady = false
    private var pendingOps: List<Pair<String, String>> = emptyList()

    private val dp: Float
        get() = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 1f, resources.displayMetrics
        )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        windowId = intent?.getStringExtra(EXTRA_WINDOW_ID).orEmpty()
        pluginId = intent?.getStringExtra(EXTRA_PLUGIN_ID).orEmpty()
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Plugin" }
        val width = intent?.getIntExtra(EXTRA_WIDTH, 420) ?: 420
        val height = intent?.getIntExtra(EXTRA_HEIGHT, 480) ?: 480
        if (windowId.isBlank() || NoxsPluginRuntime.handleFor(windowId) == null) {
            finish()
            return
        }
        buildUi(title, width, height)
        NoxsPluginRuntime.attachActivity(windowId, this)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildUi(title: String, width: Int, height: Int) {
        val dimLayer = FrameLayout(this).apply { setBackgroundColor(0x52101418) }
        setContentView(dimLayer)

        val windowCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 16f * dp
                setColor(0xF214161B.toInt())
                setStroke((1 * dp).toInt(), 0xFF2F3946.toInt())
            }
            elevation = 10f * dp
            clipToOutline = true
        }
        dimLayer.addView(windowCard, FrameLayout.LayoutParams(
            (width.coerceIn(200, 1100) * dp).toInt(),
            (height.coerceIn(200, 900) * dp).toInt(),
            Gravity.CENTER
        ))

        // Header: title + close button (matches the Noxs window look).
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
        }
        titleView = TextView(this).apply {
            text = title
            setTextColor(0xFFE6EDF3.toInt())
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val close = TextView(this).apply {
            text = getString(R.string.action_close)
            setTextColor(0xFF9AA7B4.toInt())
            textSize = 13f
            setPadding((10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt())
        }
        close.setOnClickListener { NoxsPluginRuntime.closeWindow(windowId) }
        header.addView(titleView)
        header.addView(close)
        windowCard.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // Content: the plugin's own HTML surface.
        val content = NoxsPluginRuntime.buildWindowWebView(this, pluginId, windowId)
        content.setBackgroundColor(0xFF10151C.toInt())
        content.webViewClient = object : WebViewClient() {
            private var booted = false
            override fun onPageFinished(view: WebView, url: String) {
                if (booted) return
                booted = true
                pageReady = true
                view.evaluateJavascript(NoxsPluginRuntime.BOOTSTRAP_JS, null)
                view.evaluateJavascript(
                    "NoxsWindow.interceptClicks(" + jsQuote(windowId) + ");", null
                )
                replayPending()
            }
        }
        windowCard.addView(content, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        webView = content
        content.loadUrl("about:blank")
        dimLayer.setOnClickListener { /* keep focus inside the window */ }
    }

    /** Applies one host-side op; called from the runtime (main thread). */
    fun applyOp(op: String, arg: String) {
        when (op) {
            "setHTML" -> if (pageReady) {
                webView?.evaluateJavascript(
                    "NoxsWindow.setHTML(" + jsQuote(arg) + ");"
                    + "NoxsWindow.interceptClicks(" + jsQuote(windowId) + ");",
                    null
                )
            } else buffer(op, arg)
            "setText" -> if (pageReady) {
                // arg is the JSON array [selector, text] produced by the shim.
                webView?.evaluateJavascript(
                    "(function(){ try{ var a = JSON.parse(" + jsQuote(arg) +
                        "); NoxsWindow.setText(a[0], a[1]); }catch(e){} })();",
                    null
                )
            } else buffer(op, arg)
            "show" -> Unit // the activity IS the shown window
            "hide", "close" -> NoxsPluginRuntime.closeWindow(windowId)
        }
    }

    private fun buffer(op: String, arg: String) {
        pendingOps = pendingOps + (op to arg)
    }

    private fun replayPending() {
        val ops = pendingOps
        pendingOps = emptyList()
        ops.forEach { (op, arg) -> applyOp(op, arg) }
    }

    private fun jsQuote(text: String): String =
        org.json.JSONObject.quote(text)

    override fun onDestroy() {
        NoxsPluginRuntime.detachActivity(windowId)
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_WINDOW_ID = "noxs.extra.PLUGIN_WINDOW_ID"
        const val EXTRA_PLUGIN_ID = "noxs.extra.PLUGIN_ID"
        const val EXTRA_TITLE = "noxs.extra.PLUGIN_TITLE"
        const val EXTRA_WIDTH = "noxs.extra.PLUGIN_WIDTH"
        const val EXTRA_HEIGHT = "noxs.extra.PLUGIN_HEIGHT"
    }
}
