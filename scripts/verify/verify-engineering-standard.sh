#!/usr/bin/env bash
# LobOS 工程规范总校验入口。
# 后续所有改动提交前跑这一个脚本；具体规则见 docs/ENGINEERING-STANDARD.md。
set -uo pipefail
cd "$(dirname "$0")/../.." || exit 2

echo "== LobOS 工程规范校验 =="
fail=0
run() {
  local name="$1" script="$2"
  if [ ! -f "$script" ]; then echo "  SKIP $name ($script 不存在)"; return; fi
  echo "  --- $name ---"
  if bash "$script" >/tmp/lobos-verify-out 2>&1; then
    tail -2 /tmp/lobos-verify-out | sed 's/^/    /'
    echo "    PASS"
  else
    tail -8 /tmp/lobos-verify-out | sed 's/^/    /'
    echo "    FAIL"
    fail=1
  fi
}

run "分层依赖" scripts/verify/verify-layer-deps.sh
run "目录结构" scripts/verify/verify-directory-layout.sh
run "Native ABI契约" scripts/verify/verify-native-config.sh
run "official 锚" scripts/verify/verify-ota-anchor.sh

echo "== 结果 =="
if [ "$fail" -gt 0 ]; then
  echo "FAIL：规范校验未通过，见上"
  exit 1
fi
echo "OK：规范校验通过"
exit 0
