#!/usr/bin/env bash
# scripts/sync-cli.sh — regenerate the canonical linux-runtime/launcher/noxs-cli
# from the Kotlin template (single source of truth), then verify the sync.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$ROOT" <<'PY'
import pathlib, re, sys
root = pathlib.Path(sys.argv[1])
kt = (root / 'app/src/main/java/com/noxs/linux/runtime/NoxsCliTemplate.kt').read_text()
m = re.search(r'val CLI = """\n?(.*?)"""', kt, re.S)
assert m, 'template markers not found'
content = m.group(1).replace("${'$'}", "$")
out = root / 'linux-runtime/launcher/noxs-cli'
out.write_text(content)
print(f'[noxs] canonical noxs-cli regenerated ({len(content.splitlines())} lines)')
PY
chmod +x "$ROOT/linux-runtime/launcher/noxs-cli"
