# packages/

APT integration for the Noxs Debian userspace.

| Directory      | Contents                                                        |
|----------------|-----------------------------------------------------------------|
| `apt/`         | apt configuration snippets installed into the rootfs            |
| `metadata/`    | JSON schema for `bootstrap.manifest` (URL + SHA-256 pins)       |
| `repositories/`| Reference `sources.list` files per Debian/Ubuntu release        |

Rules:

- APT state lives entirely inside the rootfs (`/var/lib/dpkg`, `/var/cache/apt`).
- Only HTTPS repositories; `AllowUnauthenticated` is explicitly `false`.
- No credentials or tokens are hardcoded anywhere; no custom mirror requires auth.
- Release builds must pin rootfs/proot SHA-256 via `scripts/build-rootfs.sh`.
- Initial packages provisioned by `scripts/build-rootfs.sh` (`--include` list):
  bash, coreutils, apt, sudo, procps, util-linux, grep, sed, awk, findutils,
  tar, gzip, xz-utils, curl, wget, git, openssh-client, nano, vim-tiny, less,
  ca-certificates, passwd (useradd/chpasswd), socat (unix socket helper).
