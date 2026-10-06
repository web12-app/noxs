# Noxs Native Browser — `nx ow`

Opens a real website inside a Noxs floating window (Noxs platform spec
§15-§26).

    nx ow https://example.com
    nx ow http://localhost:8080        # works device-globally, not just in-terminal

## Architecture

    nx CLI (guest)
      → web bridge   /var/run/noxs/host/web/requests  (file-based, like the storage bridge)
      → NoxsService watcher (always on while Noxs runs — device-global)
      → NoxsUrlGuard (second validation on the Android side)
      → Noxs Web Window Manager
      → Native Android WebView (android.webkit.WebView)
      → Real HTTP/HTTPS website

The website is loaded directly with `webView.loadUrl(url)` inside
`ui/WebWindowActivity`. **Never used**: iframes, srcdoc, external Android
browsers, Chrome Custom Tabs, browser intents, `javascript:`, `file:`,
`data:`, `intent:` URLs.

## Browser UI (spec §16, §26)

    ┌──────────────────────────────────────┐
    │ ● ● ●        Noxs Web                │
    │ 🔒 https://example.com          ↗    │
    ├──────────────────────────────────────┤
    │                                      │
    │          REAL NATIVE WEBVIEW         │
    │                                      │
    ├──────────────────────────────────────┤
    │ ←   →       ⛶       ↻       ⋯       │
    └──────────────────────────────────────┘

- dark chrome, rounded address bar, security icon (🔒 https / ⚠ http)
- share control; address bar editable; updates after navigation
- bottom toolbar: back · forward · content-fullscreen · reload · menu
- traffic-light controls: close (red) · minimize (yellow) · fullscreen /
  restore (green) — generic rounded shapes, no proprietary assets
- draggable by the header, resizable by the corner handle
- minimum 280dp × 300dp; portrait / landscape / phone / tablet
- minimize collapses to a "Noxs Web — tap to restore" chip

## URL security (spec §18)

`NoxsUrlGuard` is the single validation point, applied on BOTH sides:

- allowed: `http://`, `https://`
- rejected by default: `javascript:`, `file:`, `content:`, `data:`,
  `intent:`, `chrome:`, `android-app:`, `about:`, `blob:`, `ws(s):`,
  `view-source:` — plus any input containing whitespace/control characters
- every URL re-validated before load (guest claim is never trusted)
- the address bar never executes JavaScript

## WebView configuration

JavaScript, DOM storage, zoom, text selection, forms, touch and cookies
(`CookieManager`, third-party cookies off) are enabled. File and content
access are DISABLED. Mixed content is never allowed.

## Isolation (spec §19-§20)

- external websites NEVER receive `window.nx`, `@noxs/nx-api`, terminal,
  filesystem or Android API access — no `addJavascriptInterface` exists on
  the browser WebView at all
- NX package UI uses a SEPARATE WebView class (`NoxsPackageWebViewHost`)
  that verifies package identity + origin before any bridge call
- new windows (`target="_blank"`, `window.open`) are captured and re-enter
  validation through the Noxs Web Window Manager — never an external browser

## Navigation, downloads, uploads

- Back: `webView.canGoBack()` → goBack, else the window closes
- downloads: URL + filename validated (`safeDownloadName`), saved into
  Noxs-managed app-private storage via `DownloadManager` — no path
  traversal, no system-directory writes
- uploads (`<input type="file">`): the Android system file picker only;
  the website receives just the selected file

Attribution: Crossberry / web12-app — the Noxs project.
