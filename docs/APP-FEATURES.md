# Noxs Android app — current feature reference

This guide describes the behavior present in this repository's app code. It covers the Android screens and the Linux runtime they manage; it is not a roadmap. Where the UI or older project documentation promises more than the code currently wires up, the implementation caveats near the end call that out explicitly.

## What the app is

Noxs is an Android app that downloads and configures a Debian 12 (Bookworm) root filesystem in the app's private storage, then launches Linux commands through **proot**. It does not require Android root. A Noxs “root” session is proot's simulated UID 0 inside that userspace; it is not Android device root and does not grant privileges beyond the Android app's own UID.

The app's normal storage layout is rooted at `filesDir/noxs/`:

| Path | Purpose |
| --- | --- |
| `rootfs/` | Debian filesystem and the default `/home/noxs` home directory |
| `bin/` | Android-side runtime staging location (proot is packaged in the APK instead) |
| `run/` | App-private host-side runtime files, bind-mounted into the Linux rootfs |
| `cache/` | Downloaded bootstrap archives |
| `logs/` | App-side log storage location |
| `tmp/` | App-side temporary files used by proot |

The app disables Android backup and excludes the Noxs data directory from backup/transfer rules. It requests network, foreground-service, notification, wake-lock, and vibration permissions; it does not request broad shared-storage or root permissions.

## App screens

