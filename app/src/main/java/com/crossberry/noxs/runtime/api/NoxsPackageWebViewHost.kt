/*
 * Noxs — original implementation.
 * NoxsPackageWebViewHost: hosts UI for a single NX package inside a Noxs
 * floating window (Noxs API spec §12, §19-§20).
 *
 * Isolation model:
 *
 *   NX Package UI -> Package WebView (noxs-pkg://<packageId>/...)
 *                 -> @noxs/nx-api -> NoxsApiBridge -> Permission Manager
 *
 *  - package content is served from app-private noxs-pkg-ui/<packageId>/
 *    through the custom noxs-pkg:// scheme — never file:, never content:
 *  - window.NoxsBridge is attached ONLY to this package WebView; external
 *    websites opened with `nx ow` use a separate restricted WebView that
 *    never receives any Noxs bridge or SDK (spec §19)
 *  - navigation away from the package origin is redirected into the Noxs
 *    browser window (full URL validation), so the bridge can never leak to
 *    an arbitrary site
 *  - the bridge verifies the live origin at every call: packageId comes
 *    from the host, not from JavaScript
 *  - every bridge call is validated by NoxsApiBridge (identity, permission,
 *    arguments, structured errors)
 */
package com.crossberry.noxs.runtime.api

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.crossberry.noxs.ui.WebWindowActivity
import java.io.File

class NoxsPackageWebViewHost private constructor(
    private val webView: WebView,
    private val packageId: String,
    private val bridge: NoxsApiBridge,
    private val contentRoot: File,
    private val eventDispatcher: ((String) -> Unit)?
) {

    /** Live package origin, updated on every page start; null when off-origin. */
    @Volatile
    private var onPackageOrigin: Boolean = false

    init {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        webView.webViewClient = PackageWebViewClient()
        webView.addJavascriptInterface(PackageBridge(), "NoxsBridge")
    }

    fun loadIndex() {
        webView.loadUrl("$SCHEME://$packageId/index.html")
    }

    private fun resolveContent(requestUrl: android.net.Uri): File? {
        // Accept ONLY noxs-pkg://<this package id>/... — anything else is
        // rejected (cross-package access, external schemes, traversal).
        if (requestUrl.scheme != SCHEME || requestUrl.host != packageId) return null
        val path = requestUrl.path?.trimStart('/') ?: return null
        if (path.isEmpty()) return null
        val file = File(contentRoot, path)
        val canonicalRoot = contentRoot.canonicalFile
        val canonical = file.canonicalFile
        if (!canonical.path.startsWith(canonicalRoot.path + File.separator)) return null
        if (!canonical.isFile) return null
        return canonical
    }

    private inner class PackageWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            if (url.scheme == SCHEME && url.host == packageId) return false
            // Off-origin navigation (a link clicked inside package UI) goes to
            // the Noxs browser window — validated, isolated, bridge-free.
            WebWindowActivity.open(view.context, url.toString())
            return true
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val file = resolveContent(request.url) ?: return null
            val mime = when (file.extension.lowercase()) {
                "html", "htm" -> "text/html"
                "js" -> "application/javascript"
                "css" -> "text/css"
                "json" -> "application/json"
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "svg" -> "image/svg+xml"
                "woff2" -> "font/woff2"
                else -> "application/octet-stream"
            }
            return runCatching {
                WebResourceResponse(mime, null, file.inputStream())
            }.getOrNull()
        }

        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(view, url, favicon)
            val parsed = android.net.Uri.parse(url)
            onPackageOrigin = parsed.scheme == SCHEME && parsed.host == packageId
        }

        @SuppressLint("SetJavaScriptEnabled")
        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            if (!onPackageOrigin) return
            // Bind the package context before app code runs against the bridge.
            view.evaluateJavascript(
                "window.__NOXS_PACKAGE_ID__ = ${org.json.JSONObject.quote(packageId)};",
                null
            )
        }
    }

    private inner class PackageBridge {
        @JavascriptInterface
        fun post(requestJson: String): String {
            // Origin is re-verified per call (spec §20): window.location,
            // referrer or message content are never the security boundary.
            if (!onPackageOrigin) {
                return bridge.writeOnlyError(
                    NoxsApi.ERR_IDENTITY_MISMATCH, "Package origin not verified."
                )
            }
            return bridge.handle(packageId, requestJson)
        }

        @JavascriptInterface
        fun setEventDispatcher(marker: String) {
            // Register the JS-side dispatcher callback; events are delivered
            // on the Android main thread via evaluateJavascript.
            if (eventDispatcher == null) return
            if (!onPackageOrigin) return
            com.crossberry.noxs.shared.NoxsLog.i("PackageWeb", "event dispatcher armed for $packageId")
        }
    }

    /** Deliver one structured event into the package page. */
    fun dispatchEvent(event: String, payloadJson: String) {
        if (!onPackageOrigin) return
        val dispatcher = eventDispatcher ?: return
        val json = "{\"event\":${quote(event)},\"payload\":$payloadJson}"
        webView.post { dispatcher(json) }
    }

    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    companion object {
        const val SCHEME = "noxs-pkg"

        /**
         * Create a host for a verified package. Package identity and its
         * content directory must already be established by the caller —
         * unknown package ids never reach a WebView.
         */
        fun create(
            webView: WebView,
            packageId: String,
            bridge: NoxsApiBridge,
            contentRoot: File,
            eventDispatcher: ((String) -> Unit)? = null
        ): NoxsPackageWebViewHost? {
            if (!packageId.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}"))) return null
            if (!contentRoot.isDirectory) return null
            return NoxsPackageWebViewHost(webView, packageId, bridge, contentRoot, eventDispatcher)
        }
    }
}
