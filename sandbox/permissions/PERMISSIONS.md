# Userspace permission model

Noxs implements a realistic Debian permission model inside the sandbox:

1. **Identity** — uid/gid from `/etc/passwd` + `/etc/group`. Sessions log in as
   `noxs`; a "root session" (drawer → New root session) runs `bash --login`
   under proot `-0` as the (faked) uid 0, still inside the sandbox.
2. **File permissions** — the rootfs files carry normal POSIX modes on the
   underlying ext4/f2fs storage. `chmod`/`chown` inside the sandbox operate on
   the app-private copies via proot's emulation.
3. **Privilege escalation** — Debian `sudo` with a password, restricted to the
   `sudo` group (the noxs user is a member). Nothing is passwordless by
   default; nothing escalates beyond the app sandbox.
4. **Android boundary** — the app process itself only has its own UID and
   SELinux category. Noxs requests no privileged Android permissions and never
   attempts setuid on the Android side.