| Screen | Current behavior |
| --- | --- |
| **Welcome** | Detects whether setup is complete. Offers setup/settings and enables terminal entry once the install marker, rootfs, and bundled proot are present. |
| **Linux setup** | Collects and confirms a noxs-user password, then shows bootstrap steps, download progress, logs, and the resulting rootfs digest. Existing installs can be opened without downloading again. |
| **Terminal** | Hosts multiple sessions, session tabs, a drawer, manager shortcuts, shell command input, copy/paste/clear controls, display-mode toggle, extra keys, and a resource-status strip. |
| **Files** | Browses the rootfs (starting at `/home/noxs` when present), displays text from a selected file, and deletes files/directories after a confirmation. It does not provide file editing or import/export. |
| **Packages** | Searches APT package names, lists installed packages, runs `apt-get update`, and can open a new terminal session with an install command. |
| **Users** | Lists `/etc/passwd`, creates users with Debian `useradd`, attempts password changes through `chpasswd` stdin, and can remove users other than `noxs`. Username validation follows the app's lowercase Debian-style rules. |
| **Processes** | Shows app-managed terminal sessions and a `ps` snapshot from the Linux userspace. A process row can request a kill; PID 1 and lower are protected by the control layer. |
| **Services** | Shows the optional code-server status/action menu and service definitions found under `/etc/noxs/services`. Service actions are delegated to `noxs-service` when that helper is available. |
| **Environment** | Reads and edits key/value lines in the rootfs `/etc/environment` file, including deleting a selected key. |
| **Storage** | Reports rootfs and APT-cache sizes, clears cached `.deb` files, clears the in-memory diagnostic log ring, and offers a confirmed rootfs reset. Reset removes `/home/noxs` along with the rest of the rootfs. |
| **Security** | Displays the app's architecture, PTY/socket probes, storage status, Android API level, SELinux status, and other capability information, plus the sandbox-only privilege notice. |
| **Settings** | Exposes terminal toggles, selected resource quotas, a rootfs URL field, and DNS nameserver editing. Some controls are currently stored or displayed but are not connected to runtime behavior; see [Known implementation caveats](#known-implementation-caveats). |
| **Diagnostics** | Displays the capability snapshot and a bounded in-memory log ring; can copy the log entries to the clipboard. |
| **About** | Shows product/licensing text, app ID, userspace suite, and engine information. |

## First-run bootstrap

`SetupActivity` delegates installation to `NoxsInstaller`. The intended pipeline is:

1. Select an ABI and create app-private directories.
2. Load `assets/bootstrap/<abi>/bootstrap.manifest`.
3. Verify that the bundled proot binary is available in Android's extracted native-library directory. Proot is not downloaded by the installer.
4. Download the Debian rootfs archive to a `.part` file, retrying network I/O up to three times, or reuse the cached archive.
5. Check SHA-256 when the manifest supplies a digest; extract through the guarded tar reader.
6. Configure Debian paths and APT sources, create the default user/home, prepare runtime directories, and write the install marker.
7. Defer `apt update` until the user requests it; the basic environment is intended to run offline after bootstrap.

The manifests in the app assets describe Bookworm rootfs archives for `arm64-v8a`, `x86_64`, and `armeabi-v7a`. The Android Gradle ABI filter currently packages native libraries only for **ARM64 and x86_64**, so the ARMv7 manifest does not by itself make an ARMv7 APK available. The packaged proot runtime and its companion libraries are under `app/src/main/jniLibs/`.

The shared tar extractor supports `.tar`, `.tar.gz`, and `.tar.xz`. It rejects absolute/traversal paths, unsafe links and special device/fifo entries, strips privileged mode bits, and caps expanded file data at 6 GiB. Dedicated JVM tests exercise these checks. Extraction happens inside the app's private rootfs directory.

The setup wizard passes the entered password to the installer, which attempts to send it to `chpasswd` on stdin and overwrites the supplied character array after use. It does not save the password in app preferences. This is not a guarantee that every temporary JVM string copy is erased from memory. In addition, the currently installed `sudo` wrapper does not use that password; see the security caveat below.

## Terminal and sessions

### Session lifecycle

- `NoxsService` is a non-exported foreground service. It owns the session manager and Android-side control socket while running, and updates a notification with the session count.
- Each terminal session launches a proot command with an argv array. The default session enters as the `noxs` user; a root session starts a login shell as proot's simulated UID 0.
- The preferred transport is a native PTY implemented in `app/src/main/cpp/noxs-pty.c` (`forkpty`-style PTY setup, `execve`, resize ioctls, and signals). If the JNI library is unavailable or PTY creation fails, the session class falls back to `ProcessBuilder` pipes.
- The default session limit is 8, configurable within 1–32. Closing a session sends termination and the PTY path has a force-kill fallback. Sessions are process-bound: if Android kills the app process, their shell state ends; files in `/home/noxs` persist.

### Terminal interface and emulator

The terminal screen offers two renderers:

- **Text view**: selectable transcript in a styled Android `TextView`, with ANSI foreground/background colors, bold, and a visible cursor marker. The command bar sends a line to the active shell.
- **TTY view**: canvas renderer backed by the terminal grid. It supports terminal-focused IME input, hardware key mapping, scrolling, and a long-press copy/paste menu. Entering an alternate screen (for example, a full-screen editor) automatically switches to TTY view.

The extra-keys row provides Escape, Tab, Ctrl/Alt latches, arrows, Ctrl-C/Ctrl-L, and common shell characters. Hardware arrows, navigation/function keys, Ctrl-letter controls, volume-key arrows, and bracketed paste are mapped to terminal byte sequences. The default scrollback is 1,000 lines.

`terminal-emulator` is a JVM-only VT100/VT220/xterm-subset engine. Implemented sequences include cursor movement and positioning, erase/insert/delete operations, scrolling regions, alternate screens, SGR (16/256/truecolor and common attributes), OSC titles, device/status replies, tabs, DEC special graphics, UTF-8 decoding, wide-character cells, and bracketed paste. It is a subset rather than a complete xterm implementation; resize does not reflow prior lines and combining marks are not attached to preceding glyphs.

### Automatic startup fallback

If a proot session exits unsuccessfully within about 2.5 seconds, the session manager replaces it with an interactive `/system/bin/sh` (or `/bin/sh`) whose working directory is a physical directory under the rootfs. This keeps the terminal interactive for diagnosis, but it is **not a Debian/proot session**: changing the working directory does not virtualize absolute paths. Commands in that fallback run as the Android app UID and must not be treated as a sandboxed Linux environment.

## Linux userspace and manager operations

### Rootfs configuration and paths

The configurator prepares FHS directories, `/home/noxs`, `/root`, `/run`, `/var/run`, APT settings, hostname/hosts/DNS defaults, the MOTD, shell profile, and compatibility links. It binds the app-private run directory to `/var/run/noxs/host`, and binds host `/dev`, `/proc`, and `/sys` into proot. These binds do not grant Android root; the Android process remains subject to its app UID and platform security controls.

`/etc/apt/sources.list` is configured for HTTPS Debian Bookworm, Bookworm updates, and Debian security repositories. The `70noxs` APT config disables recommended packages and unauthenticated package installation. Initial package provisioning is described in `scripts/build-rootfs.sh`; a fresh setup does not automatically run `apt update`.

### Package manager

The package screen uses `apt-cache search` for a bounded set of name-validated results and `dpkg-query` for an installed-package snapshot. Updating runs `apt-get update`. Choosing a package opens a new terminal session and writes a `sudo apt install -y <package>` command into it, rather than hiding installation output in the manager screen.

### User manager

User records are read from the rootfs `/etc/passwd`. The app validates names before invoking `useradd`, `userdel`, or `chpasswd`. The default `noxs` account cannot be deleted from this screen. Password changes are sent to `chpasswd` through stdin rather than being put in a command-line argument.

### Process manager

The screen combines Android-side Noxs session metadata with a `ps -eo ...` snapshot from the rootfs. It asks before closing a session or killing a process. Process listings and kills are snapshots/requests, not a persistent process supervisor.

### Services and code-server

Service definitions use `/etc/noxs/services/<name>.conf` with `COMMAND`, optional `USER`, `DESC`, and `AUTO_RESTART` fields. The Kotlin parser validates definition names and displays descriptions/status. The checked-in `linux-runtime/service-manager/noxs-service` script implements start/stop/restart/status/list with pidfiles under `/var/run/noxs` and logs under `/var/log/noxs`. Its `USER` field is informational, and `AUTO_RESTART` is parsed but not supervised by the script.

The `noxs code` CLI reports code-server status and can start/stop/restart an already installed binary under `/opt/code-server`. Start uses `127.0.0.1:8080`, stores user data beneath `~/.noxs/code`, and disables telemetry and update checks. `noxs code install` currently prints official installation instructions; it does not download or install code-server itself. Code-server is not bundled in the APK.

### Environment and storage

The environment screen edits the rootfs `/etc/environment` file, but the shell startup code does not explicitly source it. Also, each rootfs health/configuration pass rewrites that file with defaults, so custom entries are not reliably applied and may be discarded when a session is created. The configuration screen writes only `nameserver` lines to `/etc/resolv.conf`. Storage figures are calculated from app-private files; clearing the APT cache removes `.deb` archives, while the diagnostics clear action clears the in-memory `NoxsLog` ring (not arbitrary Linux log files). A reset deletes the rootfs and install marker; cached bootstrap archives and the rest of the app data are not the rootfs and may remain.

## IPC, sockets, and networking

The Android service exposes a local abstract-namespace socket named `@noxs.control` with one-line `ping`, `status`, and `sessions` requests. The response is a small JSON object/line. The rootfs bind at `/var/run/noxs/host` provides a path for app-private filesystem-socket communication.

The source tree also includes `noxs-socket` (list/test/serve/remove helpers) and a path validator intended to restrict guest socket paths to `/run`, `/var/run`, or `/tmp`; `serve` requires `socat`. These helper scripts are present under `linux-runtime/socket-manager/`.

Network access is used for bootstrap downloads, APT, and optional user-installed code-server. The manifest rejects ordinary `http://` bootstrap URLs. The app disables cleartext traffic. The code-server CLI defaults to loopback rather than a LAN-facing bind address; users can still install and run other network services themselves inside the userspace.

## Resource reporting and quotas

The resource configuration model and defaults are:

| Setting | Default | Current enforcement/reporting |
| --- | ---: | --- |
| Sessions | 8 | Enforced by the app session manager; accepted range 1–32. |
| Processes per session | 256 | Written to the rootfs config and applied as `ulimit -u` in the profile/service launch path where available; accepted range 16–4096. |
| Open files | 512 | Written to the config and applied as `ulimit -n` by the login profile; accepted range 64–8192. |
| Memory soft target | 2,048 MB | Stored in config; the current code does not enforce a memory cap. |
| Storage warning | 4,096 MB | Informational threshold for rootfs usage; it does not prevent writes. |

The terminal status strip is approximate: CPU is sampled from the Android app process, memory comes from host `/proc/meminfo`, and rootfs storage is calculated from app-private files. These values are not per-container accounting or kernel-enforced Noxs quotas.

## Capability, privacy, and security information

The Security/Diagnostics screens report probes for ABI, native PTY availability, socket-directory availability, storage, battery-optimization exemption, SELinux, and the presence of common `su` paths. Namespace/root checks are informational; Noxs does not use them to escape the app sandbox. A detected `su` binary is not invoked by the app.

The application requests internet/network-state, foreground-service/data-sync, notification, wake-lock, and vibration permissions. Activities and the foreground service are non-exported except for the launcher activity. Backups are disabled and Noxs data is excluded from the supplied backup/transfer rules.

### Important current privilege behavior

The configurator replaces `/usr/bin/su` with a Noxs compatibility wrapper and writes `/usr/local/bin/sudo`. The latter directly runs commands in proot's simulated-root environment; it does **not** prompt for or verify the password collected in setup. Consequently, the current code does not implement Debian's password-authenticated `sudo`, despite that wording in some older project text. This remains sandbox-scoped and is not Android root, but it is a material difference from a password-protected sudo model.

## Known implementation caveats

The following points describe gaps in the current source, not planned features:

1. **Root one-shot command construction needs validation.** `ProotLauncher` appends root one-shot arguments after `/bin/bash --login` instead of building an explicit command invocation. `OneShotExecutor` defaults to this root path, and setup/user/manager operations call it. The existing launcher tests cover the user one-shot path but do not exercise a real root one-shot against proot. Root-side setup and manager operations should not be assumed to work until this path is tested/fixed.
2. **Runtime manager helper scripts are not copied by the current configurator.** `linux-runtime` contains `noxs-service`, `noxs-socket`, and `noxs-ps`, but `RootfsConfigurator` currently installs the `noxs` CLI, `noxs-hello`, the link helper, and `su`/`sudo` wrappers—not those three manager scripts. A fresh rootfs may therefore lack the helpers expected by some CLI/service actions unless they are provisioned separately.
3. **Several Settings controls are only partially wired.** The extra-keys, volume-key, and bell switches save preferences, but the terminal screen currently shows/enables these behaviors directly rather than reading those preferences. The font-size and scrollback strings have no corresponding controls in the current Settings screen. A custom rootfs URL is saved in preferences, but `SetupActivity` does not pass it to `NoxsInstaller.overrideRootfsUrl`.
4. **Environment edits are not durable.** The app does not explicitly load `/etc/environment` into the shell, and each rootfs health/configuration pass rewrites it with defaults; edits may not affect sessions and may be discarded when a session is created.
5. **Some limits are advisory.** The memory target is not enforced; storage is a warning only. The process and open-file limits rely on shell `ulimit` support and do not constitute Android/kernel resource isolation.
6. **Blank-digest trust-on-first-use is incomplete.** With a blank manifest digest, the installer computes and writes a `.sha256` sidecar, but the current verifier does not compare a later cached archive against that previous sidecar. The checked-in manifests have digests; the caveat matters for unpinned/custom bootstrap inputs.
7. **Version constants disagree.** Android Gradle metadata currently says `0.1.6`, while the shared runtime constant used in several screens/protocol responses says `1.0.0`.

## Source map and validation

| Area | Main source |
| --- | --- |
| Android screens | `app/src/main/java/com/noxs/linux/ui/` |
| Installer, paths, proot, sessions, service, manager controls | `app/src/main/java/com/noxs/linux/runtime/` |
| Native PTY | `app/src/main/cpp/noxs-pty.c` |
| Shared security/parser utilities | `noxs-shared/src/main/kotlin/com/noxs/linux/shared/` |
| Terminal parser and buffer | `terminal-emulator/src/main/kotlin/com/noxs/linux/terminal/emulator/` |
| Android terminal view and renderer | `terminal-view/src/main/kotlin/com/noxs/linux/terminal/view/` |
| Linux runtime reference scripts | `linux-runtime/` |

Useful checks from the repository root:

```bash
./gradlew assembleDebug
./gradlew noxs-shared:test terminal-emulator:test app:testDebugUnitTest
scripts/test.sh --manifests
scripts/test.sh --sync-check
scripts/test.sh --scripts
```

See also [Architecture](ARCHITECTURE.md), [Bootstrap](BOOTSTRAP.md), [Security](SECURITY.md), [Testing](TESTING.md), and [FAQ](FAQ.md).
