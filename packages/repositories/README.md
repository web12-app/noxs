# repositories/

Reference APT source lists. `bookworm.sources` is the final Debian 12
configuration installed by RootfsConfigurator as the sole
`/etc/apt/sources.list.d/noxs.sources` file. During first setup, the same
signed repositories are temporarily served over HTTP only to install and
verify `ca-certificates`; initialization then switches to HTTPS and requires a
successful authenticated `apt-get update`. The other files are reference-only.

| File               | Release            |
|--------------------|--------------------|
| `bookworm.sources` | Debian 12 (default)|
| `trixie.sources`   | Debian 13          |
| `ubuntu.sources`   | Ubuntu 24.04 LTS   |
