/*
 * Noxs — original implementation.
 * @noxs/nx-api — the public Noxs SDK for NX package developers (spec §3-§14).
 *
 * Installed into the environment at /usr/local/lib/noxs/nx-api/ and mirrored
 * at linux-runtime/nx/nx-api/. Packages import it with:
 *
 *     const nx = require("/usr/local/lib/noxs/nx-api/nx-api.js");
 *     // or: import { nx } from "@noxs/nx-api";   (when bundled)
 *
 * Architecture (Noxs platform spec):
 *
 *     Package -> @noxs/nx-api -> Noxs API Bridge -> Permission Manager
 *             -> Noxs Runtime -> Kotlin / Rust / C++ -> Android / Linux
 *
 * The SDK NEVER talks to Android directly. Every call is a validated request
 * over window.NoxsBridge, which is injected only into package-owned WebViews
 * by NoxsPackageWebViewHost after package identity + origin verification.
 * External websites loaded with `nx ow` never receive this bridge or SDK.
 *
 * Encoding convention: inside the raw strings a literal `$` is written as `§`
 * and expanded by .replace('§', '$') (same as the shell templates).
 */
package com.crossberry.noxs.runtime

object NoxsNxApiTemplate {

    val API_JS = """/*
 * @noxs/nx-api — Noxs SDK for NX packages (original implementation).
 * Every privileged call is permission-checked on the Noxs side; the SDK
 * only formats requests and surfaces structured errors.
 */
(function (global) {
    'use strict';

    var VERSION = '1.0.0';
    var API_VERSION = '1';

    function detectPackageId() {
        if (global.__NOXS_PACKAGE_ID__ && typeof global.__NOXS_PACKAGE_ID__ === 'string') {
            return global.__NOXS_PACKAGE_ID__;
        }
        throw new Error('nx-api: package context missing (__NOXS_PACKAGE_ID__ not set)');
    }

    function noBridge() {
        var e = new Error('Noxs API bridge is unavailable inside this context');
        e.code = 'BRIDGE_UNAVAILABLE';
        return e;
    }

    function nextRequestId() {
        nextRequestId.seq = (nextRequestId.seq || 0) + 1;
        return 'req-' + Date.now().toString(36) + '-' + nextRequestId.seq;
    }

    function post(moduleName, operation, args) {
        if (!global.NoxsBridge || typeof global.NoxsBridge.post !== 'function') {
            return Promise.reject(noBridge());
        }
        var request = {
            v: API_VERSION,
            packageId: detectPackageId(),
            requestId: nextRequestId(),
            module: moduleName,
            operation: operation,
            arguments: args || {}
        };
        return new Promise(function (resolve, reject) {
            var raw;
            try {
                raw = global.NoxsBridge.post(JSON.stringify(request));
            } catch (err) {
                reject(err);
                return;
            }
            var response;
            try {
                response = JSON.parse(raw);
            } catch (err) {
                reject(new Error('nx-api: malformed bridge response'));
                return;
            }
            if (response && response.ok) {
                resolve(response.data);
            } else {
                var error = new Error((response && response.error && response.error.message) || 'Request failed');
                error.code = (response && response.error && response.error.code) || 'UNKNOWN';
                reject(error);
            }
        });
    }

    function makeEmitter() {
        var listeners = {};
        return {
            on: function (name, cb) {
                if (typeof cb !== 'function') throw new TypeError('callback required');
                (listeners[name] = listeners[name] || []).push(cb);
                post('events', 'subscribe', { event: name }).catch(function () {});
                return function off() { emitterOff(listeners, name, cb); };
            },
            off: function (name, cb) {
                emitterOff(listeners, name, cb);
                post('events', 'unsubscribe', { event: name }).catch(function () {});
            },
            emitLocal: function (name, payload) {
                (listeners[name] || []).slice().forEach(function (cb) {
                    try { cb(payload); } catch (ignored) {}
                });
            }
        };
    }

    function emitterOff(listeners, name, cb) {
        var list = listeners[name] || [];
        var index = list.indexOf(cb);
        if (index >= 0) list.splice(index, 1);
    }

    var bus = makeEmitter();

    if (global.NoxsBridge && typeof global.NoxsBridge.setEventDispatcher === 'function') {
        global.NoxsBridge.setEventDispatcher(function (rawEvent) {
            try {
                var parsed = JSON.parse(rawEvent);
                bus.emitLocal(parsed.event, parsed.payload);
            } catch (ignored) {}
        });
    }

    function requireWindowId(windowId) {
        if (typeof windowId !== 'string' || !windowId) {
            throw new TypeError('windowId must be a non-empty string');
        }
        return windowId;
    }

    function windowOptions(options) {
        var out = {};
        if (!options || typeof options !== 'object') return out;
        var keys = ['title', 'width', 'height', 'draggable', 'resizable', 'minimizable',
            'maximizable', 'fullscreen', 'focusable', 'closable', 'url', 'content'];
        keys.forEach(function (key) {
            if (options[key] !== undefined) out[key] = options[key];
        });
        return out;
    }

    var windowApi = {
        open: function (options) {
            return post('window', 'open', windowOptions(options)).then(function (data) {
                bus.emitLocal('open', data);
                return data;
            });
        },
        close: function (windowId) { return post('window', 'close', { windowId: requireWindowId(windowId) }); },
        minimize: function (windowId) { return post('window', 'minimize', { windowId: requireWindowId(windowId) }); },
        restore: function (windowId) { return post('window', 'restore', { windowId: requireWindowId(windowId) }); },
        maximize: function (windowId) { return post('window', 'maximize', { windowId: requireWindowId(windowId) }); },
        fullscreen: function (windowId) { return post('window', 'fullscreen', { windowId: requireWindowId(windowId) }); },
        focus: function (windowId) { return post('window', 'focus', { windowId: requireWindowId(windowId) }); },
        getState: function (windowId) { return post('window', 'getState', { windowId: requireWindowId(windowId) }); },
        on: function (name, cb) { return bus.on('window.' + name, cb); },
        off: function (name, cb) { bus.off('window.' + name, cb); }
    };

    var webApi = {
        open: function (url) {
            if (typeof url !== 'string') throw new TypeError('url must be a string');
            return post('web', 'open', { url: url });
        }
    };

    function terminalApi(operation, args) {
        return post('terminal', operation, args);
    }

    var terminalApiObject = {
        read: function (options) { return terminalApi('terminal.read', 'read', options || {}); },
        subscribe: function (cb) {
            return post('terminal', 'subscribe', {}).then(function (data) {
                bus.on('terminal.update', cb);
                return data;
            });
        },
        write: function (text) {
            if (typeof text !== 'string') throw new TypeError('text must be a string');
            return post('terminal', 'write', { text: text });
        },
        execute: function (command) {
            if (typeof command !== 'string' || !command) throw new TypeError('command must be a non-empty string');
            return post('terminal', 'execute', { command: command });
        }
    };

    var logsApi = {
        read: function (options) { return post('logs', 'read', options || {}); },
        on: function (name, cb) {
            if (name !== 'update') throw new Error('nx.logs supports the "update" event only');
            return bus.on('logs.update', cb);
        },
        off: function (name, cb) { bus.off('logs.update', cb); }
    };

    var packageApi = {
        info: function (packageId) { return post('package', 'info', { packageId: packageId || detectPackageId() }); }
    };

    var permissionsApi = {
        query: function (list) { return post('permissions', 'query', { permissions: list || [] }); },
        request: function (list) { return post('permissions', 'request', { permissions: list || [] }); }
    };

    var systemApi = {
        info: function () { return post('system', 'info', {}); }
    };

    var storageApi = {
        read: function (path) { return post('storage', 'read', { path: String(path || '') }); },
        write: function (path, content) { return post('storage', 'write', { path: String(path || ''), content: String(content == null ? '' : content) }); }
    };

    var nx = {
        version: VERSION,
        apiVersion: API_VERSION,
        window: windowApi,
        web: webApi,
        terminal: terminalApiObject,
        logs: logsApi,
        events: bus,
        package: packageApi,
        permissions: permissionsApi,
        system: systemApi,
        storage: storageApi
    };

    global.nx = nx;
    if (typeof module === 'object' && module.exports) {
        module.exports = { nx: nx };
    }
})(typeof window !== 'undefined' ? window : globalThis);
""".replace('§', '$')

