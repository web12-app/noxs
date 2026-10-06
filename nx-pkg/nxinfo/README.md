# nxinfo

Prints Noxs environment information — an official noxs-pkg package (Node.js template).

## Install (from a Noxs environment)

    nx install nxinfo

or pinned:

    nx install nxinfo@1.0.0

Any Git repository can be used as the source, too:

    nx install -git @noxs-pkg/nxinfo

## Usage

nxinfo

## Development

    nx pkg init nxinfo   # or clone this repository
    nx pkg build
    nx pkg release

Releases are fully automated: bump `VERSION`, push, and GitHub Actions
builds every supported architecture (aarch64 / x86_64 / armv7 where the
language produces native code), creates the `nxinfo-v<version>` tag,
publishes the `.nx.pkg` release assets with SHA-256 digests and updates
`registry.json` — the registry file is never edited by hand.

## Attribution

Part of the Noxs package ecosystem by Crossberry / web12-app.
