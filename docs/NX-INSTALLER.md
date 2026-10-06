# nx-installer — setup installer

Installs the exact Noxs `nx` runtime (CLI, package modules, `@noxs/nx-api`
SDK, language templates) into a Linux environment from a tagged GitHub
release. Full details live in `nx-installer/README.md`.

## Quick start (inside a Noxs environment)

    ./setup.sh                          # latest release
    NX_INSTALLER_TAG=v0.6.0 ./setup.sh  # pinned release

## Flow

    release tag (vMAJOR.MINOR.PATCH — anything else is refused)
      → download noxs-installer-<version>.tar.gz
      → download + verify .sha256 sidecar (fatal on mismatch)
      → extract into a private staging directory
      → run installer.sh (staged copy + atomic move per file)
      → verify: nx version + module presence checks

Remote content is never piped into a shell: download, verify, then execute
the verified payload (spec §33).

## Installed files

    /usr/local/bin/nx
    /usr/local/lib/noxs-pkg/{pkg-lib,pkg-init,pkg-dev,pkg-install,web-lib}.sh
    /usr/local/lib/noxs/nx-api/{nx-api.js,package.json,README.md}
    /usr/local/share/noxs-pkg/templates/ + .nx-version

## Release automation

Every `v*` tag packs the payload from the canonical Kotlin templates
(`scripts/extract_payload.py`), verifies the archive round-trips, and
attaches `noxs-installer-<version>.tar.gz` + `.sha256` to the GitHub
Release. Test runs attach the payload as a workflow artifact instead.

Attribution: Crossberry / web12-app — the Noxs project.