    val PACKAGE_JSON = """{
  "name": "@noxs/nx-api",
  "version": "1.0.0",
  "description": "Official Noxs SDK for NX packages — window, web, terminal, logs, storage and system APIs behind the Noxs Permission Manager",
  "main": "nx-api.js",
  "license": "MIT",
  "authors": [
    "Crossberry",
    "web12-app"
  ],
  "repository": {
    "type": "git",
    "url": "https://github.com/web12-app/noxs"
  },
  "keywords": ["noxs", "nx", "nx-api", "sdk"],
  "engines": {
    "node": ">=16"
  }
}
"""

    val API_README = """# @noxs/nx-api

The official Noxs SDK for NX packages. Packages interact with the Noxs
platform only through this SDK — direct Android, filesystem, shell or
WebView access is never available.

    const { nx } = require("@noxs/nx-api");

    const win = await nx.window.open({ title: "My tool", width: 420, height: 320 });
    await nx.web.open("https://example.com");
    const info = await nx.system.info();

## Modules

| Module          | Permission required                     |
|-----------------|-----------------------------------------|
| nx.window       | window.create / window.control          |
| nx.web          | web.open                                |
| nx.terminal     | terminal.read / subscribe / write / execute |
| nx.logs         | logs.read / logs.subscribe              |
| nx.storage      | storage.read / storage.write            |
| nx.package      | package.read                            |
| nx.permissions  | always available (self-query)           |
| nx.system       | system.info                             |
| nx.events       | follows the underlying subscription     |

Every denied call rejects with a structured error:

    { code: "PERMISSION_DENIED", message: "Permission required." }

Stable error codes: INVALID_ARGUMENT, INVALID_URL, PERMISSION_DENIED,
PACKAGE_NOT_FOUND, PACKAGE_VERSION_INVALID, CHECKSUM_FAILED,
DOWNLOAD_FAILED, WINDOW_NOT_FOUND, WEBVIEW_ERROR, NETWORK_ERROR,
TASK_CANCELLED, UNSUPPORTED_OPERATION.

Package isolation: each NX package receives its own API context. Package A
cannot read Package B data, windows, files, processes or permissions.
Cross-package communication requires an explicit, permission-controlled API.

Installed at: /usr/local/lib/noxs/nx-api/
Attribution: Crossberry / web12-app — the Noxs project.
"""
}
