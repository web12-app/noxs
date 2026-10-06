# __PKG_NAME__

A Noxs package built with the NX Package System.

## Development

    nx pkg build        # build with the language's own build system
    nx pkg release      # pre-flight validation before publishing

## Releasing

1. Bump `VERSION` (semantic versioning, MAJOR.MINOR.PATCH)
2. Commit and push — GitHub Actions builds all supported architectures,
   tags `__PKG_NAME__-v<version>`, creates the GitHub Release and updates
   `registry.json` automatically
3. Users install it with:

    nx install __PKG_NAME__

or pinned:

    nx install __PKG_NAME__@1.0.0

## Files

- `.github/workflows/pkg.yml` — build + release automation (do not edit)
- `registry.json` — release metadata (updated automatically by CI)
- `VERSION` — the version the next release publishes
