/*
 * Noxs — original implementation.
 * NoxsPluginRuntime: connects installed Noxs plugins (dist/plugin.js) to the
 * noxs Plugin API. Each activated plugin runs inside its own WebView as a
 * private, isolated JavaScript context; visible plugin windows are separate
 * WebViews showing plugin HTML.
 *
 * Isolation model (spec §19-§21):
 *  - a plugin can only ever see its own context; there is no shared object
 *    between plugins and no access to private Noxs internals
 *  - every host call goes through the NoxsHost JavascriptInterface and is
 *    permission-checked HERE (in Kotlin) against the plugin.json grants —
 *    a plugin cannot grant itself anything
 *  - plugin webviews have no file/content access, no geolocation, no
 *    multiple windows; navigation is blocked at the WebViewClient
 *  - README content is rendered with JavaScript disabled (details page)
 *  - a crashing plugin is caught per call: errors surface in noxs.log and
 *    the runtime stays alive; deactivate() removes its windows
 */
package com.crossberry.noxs.runtime.plugins

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.crossberry.noxs.shared.NoxsLog
import com.crossberry.noxs.ui.PluginWindowActivity
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Result of a one-shot guest command (terminal permission). */
data class PluginExecResult(val exitCode: Int, val stdout: String, val stderr: String)

object NoxsPluginRuntime {

    private const val TAG = "PluginRuntime"

    class WindowHandle(
        val windowId: String,
        val pluginId: String,
        var title: String,
        var width: Int,
        var height: Int,
        val ops: MutableList<Pair<String, String>> = mutableListOf()
    )

