# Noxs API — @noxs/nx-api

The public SDK NX packages use to talk to the Noxs platform. Packages
interact ONLY through this API — direct Android, filesystem, shell, process
or WebView access never exists for them.

    Package
       ↓
    @noxs/nx-api
       ↓
    Noxs API Bridge            (app/src/main/java/.../runtime/api/NoxsApiBridge.kt)
       ↓
    Permission Manager         (runtime/NoxsPermissionCenter.kt)
       ↓
    Noxs Runtime
       ↓
    Kotlin / Rust / C++
       ↓
    Android / Linux environment

## Modules (spec §4)

| module        | operations                                     | permission(s)                    |
|---------------|------------------------------------------------|----------------------------------|
| `nx.window`   | open/close/minimize/restore/maximize/fullscreen/focus/getState + `on` events | `window.create`, `window.control` |
| `nx.web`      | open                                           | `web.open`                       |
| `nx.terminal` | read/subscribe (default) · write/execute (explicit only) | `terminal.*`            |
| `nx.logs`     | read + `on("update")`                          | `logs.read`, `logs.subscribe`    |
| `nx.events`   | subscribe/unsubscribe structured events        | follows the event prefix         |
| `nx.package`  | info                                           | `package.read`                   |
| `nx.permissions` | query/request states (never grants)        | self-query                       |
| `nx.system`   | info                                           | `system.info`                    |
| `nx.storage`  | read/write (per-package isolated root)         | `storage.package`                |

Usage:

    import { nx } from "@noxs/nx-api";        // or require() from /usr/local/lib/noxs/nx-api

    const win = await nx.window.open({ title: "My tool", width: 420, height: 320 });
    await nx.window.minimize(win.id);
    await nx.web.open("https://example.com");
    const info = await nx.system.info();
    nx.logs.on("update", entry => { ... });

Only APIs that are implemented and permission-controlled are exposed.

## Request envelope (spec §11)

Every call becomes a validated request:

    { "v": "1", "packageId": "tree", "requestId": "req-…",
      "module": "window", "operation": "open", "arguments": { ... } }

The bridge verifies: package identity (host-verified origin, not the JS
claim), known package, API version, request-id shape, permission for the
module+operation, and argument validity. Responses are structured:

    { "ok": true,  "data": { ... } }
    { "ok": false, "error": { "code": "PERMISSION_DENIED", "message": "Permission required." } }

Errors never expose stack traces, private filesystem paths, internal
service URLs, secrets or implementation details.

## Error codes (spec §32)

INVALID_ARGUMENT · INVALID_URL · PERMISSION_DENIED · PACKAGE_NOT_FOUND ·
PACKAGE_VERSION_INVALID · CHECKSUM_FAILED · DOWNLOAD_FAILED ·
WINDOW_NOT_FOUND · WEBVIEW_ERROR · NETWORK_ERROR · TASK_CANCELLED ·
UNSUPPORTED_OPERATION

User-facing messages stay simple; developer diagnostics live in the secure
Noxs logs.

## Package isolation (spec §12)

Each NX package receives an isolated API context:

- per-package data root under the app sandbox (`packageDataDir`)
- package A never sees B's data, windows, files, processes or permissions
- cross-package communication requires an explicitly designed,
  permission-controlled API — none exists by default

## Where the SDK lives

- Guest copy: `/usr/local/lib/noxs/nx-api/` (installed by
  RootfsConfigurator, source of truth in `NoxsNxApiTemplate.kt`)
- Package UI binding: `NoxsPackageWebViewHost.kt` serves
  `noxs-pkg://<packageId>/` content and attaches `window.NoxsBridge` ONLY
  after origin verification (spec §20)
- External websites (`nx ow`) never receive the bridge or the SDK

Attribution: Crossberry / web12-app — the Noxs project.
