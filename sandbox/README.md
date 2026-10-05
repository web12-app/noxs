# sandbox/

The Noxs isolation architecture. The Kotlin implementation lives in
`noxs-shared` and `app/src/main/java/com/crossberry/noxs/runtime` (mapping below);
this directory documents the model and holds provisioning references.

```
Android host (SELinux-enforcing kernel)
└── Noxs app sandbox            ← Android UID + app-private storage; no bypass
    └── Noxs runtime            ← proot (ptrace path translation, no namespaces)
        └── Debian 12 userspace ← full FHS: /etc /home/noxs /run /var/run …
```

| Directory      | Documentation                            | Kotlin implementation                |
|----------------|------------------------------------------|--------------------------------------|
| `filesystem/`  | `LAYOUT.md` — the FHS tree Noxs provides | `NoxsPaths`, `RootfsExtractor`       |
| `users/`       | `USERS.md` — user database + provisioning| `PasswdDb`, `UserManagerControl`     |
| `permissions/` | `PERMISSIONS.md` — userspace model       | `RootfsConfigurator` (sudo group)    |
| `sessions/`    | `SESSIONS.md` — terminal session model   | `NoxsSessionManager`, `NoxsService`  |

Hard rules (enforced in code and tests):

1. Never modify Android's real system filesystem — all paths are app-private.
2. Never bypass SELinux, the app sandbox, or verified boot.
3. `sudo` is Debian's real sudo, scoped to the Noxs userspace; the app never
   claims device root and never silently escalates.
4. If kernel features (namespaces/chroot) are unavailable — the normal case on
   stock Android — Noxs falls back to proot's userland emulation automatically.
