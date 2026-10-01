#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
F="$ROOT/scripts/userland-verify.json"
key="${1:?usage: read-userland-entry.sh <tool>}"
python3 - "$F" "$key" <<'PY'
import json, sys
path, tool = sys.argv[1], sys.argv[2]
try:
    j = json.load(open(path))
    crit = j.get('criteria') or {}
    entry = (crit.get(tool) or {}).get('entry')
except Exception as e:
    print('[FAIL] %s 读 %r: %s' % (path, tool, e), file=sys.stderr)
    sys.exit(1)
if not entry or '/' not in entry or entry.startswith('/') or '..' in entry.split('/'):
    print('[FAIL] %s: 件 %r 的 entry 未声明或不合规（应为件内相对路径，如 bin/<名>）' % (path, tool), file=sys.stderr)
    sys.exit(1)
print(entry)
PY
