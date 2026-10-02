# linux-runtime

Runtime components of the Noxs Debian userspace. The Android app embeds
equivalent content (Kotlin templates + assets) at build time; CI validates
that both copies stay in sync.

| Directory          | Contents                                                        | Installed to (inside rootfs)     |
|--------------------|-----------------------------------------------------------------|----------------------------------|
| `bootstrap/`       | Per-arch `bootstrap.manifest` (URL + SHA-256 pins)              | n/a — consumed by the installer  |
| `rootfs/overlay/`  | `/etc/profile.d/noxs.sh`, apt config, FHS link helper            | `/etc/…`, `/usr/local/lib/…`     |
| `launcher/`        | `noxs-cli` (code-server + info), `noxs-launch.sh` reference argv | `/usr/local/bin/noxs`            |
| `service-manager/` | `noxs-service` (pidfiles under /var/run/noxs)                    | `/usr/local/bin/noxs-service`    |
| `socket-manager/`  | `noxs-socket` (list/test/serve unix sockets under /run, /var/run)| `/usr/local/bin/noxs-socket`     |
| `process-manager/` | `noxs-ps` (ps wrapper parsed by the app's process screen)        | `/usr/local/bin/noxs-ps`         |

The services installed into `/usr/local/bin` are executed as the `noxs` user
inside the proot sandbox. They manage the Noxs environment only — they have no
Android privileges of any kind.
