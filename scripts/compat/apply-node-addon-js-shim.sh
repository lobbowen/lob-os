#!/usr/bin/env bash
# Apply the LobOS pure-JS fallback for node-addon-require-builtin in an
# installed @deepseek-ai/dsh tree.
#
# Why: node-addon-require-builtin has no android-arm64 native build. Its only
# purpose is requireBuiltin(id) -> require internal Node modules; under
# `--expose-internals` a few lines of JS do exactly that. This shim avoids
# compiling anything and works on every Linux/Android node, not just dsh.
# (Same technique as the upstream-recommended JS fallback for this addon.)
#
# Idempotent: re-running restores-from-original then re-applies.
set -euo pipefail

D="${1:-/data/user/0/lobos.os/files}"
# run-as 域的 sh heredoc 要写临时文件到 TMPDIR，指到可写目录
export TMPDIR="${TMPDIR:-/data/local/tmp}"
[ -w "$TMPDIR" ] || export TMPDIR="$D"

IDX="$D/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/node-addon-require-builtin/lib/index.js"
[ -f "$IDX" ] || { echo "::error::找不到 $IDX —— dsh 装在哪先确认"; exit 1; }

# 恢复上游原文（若我们有备份），保证幂等
if [ -f "$IDX.lobos-orig" ]; then cp -f "$IDX.lobos-orig" "$IDX"; else cp -f "$IDX" "$IDX.lobos-orig"; fi

cat > "$IDX" <<'EOF'
'use strict';
/* LobOS pure-JS fallback for node-addon-require-builtin.
   Requires the process to run with --expose-internals (LobOS node wrapper
   adds it). No native binding needed, no per-platform build. */
function requireBuiltin(id) { return require(id); }
function isAllowedInternalId() { return true; }
function getNativeBindingInfo() {
  return { mode: 'js-shim', product: 'require-builtin', backend: 'napi', abi: 'napi-v9' };
}
module.exports = { requireBuiltin, isAllowedInternalId, getNativeBindingInfo,
  default: { requireBuiltin, isAllowedInternalId, getNativeBindingInfo } };
EOF
chmod 644 "$IDX"
echo "[ok] $IDX 已换为纯 JS 替身（原件备份 $IDX.lobos-orig）"
echo "[note] 需要 node 带 --expose-internals 启动；LobOS 终端 node wrapper 已加。"
