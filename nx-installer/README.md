# nx-installer — the Noxs setup installer

The nx-installer installs the Noxs `nx` runtime (CLI, package modules,
`@noxs/nx-api` SDK and language templates) into an existing Linux
environment from a tagged GitHub release. It exists so environments
created outside the Noxs app (or repaired environments) can get the exact
same runtime the app installs.

## Layout

| file                 | role                                                        |
|----------------------|-------------------------------------------------------------|
| `setup.sh`           | bootstrap: resolve tag → download tar.gz → verify SHA-256 → run payload |
| `installer.sh`       | verified payload: staged, atomic installs into /usr/local prefixes |
| `build-installer.sh` | packs the payload from the canonical Kotlin templates        |
| `.github/workflows/installer.yml` (repo-level) | attaches release assets on every `v*` tag |

## Setup flow

    ./setup.sh                          # latest release
    NX_INSTALLER_TAG=v0.6.0 ./setup.sh  # pinned release

    Noxs release tag
      -> download noxs-installer-<version>.tar.gz
      -> download noxs-installer-<version>.tar.gz.sha256
      -> sha256sum -c (fatal on mismatch — nothing installs unverified)
      -> extract into a private staging directory
      -> run installer.sh (targets /usr/local/bin, /usr/local/lib, /usr/local/share)
      -> staged copy + atomic move for every file; no partial installs
      -> verify: nx version && module presence checks

Remote content is **never** piped into a shell. Tags must match
`vMAJOR.MINOR.PATCH` — anything else is refused.

## What gets installed

    /usr/local/bin/nx                      the nx CLI
    /usr/local/lib/noxs-pkg/*.sh           pkg-lib, pkg-init, pkg-dev, pkg-install, web-lib
    /usr/local/lib/noxs/nx-api/            @noxs/nx-api SDK (nx-api.js, package.json, README.md)
    /usr/local/share/noxs-pkg/templates/   language templates + .nx-version marker

Prefixes can be redirected for tests:

    NX_PREFIX_BIN=... NX_PREFIX_LIB=... NX_PREFIX_SHARE=... sh installer.sh

## Release automation

Every `v*` tag builds the payload from the canonical Kotlin templates,
verifies the archive round-trips, and attaches
`noxs-installer-<version>.tar.gz` + `.sha256` to the GitHub Release. The
payload is generated (`scripts/extract_payload.py`) — the repository never
carries a second hand-edited copy of the nx runtime.

## Attribution

Crossberry / web12-app — the Noxs project.
