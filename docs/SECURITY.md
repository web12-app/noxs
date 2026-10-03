# Noxs security model

## Threat model & guarantees

Noxs operates **entirely inside the Android application sandbox**. It:

- does **not** modify the Android system filesystem, system services, or
  framework configuration;
- does **not** bypass, disable, or attempt to evade SELinux, verified boot,
  seccomp, or any other Android security control;
- does **not** request superuser (root) privileges and never silently
  escalates;
- does **not** expose Android's real `/var/run` — all "system" paths are
  virtual paths resolved inside the app-private rootfs by proot.

## `sudo` semantics

`sudo` inside the Noxs terminal is Debian's real `sudo`, password-protected,
scoped to the Noxs userspace. It grants control over the Noxs environment
only. The app states this in the welcome screen, the terminal MOTD, the
Security screen, and `noxs-hello`. Noxs never describes this as device root.

## Supply chain

| Stage      | Control                                                                 |
|------------|-------------------------------------------------------------------------|
| Download   | HTTPS only (`BootstrapManifest` rejects http://), staging + rename      |
| Integrity  | SHA-256 pinned in release manifests (`build-rootfs.sh`); TOFU in dev    |
| Extraction | `TarGuard`: rejects `..`, absolute paths, drive letters, NUL, escaping symlinks/hardlinks, device/fifo entries; strips setuid/setgid; caps total size |
| Runtime    | argv-only execution (no shell interpolation); `ShellUtil.quote` everywhere |
| Storage    | Everything under app-private `filesDir/noxs/`; backups excluded         |
| Credentials| Passwords pass via stdin to `chpasswd`, are scrubbed from memory, never logged |

## Extraction defense (unit tested)

`noxs-shared/src/test/.../RootfsExtractorTest.kt` proves:

- `../../outside` → `SecurityException`, nothing written outside the rootfs
- `/etc/evil` absolute entries → rejected
- symlink/hardlink escaping the root → rejected
- device/fifo special entries → rejected
- setuid bits stripped from all modes
- archives larger than `MAX_ROOTFS_BYTES` → rejected (extraction bombs)

## Unix sockets

- Sockets live under `/run`, `/var/run`, `/tmp` **inside the rootfs** only;
  `SocketPathValidator` enforces the allowlist and is unit tested.
- The app↔Debian control socket binds to the app-private run directory and is
  bind-mounted at `/var/run/noxs/host` — never at a global path.
- No network services are exposed by default; `code-server` binds to
  `127.0.0.1` inside the sandbox, reachable only through the device itself.

## Android ↔ Linux boundary

Capability detection (`NoxsDeviceCapabilities`) is reported honestly in the
Security/Diagnostics screens: PTY, unix sockets, namespaces (informational —
never used to escape), root (informational only, unused), storage mode,
background restrictions, SELinux state.

## Reporting

See `SECURITY.md` in the project root for the vulnerability reporting process.
