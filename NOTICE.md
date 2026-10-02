# NOTICE

Product: Noxs — Debian-optimized Linux userspace environment for Android
Application ID: com.noxs.linux
License: Apache-2.0 (see LICENSE)

Noxs is an original implementation. It contains no source code, branding, or
assets from Termux or any other terminal application; other projects were used
only as conceptual/architectural references under principles unencumbered by
copyright.

Runtime components remain under their upstream licenses:

- Debian 12 (bookworm) root filesystem — downloaded at first launch, produced
  by the Debian project and the debuerreotype tooling; packages remain under
  their respective Debian licenses (see /usr/share/doc/<package>/copyright
  inside the rootfs). Not redistributed by this project.
- proot — GPL-2.0+ (proot-me contributors); the Android build bundled in this
  APK as libnoxs-proot.so comes from the Termux project's package repository
  (proot 5.1.107.96, https://github.com/termux/proot, patches included there),
  with libtalloc (LGPL-2.1+, http://talloc.samba.org) and libandroid-shmem
  (https://github.com/termux/libandroid-shmem) bundled alongside. Corresponding
  source is available at the upstream repositories above.
- code-server — optional, user-installed, MIT (Coder); never bundled here.

Android, Debian, Ubuntu, VS Code and their marks belong to their respective
owners. Noxs is not affiliated with, endorsed by, or derived from Termux.
