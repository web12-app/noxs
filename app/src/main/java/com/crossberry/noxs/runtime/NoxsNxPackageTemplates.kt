/*
 * Noxs — original implementation.
 * Language templates for `nx pkg init` (NX Package System spec §3-§9, §25).
 * Written into the rootfs at /usr/local/share/noxs-pkg/templates/. The
 * installation step assembles complete per-language trees:
 *
 *   templates/_shared/{pkg.yml, registry.json, VERSION, README.md, LICENSE}
 *   templates/<lang>/template.conf
 *   templates/<lang>/files/            (language-specific sources)
 *   templates/<lang>/files/.github/workflows/pkg.yml   (copied from _shared)
 *   templates/<lang>/files/{registry.json, VERSION, README.md, LICENSE}
 *
 * Adding a language later = add a directory + one FILES entry; no package
 * manager logic needs to change (spec §25).
 *
 * Same §→$ encoding convention as the other nx templates.
 */
package com.crossberry.noxs.runtime

object NoxsNxPackageTemplates {

    // ---------------------------------------------------------- shared files

    private val REGISTRY_JSON = """{
  "name": "__PKG_NAME__",
  "description": "Noxs __PKG_NAME__ utility",
  "latest": "1.0.0",
  "versions": [
    {
      "version": "1.0.0",
      "tag": "__PKG_NAME__-v1.0.0",
      "files": []
    }
  ]
}
"""

    private val VERSION_FILE = """1.0.0
"""

    private val README_MD = """# __PKG_NAME__

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
"""

    private val LICENSE_FILE = """MIT License

Copyright (c) 2026 __PKG_NAME__ contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
"""

    // ------------------------------------------------------------- Node.js

    private val NODEJS_CONF = """# Noxs package template metadata (read by nx)
TEMPLATE_LANGUAGE=nodejs
TEMPLATE_DISPLAY="Node.js"
TEMPLATE_ARCHES="universal"
TEMPLATE_BUILD_SYSTEM="npm/node"
"""

    private val NODEJS_PACKAGE_JSON = """{
  "name": "__PKG_NAME__",
  "version": "1.0.0",
  "private": true,
  "bin": {
    "__PKG_NAME__": "src/main.js"
  },
  "scripts": {
    "start": "node src/main.js"
  }
}
"""

    private val NODEJS_MAIN = """#!/usr/bin/env node
'use strict';

function main(argv) {
    for (const arg of argv) {
        console.log(arg);
    }
    return 0;
}

if (require.main === module) {
    process.exitCode = main(process.argv.slice(2));
}

module.exports = { main };
"""

    // --------------------------------------------------------------- Rust

    private val RUST_CONF = """# Noxs package template metadata (read by nx)
TEMPLATE_LANGUAGE=rust
TEMPLATE_DISPLAY="Rust"
TEMPLATE_ARCHES="x86_64 aarch64 armv7"
TEMPLATE_BUILD_SYSTEM="cargo"
"""

    private val RUST_CARGO_TOML = """[package]
name = "__PKG_NAME__"
version = "1.0.0"
edition = "2021"

[dependencies]
"""

    private val RUST_MAIN = """fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    for arg in args {
        println!("{arg}");
    }
}
"""

    // ---------------------------------------------------------------- C++

    private val CPP_CONF = """# Noxs package template metadata (read by nx)
TEMPLATE_LANGUAGE=cpp
TEMPLATE_DISPLAY="C++"
TEMPLATE_ARCHES="x86_64 aarch64 armv7"
TEMPLATE_BUILD_SYSTEM="cmake"
"""

    private val CPP_CMAKELISTS = """cmake_minimum_required(VERSION 3.10)
project(__PKG_NAME__ CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_executable(__PKG_NAME__ src/main.cpp)
"""

    private val CPP_MAIN = """#include <iostream>

int main(int argc, char** argv) {
    for (int i = 1; i < argc; ++i) {
        std::cout << argv[i] << '\n';
    }
    return 0;
}
"""

    // -------------------------------------------------------------- Python

    private val PYTHON_CONF = """# Noxs package template metadata (read by nx)
TEMPLATE_LANGUAGE=python
TEMPLATE_DISPLAY="Python"
TEMPLATE_ARCHES="universal"
TEMPLATE_BUILD_SYSTEM="pip/pyproject"
"""

    private val PYTHON_PYPROJECT = """[build-system]
requires = ["setuptools>=61"]
build-backend = "setuptools.build_meta"

[project]
name = "__PKG_NAME__"
version = "1.0.0"
description = "Noxs __PKG_NAME__ package"
requires-python = ">=3.9"

[project.scripts]
__PKG_NAME__ = "main:main"

[tool.setuptools]
py-modules = ["main"]

[tool.setuptools.package-dir]
"" = "src"
"""

    private val PYTHON_MAIN = """# __PKG_NAME__ — a Noxs package.


def main() -> int:
    import sys

    for arg in sys.argv[1:]:
        print(arg)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
"""

    // ----------------------------------------------------------------- Go

    private val GO_CONF = """# Noxs package template metadata (read by nx)
TEMPLATE_LANGUAGE=go
TEMPLATE_DISPLAY="Go"
TEMPLATE_ARCHES="x86_64 aarch64 armv7"
TEMPLATE_BUILD_SYSTEM="go build"
"""

    private val GO_MOD = """module __PKG_NAME__

go 1.21
"""

    private val GO_MAIN = """package main

import (
        "fmt"
        "os"
)

func main() {
        for _, arg := range os.Args[1:] {
                fmt.Println(arg)
        }
}
"""

    /**
     * Every template file, keyed relative to
     * /usr/local/share/noxs-pkg/templates/. `_shared/pkg.yml` is copied into
     * each language's files/.github/workflows/pkg.yml at install time, and
     * the other _shared files are copied into each language's files/ root.
     */
    val FILES: Map<String, String> = mapOf(
        "templates/_shared/pkg.yml" to NoxsNxWorkflow.WORKFLOW_YML,
        "templates/_shared/registry.json" to REGISTRY_JSON,
        "templates/_shared/VERSION" to VERSION_FILE,
        "templates/_shared/README.md" to README_MD,
        "templates/_shared/LICENSE" to LICENSE_FILE,

        "templates/nodejs/template.conf" to NODEJS_CONF,
        "templates/nodejs/files/package.json" to NODEJS_PACKAGE_JSON,
        "templates/nodejs/files/src/main.js" to NODEJS_MAIN,

        "templates/rust/template.conf" to RUST_CONF,
        "templates/rust/files/Cargo.toml" to RUST_CARGO_TOML,
        "templates/rust/files/src/main.rs" to RUST_MAIN,

        "templates/cpp/template.conf" to CPP_CONF,
        "templates/cpp/files/CMakeLists.txt" to CPP_CMAKELISTS,
        "templates/cpp/files/src/main.cpp" to CPP_MAIN,

        "templates/python/template.conf" to PYTHON_CONF,
        "templates/python/files/pyproject.toml" to PYTHON_PYPROJECT,
        "templates/python/files/src/main.py" to PYTHON_MAIN,

        "templates/go/template.conf" to GO_CONF,
        "templates/go/files/go.mod" to GO_MOD,
        "templates/go/files/main.go" to GO_MAIN,
    )

    /** Languages with a full scaffold (kept in sync with the map above). */
    val LANGUAGES = listOf("nodejs", "rust", "cpp", "python", "go")

    /** Common files copied from _shared into every language's files/ root. */
    val COMMON_FILES = listOf("registry.json", "VERSION", "README.md", "LICENSE")
}
