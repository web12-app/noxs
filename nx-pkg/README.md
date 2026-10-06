# noxs-pkg — official Noxs packages

Each directory here is the source of an independent public Git repository
under `github.com/noxs-pkg/<name>` (NX Package System spec §1: one package,
one repository — never a central monorepo). A package repository contains:

    .github/workflows/pkg.yml   build + release automation (from the Noxs templates)
    registry.json               release metadata (updated automatically by CI)
    VERSION                     the next release version (MAJOR.MINOR.PATCH)
    README.md, LICENSE          human-facing files
    src/                        package sources (+ language build files)

Users install from any Noxs environment:

    nx install tree
    nx install tree@1.0.0
    nx install -git @noxs-pkg/tree

Rules: tags are never overwritten, every release carries SHA-256 sidecars,
registry.json is bot-written only, and installation never executes
repository source code directly.

Attribution: Crossberry / web12-app — the Noxs project.
