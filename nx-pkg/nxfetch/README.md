# nxfetch

Prints a compact system summary — an official noxs-pkg package (Python template).

## Install (from a Noxs environment)

    nx install nxfetch

or pinned:

    nx install nxfetch@1.0.0

Any Git repository can be used as the source, too:

    nx install -git @noxs-pkg/nxfetch

## Usage

nxfetch

## Development

    nx pkg init nxfetch   # or clone this repository
    nx pkg build
    nx pkg release

Releases are fully automated: bump `VERSION`, push, and GitHub Actions
builds every supported architecture (aarch64 / x86_64 / armv7 where the
language produces native code), creates the `nxfetch-v<version>` tag,
publishes the `.nx.pkg` release assets with SHA-256 digests and updates
`registry.json` — the registry file is never edited by hand.

## Attribution

Part of the Noxs package ecosystem by Crossberry / web12-app.
