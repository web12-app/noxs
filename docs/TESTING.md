# Testing & CI

## JVM unit tests (no emulator needed)

| Module               | Coverage                                                              |
|----------------------|-----------------------------------------------------------------------|
| `noxs-shared`        | SHA-256 vectors · TarGuard traversal/absolute/symlink/device/bomb rejection · manifest parse + MiniJson · passwd/group/shadow models + atomic writes · shell quoting/injection · socket path validation · service config |
| `terminal-emulator`  | VT parser: text, CR/LF, CUP, SGR (16/256/truecolor/bold), ED/EL default/zero modes, soft-wrap, OSC titles/allowlisted links/prompt markers, alt screen 1049, scroll regions, scrollback, wide chars, incremental UTF-8, DSR/DA replies, bell, ICH/DCH, RI, bracketed paste, resize, tabs, DEC graphics · KeyHandler maps |
| `:app`               | ProotLauncher argv (user/root sessions, one-shot quoting, env, quotas), single-source APT setup, narrowly scoped dpkg owner-write repair (outside files unchanged), setup-progress redaction |

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
3. **release** — on `vMAJOR.MINOR.PATCH` tags (for example `v0.1.8`): build with the tag's versionName/versionCode and attach `noxs-{version}.apk` to the GitHub release and Actions artifacts (secrets:
   `NOXS_KEYSTORE_B64`, `NOXS_KEYSTORE_PASSWORD`, `NOXS_KEY_ALIAS`,
   `NOXS_KEY_PASSWORD`).

## Manual test checklist (device)

- [ ] Fresh install → wizard completes → prompt is `noxs@android:~$`, `pwd` = /home/noxs
- [ ] APT bootstrap leaves one `/etc/apt/sources.list.d/noxs.sources` with the three HTTPS Bookworm repositories; `apt-get update`, `apt-get install --reinstall ca-certificates`, `apt-cache policy ca-certificates`, `dpkg --configure -a`, and `dpkg --audit` succeed with certificate verification enabled (including creation of `/var/lib/dpkg/status-old`)
- [ ] `echo hello` and `printf 'first\nsecond\n'` render correctly; prompt redraw clears with CSI `K`
- [ ] While typing a command, let a background loop print newline output; the input line, cursor, and prompt remain usable
- [ ] `sudo apt update` prompts for the password set in the wizard (where the rootfs sudo implementation supports it)
- [ ] First shell prints the CrossberryWeb welcome once; only the approved site/email open through normal Android intents; long-press text selection still works
- [ ] Ctrl+C stops `yes`, Ctrl+Z + `fg` resumes, Ctrl+D exits shells
- [ ] `vim-tiny`, `less`, `htop` (after install) render + resize correctly
- [ ] `ls -l /var/run` shows noxs pidfiles; `noxs-socket list` works
- [ ] `noxs code install` then `noxs code start` → browser at 127.0.0.1:8080
- [ ] Airplane mode → terminal + sockets still work (offline-first)
- [ ] Kill app from recents → reopen → setup skipped, home dir intact
- [ ] Storage → reset → setup wizard reappears
