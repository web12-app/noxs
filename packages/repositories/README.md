# repositories/

Reference APT source list. `bookworm.sources` is the final Debian 12
configuration installed by RootfsConfigurator as the sole
`/etc/apt/sources.list.d/noxs.sources` file. During first setup, the same
signed repositories are temporarily served over HTTP only to install and
verify `ca-certificates`; initialization then switches to HTTPS and requires a
successful authenticated `apt-get update`.

Noxs ships exactly ONE environment (Debian 12). The Debian 13 / Ubuntu
reference source lists were removed together with the multi-environment
feature.

| File               | Release            |
|--------------------|--------------------|
| `bookworm.sources` | Debian 12 (only)   |
