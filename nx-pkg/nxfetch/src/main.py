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