    private class RuntimeHandle(
        val pluginId: String,
        val permissions: Set<String>,
        val webView: WebView,
        val storageFile: File?
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val runtimes = ConcurrentHashMap<String, RuntimeHandle>()
    private val windows = ConcurrentHashMap<String, WindowHandle>()
    private val attached = ConcurrentHashMap<String, PluginWindowActivity>()

    /** Set by NoxsService: runs a one-shot command inside the guest. */
    @Volatile
    var guestRunner: ((command: String) -> PluginExecResult)? = null

    /** Set by NoxsService: launches the visible window activity. */
    @Volatile
    var windowLauncher: ((windowId: String, pluginId: String, title: String, width: Int, height: Int) -> Unit)? =
        null

    // ------------------------------------------------------------ lifecycle

    /**
     * Activates a plugin: private WebView + Noxs Plugin SDK bootstrap (when
     * the plugin declares an SDK) + plugin.js + activate(noxs).
     *
     * [sdkJs] is the verified SDK index.js for the plugin's selected SDK
     * release (from NoxsPluginManager.sdkRuntimeJs). A plugin developed on
     * an SDK newer than 0.0.1 is never executed without its SDK: activation
     * is refused and false returned — code never runs unvalidated.
     *
     * @return true when the plugin was activated.
     */
    fun activate(context: Context, plugin: InstalledPlugin, sdkJs: String? = null): Boolean {
        if (routines().containsKey(plugin.meta.id)) return true
        val declaredSdk = PluginSemver.parse(plugin.meta.sdkVersion) ?: PluginSemver.ZERO
        if (declaredSdk > PluginSemver(0, 0, 1) && sdkJs == null) {
            NoxsLog.w(TAG, "plugin ${plugin.meta.id} requires Noxs Plugin SDK ${plugin.meta.sdkVersion} — refusing to run unverified")
            return false
        }
        val pluginJs = runCatching {
            java.io.File(plugin.dir, PluginJson.RUNTIME_ENTRY).readText(Charsets.UTF_8)
        }.getOrElse {
            NoxsLog.w(TAG, "plugin ${plugin.meta.id} has no readable entry")
            return false
        }
        mainHandler.post {
            runCatching {
                val webView = buildRuntimeWebView(context.applicationContext, plugin, pluginJs, sdkJs)
                runtimes[plugin.meta.id] = RuntimeHandle(
                    plugin.meta.id,
                    plugin.meta.permissions.toSet(),
                    webView,
                    java.io.File(plugin.dir, PluginStorageFile.FILE_NAME)
                )
                NoxsLog.i(TAG, "plugin activating: ${plugin.meta.id}")
            }.onFailure {
                NoxsLog.w(TAG, "activation error (${it.javaClass.simpleName})")
                runtimes.remove(plugin.meta.id)
            }
        }
        return true
    }

    fun deactivate(pluginId: String) {
        windows.values.filter { it.pluginId == pluginId }.forEach { handle ->
            // finish() is an Activity call — always on the main thread.
            mainHandler.post { attached[handle.windowId]?.finish() }
            windows.remove(handle.windowId)
        }
        runtimes.remove(pluginId)?.let { handle ->
            mainHandler.post {
                runCatching {
                    handle.webView.evaluateJavascript(
                        "try{ __NOXS_PLUGIN__.deactivate(); }catch(e){}",
                        null
                    )
                    handle.webView.destroy()
                }
            }
        }
    }

    fun isActive(pluginId: String): Boolean = routines().containsKey(pluginId)

    fun activePluginIds(): List<String> = routines().keys.toList()

    fun shutdown() {
        windows.clear()
        attached.clear()
        runtimes.values.forEach { handle ->
            mainHandler.post { runCatching { handle.webView.destroy() } }
        }
        runtimes.clear()
    }

    // ----------------------------------------------------------- host calls

    /** noxs.ui.createWindow — requires the ui permission. */
    fun createWindow(pluginId: String, paramsJson: String): String {
        requirePermission(pluginId, PluginPermissions.UI)
        val params = runCatching { JSONObject(paramsJson) }.getOrDefault(JSONObject())
        val windowId = "plg-${pluginId.take(16)}-${System.nanoTime().toString(36)}"
        val handle = WindowHandle(
            windowId = windowId,
            pluginId = pluginId,
            title = params.optString("title").ifBlank { pluginId },
            width = params.optInt("width", 420).coerceIn(200, 1200),
            height = params.optInt("height", 480).coerceIn(200, 1000)
        )
        windows[windowId] = handle
        val requestedId = params.optString("id")
        NoxsLog.i(TAG, "window requested: $requestedId -> $windowId")
        windowLauncher?.invoke(windowId, pluginId, handle.title, handle.width, handle.height)
        return windowId
    }

    /**
     * Buffered window operations (setHTML/setText/show/hide/close). Ops are
     * replayed when the window activity attaches, so plugins may configure
     * a window before showing it. [callerPluginId] must own the window.
     */
    fun windowOp(callerPluginId: String, windowId: String, op: String, arg: String): Boolean {
        val handle = windows[windowId] ?: return false
        if (handle.pluginId != callerPluginId) throw SecurityException("window mismatch")
        val activity = attached[windowId]
        if (activity != null) {
            mainHandler.post { runCatching { activity.applyOp(op, arg) } }
        } else {
            synchronized(handle.ops) { handle.ops.add(op to arg) }
        }
        if (op == "close" || op == "hide") {
            // Visual close is performed by the activity; drop our record.
            if (op == "close") {
                mainHandler.post { attached[windowId]?.finish() }
                windows.remove(windowId)
            }
        }
        return true
    }

    /** Clicks inside a visible window are delivered back to the plugin. */
    fun clickFromWindow(callerPluginId: String, windowId: String, elementId: String) {
        val handle = windows[windowId] ?: return
        if (handle.pluginId != callerPluginId) {
            NoxsLog.w(TAG, "cross-plugin click refused")
            return
        }
        dispatchToRuntime(
            handle.pluginId,
            "NoxsEventBus.dispatch(" + quote(windowId) + "," + quote(elementId) + ",null);"
        )
    }

    fun dispatchToRuntime(pluginId: String, js: String) {
        mainHandler.post {
            runCatching { runtimes[pluginId]?.webView?.evaluateJavascript(js, null) }
        }
    }

    /** noxs.terminal.exec — requires the terminal permission. */
    fun exec(pluginId: String, requestId: String, command: String): Boolean {
        requirePermission(pluginId, PluginPermissions.TERMINAL)
        val runner = guestRunner ?: return false
        Thread {
            val result = runCatching { runner(command) }
                .getOrElse { PluginExecResult(127, "", "Noxs could not run the command") }
            val payload = JSONObject().put("exitCode", result.exitCode)
                .put("stdout", result.stdout).put("stderr", result.stderr)
            dispatchToRuntime(pluginId, "__noxsResolveExec(" + quote(requestId) + "," + quote(payload.toString()) + ");")
        }.start()
        return true
    }

    /**
     * sdk.storage get/set/remove/keys — the host side of the SDK "storage"
     * feature (Noxs Plugin SDK 0.0.2+). Requires the storage permission;
     * data lives in the plugin's own directory and is removed with it.
     * Returns the JSON-encoded value for "get" (null on miss), a JSON array
     * for "keys", null otherwise. Bounded by PluginStorageFile.
     */
    fun storageOp(pluginId: String, op: String, key: String, value: String): String? {
        requirePermission(pluginId, PluginPermissions.STORAGE)
        val file = routines()[pluginId]?.storageFile ?: return null
        val data = PluginStorageFile.load(file)
        val safeKey = key.take(PluginStorageFile.MAX_KEY_LENGTH)
        return when (op) {
            "get" -> data[safeKey]?.let { JSONObject.quote(it) }
            "set" -> {
                if (value.length > PluginStorageFile.MAX_VALUE_BYTES) return null
                if (PluginStorageFile.store(file, data + (safeKey to value))) "\"ok\"" else null
            }
            "remove" -> {
                PluginStorageFile.store(file, data - safeKey)
                "\"ok\""
            }
            "keys" -> JSONObject(data.keys.toList()).toString()
            else -> null
        }
    }

    fun windowTitle(windowId: String): String? = windows[windowId]?.title

    fun handleFor(windowId: String): WindowHandle? = windows[windowId]

    fun attachActivity(windowId: String, activity: PluginWindowActivity) {
        attached[windowId] = activity
    }

    fun detachActivity(windowId: String) {
        attached.remove(windowId)
    }

    fun closeWindow(windowId: String) {
        // finish() is an Activity call — always on the main thread.
        mainHandler.post { attached[windowId]?.finish() }
        windows.remove(windowId)
    }

    // ------------------------------------------------------- webview builds

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildRuntimeWebView(
        context: Context,
        plugin: InstalledPlugin,
        pluginJs: String,
        sdkJs: String?
    ): WebView {
        val webView = WebView(context)
        configure(webView)
        webView.addJavascriptInterface(
            NoxsHostInterface(plugin.meta.id, null),
            "NoxsHost"
        )
        webView.webViewClient = object : WebViewClient() {
            private var booted = false
            override fun onPageFinished(view: WebView, url: String) {
                if (booted) return
                booted = true
                // Inject only once the blank page exists: shim -> SDK ->
                // plugin.js -> activate(noxs). Every step is guarded so a
                // broken plugin degrades to a log line, never a crash.
                view.evaluateJavascript(BOOTSTRAP_JS, null)
                if (sdkJs != null) {
                    view.evaluateJavascript("(function(){(0,eval)(" + quote(sdkJs) + ");})();", null)
                }
                view.evaluateJavascript("(function(){(0,eval)(" + quote(pluginJs) + ");})();", null)
                view.evaluateJavascript(
                    "try{ __NOXS_PLUGIN__.activate(noxs); }catch(e){ NoxsHost.log('activation failed: ' + e); }",
                    null
                )
                NoxsLog.i(TAG, "plugin activated: ${plugin.meta.id}")
            }
        }
        webView.loadUrl("about:blank")
        return webView
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun buildWindowWebView(context: Context, pluginId: String, windowId: String): WebView {
        val webView = WebView(context)
        configure(webView)
        webView.addJavascriptInterface(NoxsHostInterface(pluginId, windowId), "NoxsHost")
        return webView
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true              // plugin UI is scripted by design
            domStorageEnabled = false             // no persistent storage surface
            allowFileAccess = false
            allowContentAccess = false
            allowUniversalAccessFromFileURLs = false
            allowFileAccessFromFileURLs = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
    }

    private fun requirePermission(pluginId: String, permission: String) {
        val runtime = routines()[pluginId]
            ?: throw SecurityException("plugin is not active")
        if (permission !in runtime.permissions) {
            NoxsLog.w(TAG, "permission refused: $pluginId -> $permission")
            throw SecurityException(permission)
        }
    }

    private fun routines(): Map<String, RuntimeHandle> = runtimes

    /** JSON-escapes a Kotlin string into a JavaScript string literal. */
    private fun quote(text: String): String = JSONObject.quote(text)

    // ------------------------------------------------- JavascriptInterface

    /**
     * The ONLY surface a plugin can reach. Every method re-checks the
     * plugin's declared permissions; failures are logged, never crash.
     */
    class NoxsHostInterface(
        private val pluginId: String,
        private val windowId: String?
    ) {
        @JavascriptInterface
        fun log(message: String): Unit {
            NoxsLog.i(TAG, "plugin $pluginId: ${message.take(200)}")
        }

        @JavascriptInterface
        fun createWindow(paramsJson: String): String = guarded {
            NoxsPluginRuntime.createWindow(pluginId, paramsJson)
        } ?: ""

        @JavascriptInterface
        fun windowOp(targetWindowId: String, op: String, arg: String): Boolean = guarded {
            NoxsPluginRuntime.windowOp(pluginId, targetWindowId, op, arg)
        } ?: false

        @JavascriptInterface
        fun click(targetWindowId: String, elementId: String): Unit {
            runCatching {
                NoxsPluginRuntime.clickFromWindow(pluginId, targetWindowId, elementId.take(64))
            }
        }

        @JavascriptInterface
        fun exec(requestId: String, command: String): Boolean = guarded {
            NoxsPluginRuntime.exec(pluginId, requestId.take(64), command.take(2000))
        } ?: false

        @JavascriptInterface
        fun storageOp(op: String, key: String, value: String): String? = guarded {
            NoxsPluginRuntime.storageOp(pluginId, op.take(16), key.take(PluginStorageFile.MAX_KEY_LENGTH), value)
        }

        private fun <T> guarded(block: () -> T): T? = try {
            block()
        } catch (e: SecurityException) {
            NoxsLog.w(TAG, "host call refused for $pluginId: ${e.message}")
            null
        } catch (t: Throwable) {
            NoxsLog.w(TAG, "host call failed for $pluginId: ${t.javaClass.simpleName}")
            null
        }
    }

    // ----------------------------------------------------------- bootstrap

    /**
     * Injected into every plugin webview before plugin code runs. Defines
     * the `noxs` object, the window shim, the event bus and the exec
     * resolver. Windows additionally get the NoxsWindow page helper.
     */
    const val BOOTSTRAP_JS = """(function(){
  if (window.__noxsShim) return; window.__noxsShim = true;
  var handlers = {};
  var pending = {};
  function deliver(id, ev, data){
    var cbs = handlers[id + ':' + ev] || [];
    for (var i = 0; i < cbs.length; i++){
      try { cbs[i](data); } catch (e) { NoxsHost.log('event handler error: ' + e); }
    }
  }
  function win(id){
    return {
      id: id,
      show: function(){ NoxsHost.windowOp(id, 'show', ''); },
      hide: function(){ NoxsHost.windowOp(id, 'hide', ''); },
      close: function(){ NoxsHost.windowOp(id, 'close', ''); },
      setHTML: function(html){ NoxsHost.windowOp(id, 'setHTML', String(html)); },
      setText: function(sel, text){
        NoxsHost.windowOp(id, 'setText', JSON.stringify([String(sel), String(text)]));
      },
      on: function(ev, cb){
        var key = id + ':' + ev;
        (handlers[key] = handlers[key] || []).push(cb);
      },
      emit: function(ev, data){ deliver(id, ev, data); }
    };
  }
  window.NoxsEventBus = {
    dispatch: function(id, ev, payloadJson){
      var payload = null;
      try { payload = payloadJson ? JSON.parse(payloadJson) : null; } catch (e) {}
      deliver(id, ev, payload);
    }
  };
  window.noxs = {
    log: function(m){ NoxsHost.log(String(m)); },
    ui: {
      createWindow: function(p){
        var id = NoxsHost.createWindow(JSON.stringify(p || {}));
        if (!id) throw new Error('window refused by the Noxs host');
        return win(id);
      }
    },
    terminal: {
      exec: function(cmd){
        return new Promise(function(resolve, reject){
          var rid = 'e' + Date.now() + Math.floor(Math.random() * 1000000);
          pending[rid] = { resolve: resolve, reject: reject };
          if (!NoxsHost.exec(rid, String(cmd))){
            delete pending[rid];
            reject(new Error('terminal permission denied'));
          }
        });
      },
      createSession: function(){
        return Promise.reject(new Error('interactive sessions are not available in this Noxs version'));
      }
    }
  };
  window.__noxsResolveExec = function(rid, json){
    var p = pending[rid]; if (!p) return; delete pending[rid];
    try { p.resolve(JSON.parse(json)); } catch (e) { p.reject(e); }
  };
  // Visible window page helper: renders HTML and reports clicks.
  window.NoxsWindow = {
    setHTML: function(html){
      document.open(); document.write(html); document.close();
    },
    setText: function(sel, text){
      var el = document.querySelector(sel);
      if (el) el.textContent = text;
    },
    interceptClicks: function(winId){
      document.addEventListener('click', function(e){
        var el = e.target;
        while (el && el !== document.body && !(el.id)) el = el.parentElement;
        if (el && el.id) { NoxsHost.click(winId, el.id); }
      }, true);
    }
  };
})();"""
}
