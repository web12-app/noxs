# Unix domain sockets in Noxs

Noxs provides real AF_UNIX support inside the Debian userspace under `/run`
and `/var/run` (e.g. `unix:///var/run/noxs/…`), without touching Android's
real filesystem.

## Layout

| Path (inside rootfs)     | Purpose                                                |
|--------------------------|--------------------------------------------------------|
| `/run/`                  | general runtime state + sockets (FHS)                  |
| `/var/run/` → `/run/`    | FHS compat link (recreated in-sandbox at first login)  |
| `/var/run/noxs/`         | Noxs-managed sockets & pidfiles                        |
| `/var/run/noxs/host/`    | bind-mount of app-private `filesDir/noxs/run` — Android↔Debian IPC |
| `/var/run/noxs/<svc>.pid`| pidfiles written by `noxs-service`                     |

## Creating sockets (inside a terminal session)

```console
$ sudo apt install socat          # one-time helper
$ noxs-socket serve /var/run/noxs/demo.sock -- /bin/bash -c 'while read l; do echo "echo: $l"; done'
# in another session:
$ echo hello | socat - UNIX-CONNECT:/var/run/noxs/demo.sock
echo: hello
$ noxs-socket list
$ noxs-socket test /var/run/noxs/demo.sock
```

`noxs-socket` validates that paths stay under `/run`, `/var/run` or `/tmp`.

## Android ↔ Debian IPC

The app hosts a control socket:

- Android side: `LocalServerSocket` on the abstract namespace `@noxs.control`
  serving a tiny JSON protocol (`ping` / `status` / `sessions`).
- Debian side: any tool can talk to app-private sockets under
  `/var/run/noxs/host/` (bind-mounted), and Android apps can connect to
  Debian-created sockets through their physical path inside the app-private
  rootfs — a genuine two-way AF_UNIX channel with zero privilege escalation.

## Validation

`SocketPathValidator` (unit tested) rejects paths outside the sandbox runtime
dirs, traversals, control characters, and directory targets.
