#!/usr/bin/env python3
"""gen_nx_pkg.py — materialize the official noxs-pkg package repos under
nx-pkg/ (each directory is meant to become its own public Git repository
under github.com/noxs-pkg/<name>).

The .github/workflows/pkg.yml comes from the canonical Kotlin template
(NoxsNxWorkflow.WORKFLOW_YML) so generated packages always match the
`nx pkg init` output byte-for-byte.
"""
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))
import extract_nx  # noqa: E402

LICENSE = """MIT License

Copyright (c) 2026 noxs-pkg contributors

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

TREE_CPP = r"""
// tree — the noxs-pkg directory tree utility (Noxs package example, C++).
//
// Prints an indented, colorless directory tree for a given path so output
// stays stable over every terminal and locale.
#include <algorithm>
#include <cstring>
#include <dirent.h>
#include <iostream>
#include <string>
#include <sys/stat.h>
#include <vector>

namespace {

struct Options {
    bool all = false;
    int max_depth = 3;
};

void list_directory(const std::string &path, const std::string &prefix,
                    int depth, const Options &options) {
    if (depth > options.max_depth) {
        return;
    }
    DIR *dir = opendir(path.c_str());
    if (dir == nullptr) {
        return;
    }
    std::vector<std::string> names;
    while (const dirent *entry = readdir(dir)) {
        const std::string name = entry->d_name;
        if (name == "." || name == "..") {
            continue;
        }
        if (!options.all && !name.empty() && name[0] == '.') {
            continue;
        }
        names.push_back(name);
    }
    closedir(dir);
    std::sort(names.begin(), names.end());

    for (size_t index = 0; index < names.size(); ++index) {
        const bool last = index + 1 == names.size();
        const std::string full = path + "/" + names[index];
        struct stat info {};
        const bool is_dir = stat(full.c_str(), &info) == 0 && S_ISDIR(info.st_mode);
        std::cout << prefix << (last ? "`-- " : "|-- ") << names[index]
                  << (is_dir ? "/" : "") << "\n";
        if (is_dir) {
            list_directory(full, prefix + (last ? "    " : "|   "), depth + 1, options);
        }
    }
}

}  // namespace

int main(int argc, char **argv) {
    Options options;
    std::string target = ".";
    for (int index = 1; index < argc; ++index) {
        const std::string argument = argv[index];
        if (argument == "-a" || argument == "--all") {
            options.all = true;
        } else if (argument == "-L" && index + 1 < argc) {
            options.max_depth = std::atoi(argv[++index]);
        } else if (argument == "-h" || argument == "--help") {
            std::cout << "tree [-a] [-L depth] [path] — noxs-pkg tree utility\n";
            return 0;
        } else {
            target = argument;
        }
    }
    std::cout << target << "\n";
    list_directory(target, "", 1, options);
    return 0;
}
""".lstrip()

NXINFO_JS = r"""
#!/usr/bin/env node
/*
 * nxinfo — the noxs-pkg Noxs environment info utility (Node.js example).
 * Prints basic, non-identifying environment information; pairs with the
 * @noxs/nx-api system.info module when loaded inside a package context.
 */
"use strict";

const os = require("os");

function main() {
    const rows = [
        ["Noxs package", "nxinfo"],
        ["Version", require("./package.json").version],
        ["Node", process.version],
        ["Platform", process.platform + "/" + process.arch],
        ["Hostname", os.hostname()],
        ["CPUs", String(os.cpus().length)],
        ["Free memory", Math.round(os.freemem() / 1024) + " KiB"],
    ];
    const width = Math.max(...rows.map(([key]) => key.length));
    for (const [key, value] of rows) {
        console.log(key.padEnd(width, " ") + " : " + value);
    }
    if (typeof globalThis.nx !== "undefined" && globalThis.nx.system) {
        // Inside a Noxs package UI the SDK is available (permission-gated).
        console.log("Noxs API : available");
    }
}

main();
""".lstrip()

NXFETCH_PY = r'''
#!/usr/bin/env python3
"""nxfetch — the noxs-pkg system fetch utility (Python example).

