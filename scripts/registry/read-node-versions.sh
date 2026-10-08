#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
F="$HERE/node-versions.json"
key="${1:?usage: read-node-versions.sh <key>}"

if command -v node >/dev/null 2>&1; then
  node -e '
    const fs = require("node:fs");
    const [path, key] = process.argv.slice(1);
    try {
      const v = JSON.parse(fs.readFileSync(path, "utf8"))[key];
      if (v === undefined) { console.error("[FAIL] " + path + " 没有键 " + JSON.stringify(key)); process.exit(1); }
      process.stdout.write(typeof v === "string" ? v : JSON.stringify(v));
    } catch (e) {
      console.error("[FAIL] " + path + " 读 " + JSON.stringify(key) + ": " + e.message);
      process.exit(1);
    }
  ' "$F" "$key"
  exit 0
fi

if command -v python3 >/dev/null 2>&1; then
  python3 - "$F" "$key" <<'PY'
import json, sys
path, key = sys.argv[1], sys.argv[2]
try:
    print(json.load(open(path))[key])
except Exception as e:
    print('[FAIL] %s 读 %r: %s' % (path, key, e), file=sys.stderr)
    sys.exit(1)
PY
  exit 0
fi

echo "[FAIL] 读 $F 需要 node 或 python3，两个都没有" >&2
exit 1
