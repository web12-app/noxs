# Terminal sessions

- Sessions are `TerminalSession` objects owned by `NoxsSessionManager` and kept
  alive by the `NoxsService` foreground service (notification shows the count).
- Each session = one PTY (libnoxs-pty: forkpty + execve of proot) or, when the
  native lib is unavailable, a pipe-based fallback (`ProcessBuilder`).
- Quota: `MAX_SESSIONS` (default 8) enforced in the app; per-child process and
  fd limits apply in-sandbox via `/etc/noxs/resources.conf` + ulimits.
- Session lifecycle: close tab → SIGTERM → SIGKILL fallback; app killed by
  Android → sessions die with the process (no daemon escapes the sandbox) and
  the UI recovers by creating fresh sessions. Working directories persist
  through `/home/noxs`; shell state does not (documented V1 behavior).
- Emulator scrollback default: 1000 lines (settings-tunable roadmap item).
