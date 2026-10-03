# Testing & CI

## JVM unit tests (no emulator needed)

| Module               | Coverage                                                              |
|----------------------|-----------------------------------------------------------------------|
| `noxs-shared`        | SHA-256 vectors · TarGuard traversal/absolute/symlink/device/bomb rejection · manifest parse + MiniJson · passwd/group/shadow models + atomic writes · shell quoting/injection · socket path validation · service config |
| `terminal-emulator`  | VT parser: text, CR/LF, CUP, SGR (16/256/truecolor/bold), ED/EL, alt screen 1049, scroll regions, scrollback, wide chars, incremental UTF-8, DSR/DA replies, OSC titles, bell, ICH/DCH, RI, bracketed paste, resize, tabs, DEC graphics · KeyHandler maps |
| `:app`               | ProotLauncher argv (user/root sessions, one-shot quoting, env, quotas) |

Run locally:

```bash
./gradlew noxs-shared:test terminal-emulator:test app:testDebugUnitTest
# or
scripts/test.sh --unit
```

## Script/tooling validation

`scripts/test.sh` also: validates bootstrap manifests (schema + https + sha
shape), diff-checks canonical `linux-runtime` copies against embedded app
templates (noxs CLI), runs `bash -n` on every shell file and `shellcheck` when
available.

## CI (GitHub Actions)

`.github/workflows/android-ci.yml` runs on every push/PR:

1. **android-build** — JDK 17 + Android SDK/NDK; `assembleDebug`,
   `assembleRelease`, all unit tests; uploads APKs and reports.
2. **rootfs-validation** — manifest JSON checks, sync checks, shellcheck,
   `scripts/test.sh` subset (runs the same JVM test suite for extraction
   security).
3. **release** — on `v*` tags: signed `assembleRelease` (secrets:
   `NOXS_KEYSTORE_B64`, `NOXS_KEYSTORE_PASSWORD`, `NOXS_KEY_ALIAS`,
   `NOXS_KEY_PASSWORD`) and a GitHub release with the APK attached.

## Manual test checklist (device)

- [ ] Fresh install → wizard completes → `whoami` = noxs, `pwd` = /home/noxs
- [ ] `sudo apt update` prompts for the password set in the wizard
- [ ] Ctrl+C stops `yes`, Ctrl+Z + `fg` resumes, Ctrl+D exits shells
- [ ] `vim-tiny`, `less`, `htop` (after install) render + resize correctly
- [ ] `ls -l /var/run` shows noxs pidfiles; `noxs-socket list` works
- [ ] `noxs code install` then `noxs code start` → browser at 127.0.0.1:8080
- [ ] Airplane mode → terminal + sockets still work (offline-first)
- [ ] Kill app from recents → reopen → setup skipped, home dir intact
- [ ] Storage → reset → setup wizard reappears
