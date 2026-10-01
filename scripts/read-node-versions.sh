#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
F="$ROOT/container/app/src/main/assets/node-versions.json"
key="${1:?usage: read-node-versions.sh <key>}"
python3 - "$F" "$key" <<'PY'
import json, sys
path, key = sys.argv[1], sys.argv[2]
try:
    print(json.load(open(path))[key])
except Exception as e:
    print('[FAIL] %s 读 %r: %s' % (path, key, e), file=sys.stderr)
    sys.exit(1)
PY
