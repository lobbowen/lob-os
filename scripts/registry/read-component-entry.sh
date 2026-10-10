#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
F="$HERE/../component-sources.json"
key="${1:?usage: read-component-entry.sh <tool>}"

if command -v node >/dev/null 2>&1; then
  node -e '
    const fs = require("node:fs");
    const [path, tool] = process.argv.slice(1);
    let entry = null;
    try {
      const crit = (JSON.parse(fs.readFileSync(path, "utf8")).sources) || {};
      entry = (crit[tool] || {}).entry || null;
    } catch (e) {
      console.error("[FAIL] " + path + " 读 " + JSON.stringify(tool) + ": " + e.message);
      process.exit(1);
    }
    if (typeof entry !== "string" || !entry) {
      console.error("[FAIL] " + path + ": 件 " + JSON.stringify(tool) + " 的 entry 未声明（应为件内相对路径，如 bin/<名>）");
      process.exit(1);
    }
    if (!entry.includes("/") || entry.startsWith("/") || entry.split("/").includes("..")) {
      console.error("[FAIL] " + path + ": 件 " + JSON.stringify(tool) + " 的 entry 不合规（应为件内相对路径，如 bin/<名>）: " + entry);
      process.exit(1);
    }
    process.stdout.write(entry);
  ' "$F" "$key"
  exit 0
fi

if command -v python3 >/dev/null 2>&1; then
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
  exit 0
fi

echo "[FAIL] 读 $F 需要 node 或 python3，两个都没有" >&2
exit 1
