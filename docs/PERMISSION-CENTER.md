# Noxs Permission Center

The central authority behind every privileged Noxs API request (Noxs API
spec §6-§10).

    Application
       ↓
    Permission Center          (ui/PermissionCenterActivity.kt)
       ↓
    Permission Manager         (runtime/NoxsPermissionCenter.kt)
       ↓
    Android / Noxs Runtime
       ↓
    API authorization

## Principles

- Every privileged API request passes permission validation — no exceptions.
- Packages are never trusted because they are installed.
- WebView JavaScript is never trusted by default.
- Only the minimum permissions required are requested.
- Android permission state is never faked: the center probes the real OS
  state (`ContextCompat`/`PackageManager`) and never claims access until
  Android confirms it.
- Special Android access opens the appropriate Android settings screen and
  verifies the resulting state afterwards.

## Permission levels and states (spec §8)

Levels: `required` · `optional` · `feature-dependent`

States: `Allowed` · `Not granted` · `Denied` · `Restricted` · `Not supported`

An absent decision means NOT granted. `feature-dependent` permissions map
to real feature toggles (for example `background.keepawake` follows the
Settings → Background switch).

## Catalog (runtime/NoxsPermissionCatalog.kt)

Groups (spec §9): Notifications · Storage / Files · Background activity ·
Device features · System integration · Noxs environment access

| group       | ids                                                              |
|-------------|------------------------------------------------------------------|
| notifications | notifications.show (POST_NOTIFICATIONS)                        |
| storage     | storage.read, storage.write (SAF picker — never all-files access)|
| background  | background.tasks (FOREGROUND_SERVICE), background.keepawake (WAKE_LOCK) |
| device      | device.vibrate                                                   |
| system      | window.create, window.control, web.open, web.control             |
| environment | terminal.read/subscribe/write/execute, logs.read/subscribe, storage.package, package.read/install, system.info |

Default package access is read/subscribe only. `terminal.write` and
`terminal.execute` are NEVER granted automatically (spec §13) — execution
always requires an explicit user decision.

## The Network row

Ordinary Internet access is NOT presented as an Android runtime permission
(spec §9). It is enabled through the application's normal network
capability and shown as informational status only:

    Network
    ✓ Available
    Internet access is enabled for Noxs.

## First-launch flow (spec §9)

    Noxs Launch
      → Permission Center
      → detect required permissions
      → show status (search + groups)
      → user grants/denies
      → verify (each pending Android permission asked at most once per session)
      → environment setup
      → Noxs Home

The flow runs once (flag `permission_onboarding_done` in noxs_settings);
the Permission Center stays permanently available in Settings →
Permissions. Denied permissions stay honest as "Not granted" — they are
never silently hidden.

## Storage and real Android permissions

- storage.read/write use the SAF document-tree picker (existing
  StorageActivity flow); Noxs never requests all-files access.
- POST_NOTIFICATIONS is the only true runtime permission in the current
  catalog and is requested on API 33+ only, through the real Android API.
- WAKE_LOCK / FOREGROUND_SERVICE / VIBRATE are install-time permissions;
  the center reflects the actual OS answer via the probe.

State persists as `noxs-permissions.json` inside the app sandbox with a
tamper guard: unknown ids never survive a load.

Attribution: Crossberry / web12-app — the Noxs project.
