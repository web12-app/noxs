# Noxs bootstrap system

## What gets installed

| Artifact        | Source (default)                                             | Size     |
|-----------------|--------------------------------------------------------------|----------|
| Debian 12 rootfs| `debuerreotype/docker-debian-artifacts` (official Debian CI) | ~80–120 MB compressed |
| proot (static)  | `proot-me/proot` GitHub release binary for the device arch   | ~1–2 MB  |

Both are verified with SHA-256 before use. Release builds **pin** digests via
`scripts/build-rootfs.sh`; development builds may run unpinned (the installer
computes and shows the digest, then pins it trust-on-first-use).

## First-launch flow (SetupActivity)

```
Noxs logo → "Linux environment setup"
  → architecture detection (arm64-v8a priority, then x86_64, then armv7)
  → storage initialization (filesDir/noxs/{rootfs,bin,run,cache,logs,tmp})
  → bootstrap manifest load (assets/bootstrap/<arch>/bootstrap.manifest)
  → proot download        → SHA-256 verify → stage → rename
  → rootfs download       → SHA-256 verify → stage → rename
  → safe extraction       (TarGuard: traversal/absolute/symlink/device/bomb defense)
  → Debian configuration  (FHS dirs, profile.d, apt, DNS, motd, noxs CLI)
  → noxs user creation    (passwd/group/shadow + sudo group)
  → password collection   (wizard → chpasswd stdin; never stored)
  → /run + /var/run init  (runtime state dirs inside the rootfs)
  → apt init              (sources written; `apt update` on first online use)
  → first shell
```

## Interruption safety

- Downloads use a `.part` staging file; the final name only appears after
  checksum verification, so power loss can never leave a half-verified artifact.
- Extraction is restartable: an existing complete marker short-circuits setup;
  a broken rootfs is detected by the missing marker and can be reset from
  Storage → "Reset Linux environment".
- Cached archives under `filesDir/noxs/cache` are reused across retries.

## Offline-first

After the one-time bootstrap, normal operation (terminal, sockets, services,
code-server) needs no network. `apt install` naturally needs Debian mirrors.
For fully offline distribution, build with `scripts/build-rootfs.sh --bundle`
and ship the staged tarball inside the APK assets.

## ARM64 optimization

- Priority arch: `arm64-v8a` (manifest listed first, ABI filter in Gradle).
- proot static build for aarch64; `PROOT_NO_SECCOMP=1` for broad kernel support.
- Minimal default apt set avoids unnecessary package churn; APT recommends are
  disabled (`70noxs` conf) to cut install size.
