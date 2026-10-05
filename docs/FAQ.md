# FAQ

**Is Noxs Termux?**
No. Noxs is an original project (Apache-2.0) inspired by the well-known
"terminal emulator + Linux userspace on Android" architecture. No Termux code,
branding, or assets are used. The engine also differs: Noxs runs an official
Debian 12 rootfs via proot instead of bionic-compiled packages in $PREFIX.

**Does Noxs root my phone?**
No, and it never tries. `sudo` controls the Noxs environment only — the
Security screen states this explicitly.

**Why proot instead of chroot?**
chroot requires root or special kernel privileges most devices don't grant
apps. proot achieves path virtualization via ptrace entirely in userland —
graceful by design, exactly the fallback the spec requires.

**Why is /var/run "real"?**
It is a real directory *inside the app-private rootfs*. proot maps `/var/run`
requests into `filesDir/noxs/rootfs/var/run`, so sockets behave normally while
Android's actual `/var/run` is never touched.

**Where does code-server come from?**
`noxs code install` points to the official MIT-licensed code-server release.
Noxs never bundles or redistributes VS Code or code-server binaries.

**Does it work offline?**
Yes, after the one-time bootstrap. Only package installation and code-server
download need the network.

**Which devices?**
Android 7.0+ (API 24). ARM64 is the priority target; x86_64 works for
emulators; armv7 is experimental.

**How do I reset everything?**
Storage → "Reset Linux environment", or clear the app's data from Android
Settings. Home files live inside the rootfs, so resets delete them — the
reset dialog warns first.
