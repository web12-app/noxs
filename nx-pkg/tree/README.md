# tree

Prints an indented directory tree — an official noxs-pkg package (C++ template).

## Install (from a Noxs environment)

    nx install tree

or pinned:

    nx install tree@1.0.0

Any Git repository can be used as the source, too:

    nx install -git @noxs-pkg/tree

## Usage

tree [-a] [-L depth] [path]

## Development

    nx pkg init tree   # or clone this repository
    nx pkg build
    nx pkg release

Releases are fully automated: bump `VERSION`, push, and GitHub Actions
builds every supported architecture (aarch64 / x86_64 / armv7 where the
language produces native code), creates the `tree-v<version>` tag,
publishes the `.nx.pkg` release assets with SHA-256 digests and updates
`registry.json` — the registry file is never edited by hand.

## Attribution

Part of the Noxs package ecosystem by Crossberry / web12-app.
