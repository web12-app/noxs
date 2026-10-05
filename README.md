# Noxs

**A Debian-optimized Linux environment for Android — a small Linux computer in your pocket.**

Noxs runs a real **Debian 12 (Bookworm)** userspace — bash, APT, sudo, Unix
domain sockets under `/run` and `/var/run`, user accounts, services and the
full Linux filesystem hierarchy — inside the Android application sandbox,
using **proot** (no root required, no security bypass, ARM64-first).

```
$ whoami
noxs
$ pwd
/home/noxs
$ uname -a
Linux noxs 6.x.x-android13 #1 SMP aarch64 GNU/Linux
$ sudo apt install git          # password-protected, inside the sandbox
$ noxs code start               # code-server (VS Code in browser), 127.0.0.1:8080
```

## Highlights

- 🐧 **Real Debian 12 userspace** — official rootfs, APT package management,
  glibc binaries; nothing recompiled against bionic.
- 📁 **True FHS filesystem** — `/bin /etc /home/noxs /run /var/run /var/log …`
- 🔐 **Honest sudo** — Debian's real sudo, password-protected, scoped to the
  Noxs sandbox. Never Android root. Never a security bypass.
- 🔌 **Unix domain sockets** — `unix:///var/run/noxs/…` + Android↔Debian IPC.
- 🖥️ **Real terminal** — PTY (native `forkpty`), VT/xterm engine (clean-room,
  original), colors/Unicode/scrollback/resize, multiple sessions.
- 🧩 **code-server integration** — `noxs code install|start|stop|restart|status`,
  local-only by default.
- 📊 **Resource quotas** — CPU/memory/process/session/storage limits that never
  override Android's own.
- 🛡️ **Sandbox-first security** — SHA-256-pinned bootstrap, path-traversal-safe
  extraction, argv-only execution, capability transparency.

## Project layout

```
noxs/
├── app/                    Android application (UI, runtime, native PTY)
│   └── src/main/cpp/       noxs-pty.c — forkpty/execve JNI bridge
├── terminal-emulator/      Clean-room VT/xterm engine (pure Kotlin, tested)
├── terminal-view/          Android terminal View + renderer + extra keys
├── noxs-shared/            Security core (checksums, TarGuard, users, quotas)
├── linux-runtime/          bootstrap manifests, rootfs overlay, noxs CLI,
│                           service/socket/process managers
├── sandbox/                sandbox docs (filesystem, users, permissions, sessions)
├── packages/               apt config, manifest schema, repository references
├── scripts/                bootstrap.sh · build-rootfs.sh · package.sh · test.sh
├── docs/                   ARCHITECTURE · BOOTSTRAP · ACTIVITY-CENTER · SECURITY ·
│                           TESTING · UNIX-SOCKETS · FAQ
├── .github/workflows/      android-ci.yml (build + validate + release)
└── LICENSE, NOTICE.md      Apache-2.0
```

## Build

Requirements: JDK 17, Android SDK (platform 34) + NDK 26.3 + CMake 3.22.1.

```bash
scripts/bootstrap.sh          # verify toolchain, provision wrapper
./gradlew assembleDebug       # → app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest   # JVM tests (security core, VT engine, launcher)
scripts/test.sh               # full validation incl. manifest + sync checks
./gradlew assembleRelease     # set NOXS_KEYSTORE_* env vars for signing
```

CI builds everything automatically (see `.github/workflows/android-ci.yml`).

## First run

1. Open Noxs → **Set up Linux environment**
2. Set the `noxs` user password (used by `sudo` inside the sandbox)
3. Bootstrap downloads + verifies Debian 12 + proot (ARM64 prioritized)
4. Terminal opens: `noxs@android:~$`

Everything after bootstrap works offline.

## Background Activity Center

Sessions, commands, services and code-server keep running behind the
foreground service while you use other apps. A single live notification shows
what is running (with progress) and offers Open / Stop; the in-app Activity
Center and floating status panel give full control. `exit` closes only the
current shell — Noxs shuts down only when the last shell exits with no
background work left. Details: [docs/ACTIVITY-CENTER.md](docs/ACTIVITY-CENTER.md).

## Security posture

Noxs runs entirely inside Android's app sandbox. It does not modify the
system, does not bypass SELinux or verified boot, and `sudo` manages the Noxs
environment only. Details: [docs/SECURITY.md](docs/SECURITY.md) ·
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) · [NOTICE.md](NOTICE.md).

## License

Apache-2.0 — original implementation, no third-party code or branding
redistributed. Runtime artifacts (Debian rootfs, proot, code-server) are
fetched from their official sources under their own licenses.
