# Noxs Background Activity Center

The Activity Center turns Noxs into a real Linux workstation: work keeps
running safely in the background, and the user always sees — and controls —
what is running. This document describes the shipped architecture (v0.1.8+).

## Goals

- Sessions and long work continue while the app is backgrounded (within
  Android's normal foreground-service rules — no background-execution abuse).
- One persistent, live notification mirrors the current activity state.
- `exit` closes only the current shell; Noxs shuts down only when the last
  shell exits and nothing else is running.
- Stopping one activity never touches unrelated sessions or processes.

## Architecture

```
TerminalSession ── NoxsSessionManager ── NoxsActivityCenter ── NoxsService ── Notification
        (PTY)            (ownership)        (single source of      (foreground      (Open/Stop)
                                              truth + persistence)   service)
        ▲                                                                        │
        └────────────── TerminalActivity (observer + floating panel) ◄───────────┘
```

- **NoxsActivityCenter** (`runtime/NoxsActivityCenter.kt`) — app-scoped
  singleton created in `NoxsApplication`, deliberately outside every
  Activity/Service. Survives Activity recreation, configuration changes and
  service restarts. Emits `StateFlow<List<NoxsActivityRecord>>`.
- **NoxsActivityRecord** (`runtime/NoxsActivityModels.kt`) — `activityId`,
  `sessionId`, `title`, `command`, `kind`, `status`, `startedAt`, `finishedAt`,
  `progress`, `outputSummary`, `pid`, `workingDirectory`, `exitCode`, `pinned`.
  Statuses: `QUEUED → STARTING → RUNNING → COMPLETED | FAILED | STOPPING →
  STOPPED`.
- **Persistence** — line-based, URL-escaped records in
  `filesDir/activity/history.tsv`. Stores metadata only (never terminal output
  history, passwords or secrets). Records that were RUNNING when the process
  died surface as FAILED ("Noxs was closed") instead of phantom RUNNING work.
- **NoxsService** — foreground service (`dataSync` type) on the dedicated
  `noxs_activity` notification channel. The notification shows the newest
  running activity, its parsed progress bar when a percentage is available
  (e.g. `████████░░ 82%` in terminal output), and **Open** / **Stop** actions.
  Updates are throttled to ≥800 ms — terminal output never spams it.
- **Ownership** (`runtime/NoxsProcessControl.kt` + `NoxsProcSampler.kt`) —
  every process descending (ppid chain) from a session's PTY child belongs to
  that session. Stop is always targeted: SIGTERM → up to 3 s grace → SIGKILL
  only if still alive. No "kill by name" sweeps, ever.
- **Resource monitoring** — `/proc/<pid>/stat` sampling attributed per
  session tree; CPU% normalized per core, RSS summed, only polled while the
  floating panel is visible (2 s cadence).

## User surfaces

- **Live notification** — `Noxs` + `● Running: npm run build` (+progress) with
  `[Open]` and `[Stop]`. `Open` focuses the owning session and raises the
  floating panel; `Stop` safely terminates that one activity.
- **Floating status panel** — compact in-app control window over the terminal:
  session, process, CPU, RAM, uptime, directory, PID, last output line and
  Terminal / Logs / Details / Stop buttons. Long-press the terminal toolbar's
  **Activity** button to toggle it for the current session.
- **Activity Center screen** — terminal toolbar **Activity** button or drawer
  → *Activity Center*. Sections RUNNING / COMPLETED / FAILED / STOPPED, per
  item details + logs + Terminal + Stop, *Clear finished* (never touches
  active work), and the global **Stop Noxs** with confirmation.

## Smart exit (spec 8–10)

```
exit in Terminal A ──► A closes ──► other sessions/background work running?
        ├─ yes ──► "Session closed — N session(s) still running" (Noxs stays alive)
        └─ no  ──► background activity? ──► yes: keep alive / no: graceful shutdown
```

The service stops, the notification is removed and resources are released
only when nothing remains. A shell that dies immediately after launch
(bootstrap failure) is treated as a crash, not a user `exit`, so the app
stays open for diagnosis.

## Setup bootstrap hardening (same release)

- Specific failure messages: network vs **storage** (`err_storage_space`)
  vs corrupt download — no more blanket "check your connection".
- Free-space prechecks before download (artifact + 128 MB) and extraction
  (4× archive).
- **Resumable downloads** (`Range` resume of the `.part` file) with a
  corrupt-resume restart, and a **mirror candidate**
  (`github.com/.../raw/...`) when `raw.githubusercontent.com` is unreachable.
  Pinned SHA-256 verification is unchanged.

## Testing

JVM tests (`app/src/test`): activity lifecycle, multi-session semantics,
pinned-service keep-alive, safe-stop state machine (TERM→wait→KILL), output
summary + progress parsing, persistence round-trip, stale-record handling,
proc-tree attribution, CPU/RSS aggregation, and installer mirror/reserve
planning. Run with `./gradlew :app:testDebugUnitTest :terminal-emulator:test`.