Prints a compact environment summary. Uses only the standard library so
the universal release runs on every architecture Noxs supports.
"""
from __future__ import annotations

import os
import platform
import socket
import sys


def rows() -> list[tuple[str, str]]:
    return [
        ("Noxs package", "nxfetch"),
        ("Version", "1.0.0"),
        ("Python", sys.version.split()[0]),
        ("Platform", f"{platform.system()}/{platform.machine()}"),
        ("Hostname", socket.gethostname()),
        ("User", os.environ.get("USER", os.environ.get("LOGNAME", "unknown"))),
        ("Shell", os.environ.get("SHELL", "unknown")),
    ]


def main() -> int:
    lines = rows()
    width = max(len(key) for key, _ in lines)
    for key, value in lines:
        print(f"{key.ljust(width)} : {value}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
'''.lstrip()


def registry(name: str, description: str) -> str:
    return f"""{{
  "name": "{name}",
  "description": "{description}",
  "latest": "1.0.0",
  "versions": [
    {{
      "version": "1.0.0",
      "tag": "{name}-v1.0.0",
      "files": []
    }}
  ]
}}
"""


def readme(name: str, language: str, what: str, usage: str) -> str:
    return f"""# {name}

{what} — an official noxs-pkg package ({language} template).

## Install (from a Noxs environment)

    nx install {name}

or pinned:

    nx install {name}@1.0.0

Any Git repository can be used as the source, too:

    nx install -git @noxs-pkg/{name}

## Usage

{usage}

## Development

    nx pkg init {name}   # or clone this repository
    nx pkg build
    nx pkg release

Releases are fully automated: bump `VERSION`, push, and GitHub Actions
builds every supported architecture (aarch64 / x86_64 / armv7 where the
language produces native code), creates the `{name}-v<version>` tag,
publishes the `.nx.pkg` release assets with SHA-256 digests and updates
`registry.json` — the registry file is never edited by hand.

## Attribution

Part of the Noxs package ecosystem by Crossberry / web12-app.
"""


PACKAGES = {
    "tree": {
        "description": "Noxs directory tree utility",
        "language": "C++",
        "what": "Prints an indented directory tree",
        "usage": "tree [-a] [-L depth] [path]",
        "files": {
            "CMakeLists.txt": """cmake_minimum_required(VERSION 3.13)
project(tree CXX)
set(CMAKE_CXX_STANDARD 17)
add_executable(tree src/main.cpp)
install(TARGETS tree RUNTIME DESTINATION bin)
""",
            "src/main.cpp": TREE_CPP,
        },
        "universal": False,
    },
    "nxinfo": {
        "description": "Noxs environment info utility",
        "language": "Node.js",
        "what": "Prints Noxs environment information",
        "usage": "nxinfo",
        "files": {
            "package.json": """{
  "name": "nxinfo",
  "version": "1.0.0",
  "description": "Noxs environment info utility",
  "bin": { "nxinfo": "src/main.js" },
  "license": "MIT"
}
""",
            "src/main.js": NXINFO_JS,
        },
        "universal": True,
    },
    "nxfetch": {
        "description": "Noxs system fetch utility",
        "language": "Python",
        "what": "Prints a compact system summary",
        "usage": "nxfetch",
        "files": {
            "pyproject.toml": """[build-system]
requires = ["setuptools>=61"]
build-backend = "setuptools.build_meta"

[project]
name = "nxfetch"
version = "1.0.0"
description = "Noxs system fetch utility"
requires-python = ">=3.9"
license = { text = "MIT" }
""",
            "src/main.py": NXFETCH_PY,
        },
        "universal": True,
    },
}


def main() -> int:
    consts = extract_nx.nx_constants(ROOT)
    workflow = consts["WORKFLOW_YML"]
    out_root = ROOT / "nx-pkg"
    out_root.mkdir(exist_ok=True)

    (out_root / "README.md").write_text(
        """# noxs-pkg — official Noxs packages

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
""",
        encoding="utf-8",
    )

    for name, spec in PACKAGES.items():
        target = out_root / name
        (target / ".github/workflows").mkdir(parents=True, exist_ok=True)
        (target / "src").mkdir(parents=True, exist_ok=True)
        (target / ".github/workflows/pkg.yml").write_text(workflow, encoding="utf-8")
        (target / "registry.json").write_text(registry(name, spec["description"]), encoding="utf-8")
        (target / "VERSION").write_text("1.0.0\n", encoding="utf-8")
        (target / "LICENSE").write_text(LICENSE, encoding="utf-8")
        (target / "README.md").write_text(
            readme(name, spec["language"], spec["what"], spec["usage"]), encoding="utf-8"
        )
        for rel, content in spec["files"].items():
            path = target / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
        print(f"[nx-pkg] generated {target}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
