# Users & sudo

## Default user

- `noxs` (uid/gid 1000+, member of `sudo`), home `/home/noxs`, shell `/bin/bash`.
- Created during setup from `/etc/passwd`, `/etc/group`, `/etc/shadow` inside
  the rootfs; the password is collected in the setup wizard and applied via
  `chpasswd` (stdin; never stored by the app, never logged).

## Sudo semantics (important)

- `sudo` inside a terminal session is **Debian's real sudo**. It prompts for
  the noxs password and grants privileges **within the Noxs userspace only**.
- Under proot `-0` (fake uid 0 via ptrace), setuid-style operations succeed
  inside the sandbox. This is proot's documented userland behavior; no Android
  privileges are involved.
- Noxs **never** claims sudo/root reaches the Android device. The Security
  screen and `/etc/motd` repeat this notice.
- App-internal maintenance commands (e.g. `useradd` for the Users screen) run
  as fake-root one-shots through the same proot boundary — they are app
  features, not a terminal privilege escalation, and are fully visible in
  Diagnostics logs.

## Commands provided

`whoami`, `id`, `groups`, `passwd`, `su` come from Debian packages. The Users
screen offers the same operations with validation from `PasswdDb.isValidUsername`
(Debian naming rules) before invoking `useradd`/`userdel`/`chpasswd`.
