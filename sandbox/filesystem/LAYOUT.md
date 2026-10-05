# Noxs filesystem layout

The Noxs rootfs provides a real Linux filesystem hierarchy inside
`filesDir/noxs/rootfs` (app-private storage). `/run` and `/var/run` exist and
support Unix-domain sockets; Android's real `/var/run` is never touched.

```
/  (filesDir/noxs/rootfs)
├── bin -> usr/bin            (FHS compat link, recreated in-sandbox)
├── boot/                     (empty, FHS completeness)
├── dev/                      (bind from Android /dev via proot)
├── etc/
│   ├── apt/sources.list      (isolated Debian repos)
│   ├── environment           (global env vars, editable in UI)
│   ├── hostname              ("noxs")
│   ├── hosts, resolv.conf    (DNS editable in Settings)
│   ├── motd                  (security notice)
│   ├── noxs/
│   │   ├── resources.conf    (CPU/mem/proc/session/storage quotas)
│   │   └── services/         (noxs-service definitions, *.conf)
│   ├── passwd, group, shadow (user database)
│   ├── profile.d/noxs.sh     (env + quota application)
│   └── skel/                 (new-user dotfiles)
├── home/
│   └── noxs/                 (persistent default user home)
├── lib -> usr/lib
├── media/, mnt/, opt/, srv/
├── proc/                     (bind via proot)
├── root/                     (root session home)
├── run/                      ← runtime state; sockets supported
│   └── (systemd-less; pidfiles under /var/run/noxs/)
├── sbin -> usr/sbin
├── sys/                      (bind via proot)
├── tmp/                      (sticky, per-session TMPDIR)
├── usr/{bin,sbin,lib,local/{bin,lib}}/
│   └── local/bin/{noxs,noxs-service,noxs-socket,noxs-ps}
└── var/
    ├── cache/apt/archives/   (package cache; clearable in UI)
    ├── lib/dpkg/             (package database)
    ├── log/noxs/             (service + code-server logs)
    ├── run -> /run           (FHS compat)
    └── spool/
```

## Unix sockets

- `/run` and `/var/run` are real directories inside the rootfs (created by the
  installer; `/var/run -> /run` compat link is recreated in-sandbox).
- Processes inside Debian may create sockets anywhere under `/run`, `/var/run`
  or `/tmp` — validated by `SocketPathValidator` and `noxs-socket`.
- `/var/run/noxs/host` is bind-mounted from app-private `filesDir/noxs/run`,
  giving Android↔Debian IPC over AF_UNIX (see docs/UNIX-SOCKETS.md).
