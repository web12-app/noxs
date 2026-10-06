/*
 * Noxs — original implementation.
 * .github/workflows/pkg.yml written into every `nx pkg init` project.
 * Mirrored at linux-runtime/nx/templates/_shared/pkg.yml (CI sync-check).
 *
 * Pipeline (spec §10-§16): checkout -> detect language + validate VERSION +
 * reject duplicate tags -> multi-architecture build matrix -> package
 * <name>.nx.pkg.<version>.<arch>.tar.xz + sha256 sidecar -> GitHub Release
 * with the <name>-v<version> tag -> registry.json auto-update (preserve old
 * versions, append, update latest, deterministic JSON) -> commit [skip ci].
 *
 * The same §→$ encoding convention applies: `§` in the raw string is a
 * literal `$` after .replace('§', '$').
 */
package com.crossberry.noxs.runtime

object NoxsNxWorkflow {

    val WORKFLOW_YML = """name: noxs-pkg

on:
  push:
    branches: ['**']
  workflow_dispatch:

permissions:
  contents: write

concurrency:
  group: noxs-pkg-§{{ github.ref }}
  cancel-in-progress: false

jobs:
  detect:
    name: Detect language + validate release
    runs-on: ubuntu-latest
    outputs:
      name: §{{ steps.meta.outputs.name }}
      language: §{{ steps.meta.outputs.language }}
      version: §{{ steps.meta.outputs.version }}
      tag: §{{ steps.meta.outputs.tag }}
      matrix: §{{ steps.meta.outputs.matrix }}
    steps:
      - uses: actions/checkout@v4

      - id: meta
        env:
          REPO_NAME: §{{ github.event.repository.name }}
        run: |
          set -euo pipefail
          PKG_NAME="$(printf '%s' "§REPO_NAME" | tr '[:upper:]' '[:lower:]')"
          if ! printf '%s' "§PKG_NAME" | grep -qE '^[a-z0-9][a-z0-9._-]*§'; then
            echo "::error::Invalid package name: §PKG_NAME (use lowercase letters, digits, '.', '-', '_')"
            exit 1
          fi
          if [ ! -f VERSION ]; then
            echo "::error::VERSION file is missing"
            exit 1
          fi
          VERSION="$(tr -d '[:space:]' < VERSION)"
          if ! printf '%s' "§VERSION" | grep -qE '^[0-9]+[.][0-9]+[.][0-9]+§'; then
            echo "::error::Invalid package version: §VERSION (expected MAJOR.MINOR.PATCH)"
            exit 1
          fi
          LANG_ID=""
          for pair in package.json:nodejs Cargo.toml:rust CMakeLists.txt:cpp pyproject.toml:python go.mod:go; do
            f="§{pair%%:*}"; l="§{pair##*:}"
            if [ -f "§f" ]; then
              if [ -n "§LANG_ID" ]; then
                echo "::error::Ambiguous package language (multiple build files found)"
                exit 1
              fi
              LANG_ID="§l"
            fi
          done
          if [ -z "§LANG_ID" ]; then
            echo "::error::Unable to detect package language (need exactly one of package.json, Cargo.toml, CMakeLists.txt, pyproject.toml, go.mod)"
            exit 1
          fi
          TAG="§{PKG_NAME}-v§{VERSION}"
          if git ls-remote --exit-code origin "refs/tags/§TAG" >/dev/null 2>&1; then
            echo "::error::Release already exists: §TAG. Bump VERSION to publish a new release."
            exit 1
          fi
          case "§LANG_ID" in
            nodejs|python)
              MATRIX='[{"arch":"universal"}]' ;;
            go)
              MATRIX='[{"arch":"x86_64","goarch":"amd64","goarm":""},{"arch":"aarch64","goarch":"arm64","goarm":""},{"arch":"armv7","goarch":"arm","goarm":"7"}]' ;;
            rust)
              MATRIX='[{"arch":"x86_64","target":"x86_64-unknown-linux-gnu","linker":""},{"arch":"aarch64","target":"aarch64-unknown-linux-gnu","linker":"aarch64-linux-gnu-gcc"},{"arch":"armv7","target":"armv7-unknown-linux-gnueabihf","linker":"arm-linux-gnueabihf-gcc"}]' ;;
            cpp)
              MATRIX='[{"arch":"x86_64","toolchain":""},{"arch":"aarch64","toolchain":"aarch64"},{"arch":"armv7","toolchain":"armv7"}]' ;;
          esac
          {
            echo "name=§PKG_NAME"
            echo "language=§LANG_ID"
            echo "version=§VERSION"
            echo "tag=§TAG"
            echo "matrix=§MATRIX"
          } >> "§GITHUB_OUTPUT"

  build:
    name: Build (§{{ matrix.arch }})
    needs: detect
    runs-on: ubuntu-latest
    env:
      PKG: §{{ needs.detect.outputs.name }}
      VERSION: §{{ needs.detect.outputs.version }}
      LANG_ID: §{{ needs.detect.outputs.language }}
    strategy:
      fail-fast: true
      matrix: §{{ fromJson(needs.detect.outputs.matrix) }}
    steps:
      - uses: actions/checkout@v4

      - name: Toolchain (Node.js)
        if: env.LANG_ID == 'nodejs'
        uses: actions/setup-node@v4
        with:
          node-version: '20'

      - name: Toolchain (Python)
        if: env.LANG_ID == 'python'
        uses: actions/setup-python@v5
        with:
          python-version: '3.11'

      - name: Toolchain (Go)
        if: env.LANG_ID == 'go'
        uses: actions/setup-go@v5
        with:
          go-version: 'stable'

      - name: Toolchain (Rust)
        if: env.LANG_ID == 'rust'
        uses: dtolnay/rust-toolchain@stable
        with:
          targets: §{{ matrix.target }}

      - name: Cross toolchain (Rust)
        if: env.LANG_ID == 'rust' && matrix.linker != ''
        run: |
          sudo apt-get update -qq
          case "§{{ matrix.arch }}" in
            aarch64) sudo apt-get install -y gcc-aarch64-linux-gnu ;;
            armv7)   sudo apt-get install -y gcc-arm-linux-gnueabihf ;;
          esac

      - name: Cross toolchain (C++)
        if: env.LANG_ID == 'cpp' && matrix.toolchain != ''
        run: |
          sudo apt-get update -qq
          case "§{{ matrix.arch }}" in
            aarch64) sudo apt-get install -y g++-aarch64-linux-gnu ;;
            armv7)   sudo apt-get install -y g++-arm-linux-gnueabihf ;;
          esac

      - name: Build (Node.js)
        if: env.LANG_ID == 'nodejs'
        run: |
          set -euo pipefail
          echo "Building package..."
          if [ -f package-lock.json ]; then
            npm ci --no-audit --no-fund
          else
            npm install --no-audit --no-fund
          fi
          [ -f src/main.js ] || { echo "::error::src/main.js is missing"; exit 1; }
          node --check src/main.js

      - name: Build (Rust)
        if: env.LANG_ID == 'rust'
        env:
          CARGO_TARGET_AARCH64_UNKNOWN_LINUX_GNU_LINKER: §{{ matrix.linker }}
          CARGO_TARGET_ARMV7_UNKNOWN_LINUX_GNUEABIHF_LINKER: §{{ matrix.linker }}
        run: |
          set -euo pipefail
          echo "Compiling (§{{ matrix.arch }})..."
          cargo build --release --target "§{{ matrix.target }}"

      - name: Build (C++)
        if: env.LANG_ID == 'cpp'
        run: |
          set -euo pipefail
          echo "Compiling (§{{ matrix.arch }})..."
          ARCH="§{{ matrix.arch }}"
          TOOLCHAIN_ARG=""
          if [ -n "§{{ matrix.toolchain }}" ]; then
            if [ "§ARCH" = "aarch64" ]; then
              {
                echo 'set(CMAKE_SYSTEM_NAME Linux)'
                echo 'set(CMAKE_SYSTEM_PROCESSOR aarch64)'
                echo 'set(CMAKE_C_COMPILER aarch64-linux-gnu-gcc)'
                echo 'set(CMAKE_CXX_COMPILER aarch64-linux-gnu-g++)'
                echo 'set(CMAKE_FIND_ROOT_PATH /usr/aarch64-linux-gnu)'
                echo 'set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)'
                echo 'set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)'
                echo 'set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)'
              } > aarch64-toolchain.cmake
              TOOLCHAIN_ARG="-DCMAKE_TOOLCHAIN_FILE=§PWD/aarch64-toolchain.cmake"
            elif [ "§ARCH" = "armv7" ]; then
              {
                echo 'set(CMAKE_SYSTEM_NAME Linux)'
                echo 'set(CMAKE_SYSTEM_PROCESSOR arm)'
                echo 'set(CMAKE_C_COMPILER arm-linux-gnueabihf-gcc)'
                echo 'set(CMAKE_CXX_COMPILER arm-linux-gnueabihf-g++)'
                echo 'set(CMAKE_FIND_ROOT_PATH /usr/arm-linux-gnueabihf)'
                echo 'set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)'
                echo 'set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)'
                echo 'set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)'
              } > armv7-toolchain.cmake
              TOOLCHAIN_ARG="-DCMAKE_TOOLCHAIN_FILE=§PWD/armv7-toolchain.cmake"
            fi
          fi
          cmake -S . -B "build-§ARCH" §TOOLCHAIN_ARG -DCMAKE_BUILD_TYPE=Release
          cmake --build "build-§ARCH" --parallel

      - name: Build (Python)
        if: env.LANG_ID == 'python'
        run: |
          set -euo pipefail
          echo "Building package..."
          python3 -m pip install --upgrade pip
          python3 -m pip wheel --no-deps -w wheelhouse .

      - name: Build (Go)
        if: env.LANG_ID == 'go'
        run: |
          set -euo pipefail
          echo "Compiling (§{{ matrix.arch }})..."
          CGO_ENABLED=0 GOOS=linux GOARCH="§{{ matrix.goarch }}" GOARM="§{{ matrix.goarm }}" \
            go build -trimpath -ldflags="-s -w" -o "§PKG" .

      - name: Package
        run: |
          set -euo pipefail
          STAGING="staging/usr/local"
          mkdir -p "§STAGING/bin" "§STAGING/share/doc/§PKG"
          case "§LANG_ID" in
            nodejs)
              mkdir -p "§STAGING/lib/§PKG"
              cp -a src package.json "§STAGING/lib/§PKG/"
              if [ -d node_modules ]; then cp -a node_modules "§STAGING/lib/§PKG/"; fi
              printf '#!/bin/sh\nexec node "/usr/local/lib/%s/src/main.js" "§@"\n' "§PKG" > "§STAGING/bin/§PKG"
              chmod +x "§STAGING/bin/§PKG" ;;
            rust)
              cp "target/§{{ matrix.target }}/release/§PKG" "§STAGING/bin/§PKG" ;;
            cpp)
              BIN="build-§{{ matrix.arch }}/§PKG"
              if [ ! -f "§BIN" ]; then
                BIN="$(find "build-§{{ matrix.arch }}" -type f -name "§PKG" | head -1)"
              fi
              [ -n "§BIN" ] && [ -f "§BIN" ] || { echo "::error::Build output §PKG not found"; exit 1; }
              cp "§BIN" "§STAGING/bin/§PKG" ;;
            python)
              WHEEL="$(ls wheelhouse/*.whl | head -1)"
              echo "Packaging §WHEEL..."
              python3 -m pip install --no-deps --root-user-action=ignore \
                --target "§STAGING/lib/noxs-py/§PKG" "§WHEEL" >/dev/null
              rm -rf "§STAGING/lib/noxs-py/§PKG/bin"
              {
                echo '#!/usr/bin/env python3'
                echo 'import sys'
                echo 'sys.path.insert(0, "/usr/local/lib/noxs-py/'"§PKG"'")'
                echo 'from main import main'
                echo 'sys.exit(main())'
              } > "§STAGING/bin/§PKG"
              chmod +x "§STAGING/bin/§PKG" ;;
            go)
              cp "§PKG" "§STAGING/bin/§PKG" ;;
          esac
          for f in README.md LICENSE VERSION; do
            if [ -f "§f" ]; then cp "§f" "§STAGING/share/doc/§PKG/"; fi
          done
          ARTIFACT="§PKG.nx.pkg.§VERSION.§{{ matrix.arch }}.tar.xz"
          echo "Packaging §ARTIFACT..."
          tar -cJf "§ARTIFACT" -C staging usr
          sha256sum "§ARTIFACT" > "§ARTIFACT.sha256"

      - name: Upload build artifact
        uses: actions/upload-artifact@v4
        with:
          name: pkg-§{{ matrix.arch }}
          path: |
            *.tar.xz
            *.sha256
          if-no-files-found: error

  release:
    name: Release + registry update
    needs: [detect, build]
    runs-on: ubuntu-latest
    env:
      PKG: §{{ needs.detect.outputs.name }}
      VERSION: §{{ needs.detect.outputs.version }}
      TAG: §{{ needs.detect.outputs.tag }}
    steps:
      - uses: actions/checkout@v4

      - name: Collect artifacts
        uses: actions/download-artifact@v4
        with:
          pattern: pkg-*
          merge-multiple: true
          path: dist

      - name: Verify artifacts
        run: |
          set -euo pipefail
          echo "Verifying checksums..."
          cd dist
          sha256sum -c *.sha256
          cd ..
          COUNT="$(ls dist/*.tar.xz | wc -l)"
          if [ "§COUNT" -lt 1 ]; then
            echo "::error::No package artifacts were produced"
            exit 1
          fi

      - name: Create Git tag + GitHub Release
        env:
          GH_TOKEN: §{{ github.token }}
        run: |
          set -euo pipefail
          if git ls-remote --exit-code origin "refs/tags/§TAG" >/dev/null 2>&1; then
            echo "::error::Release already exists: §TAG. Bump VERSION to publish a new release."
            exit 1
          fi
          echo "Uploading release..."
          gh release create "§TAG" \
            --target "§GITHUB_SHA" \
            --title "§PKG §VERSION" \
            --notes "Noxs package release §VERSION" \
            dist/*

      - name: Update registry.json
        env:
          LANG_ID: §{{ needs.detect.outputs.language }}
        run: |
          python3 - <<'PY'
          import hashlib, json, os, pathlib, re, sys

          pkg = os.environ["PKG"]
          version = os.environ["VERSION"]
          tag = os.environ["TAG"]

          def is_semver(v):
              return re.match(r"^\d+\.\d+\.\d+§", str(v)) is not None

          reg_path = pathlib.Path("registry.json")
          try:
              reg = json.loads(reg_path.read_text(encoding="utf-8"))
          except Exception:
              sys.exit("Invalid registry.json: the file is not valid JSON")
          if reg.get("name") != pkg:
              sys.exit("Invalid registry.json: name mismatch (expected %s)" % pkg)

          versions = [v for v in reg.get("versions", []) if isinstance(v, dict)]
          for v in versions:
              if not is_semver(v.get("version", "")):
                  sys.exit("Invalid registry.json: bad version entry %r" % v.get("version"))
              if v.get("tag") != "%s-v%s" % (pkg, v["version"]):
                  sys.exit("Invalid registry.json: tag mismatch for %s" % v.get("version"))

          dist = pathlib.Path("dist")
          files = []
          for p in sorted(dist.glob("*.tar.xz")):
              files.append({
                  "name": p.name,
                  "sha256": hashlib.sha256(p.read_bytes()).hexdigest(),
              })
          if not files:
              sys.exit("No package artifacts found in dist/")

          versions = [v for v in versions if v.get("version") != version]
          versions.append({"version": version, "tag": tag, "files": files})
          versions.sort(key=lambda v: tuple(int(x) for x in str(v["version"]).split(".")))

          latest = versions[-1]["version"]
          out = {
              "name": reg["name"],
              "description": reg.get("description", ""),
              "latest": latest,
              "versions": versions,
          }
          reg_path.write_text(json.dumps(out, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
          print("registry.json updated: latest=%s, versions=%d" % (latest, len(versions)))
          PY

      - name: Commit registry update
        run: |
          set -euo pipefail
          echo "Updating registry..."
          git config user.name "noxs-pkg-bot"
          git config user.email "noxs-pkg-bot@users.noreply.github.com"
          git add registry.json
          if git diff --cached --quiet; then
            echo "registry.json unchanged"
            exit 0
          fi
          git commit -m "registry: §PKG §VERSION [skip ci]"
          git push
""".replace('§', '$')
}
