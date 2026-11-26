#!/usr/bin/env bash
# 分层依赖校验：kernel 层不得依赖 services/device/api/control/android
# 这是架构纪律的第一道闸门；当前失败项是重构 TODO 清单。
set -uo pipefail
cd "$(dirname "$0")/../.." || exit 2

KDIR="container/app/src/main/java/lobos/kernel"
violations=0

check() {
  local pattern="$1" label="$2"
  local hits
  hits=$(grep -rn "$pattern" "$KDIR" --include='*.kt' 2>/dev/null | grep -vE ':[0-9]+: *\*|:[0-9]+: */\*')
  if [ -n "$hits" ]; then
    echo "VIOLATION [$label]:"
    echo "$hits" | sed 's/^/  /'
    violations=$((violations + $(echo "$hits" | wc -l)))
  fi
}

check '^import lobos\.services'  'kernel imports services'
check '^import lobos\.device'    'kernel imports device'
check '^import lobos\.api'       'kernel imports api'
check '^import lobos\.control'   'kernel imports control'
check 'lobos\.services\.'        'kernel FQN references services'

if [ "$violations" -gt 0 ]; then
  echo ""
  echo "FAIL: kernel 层存在 $violations 处上层依赖（见 ARCHITECTURE-AUDIT.md P0）"
  exit 1
fi
echo "OK: kernel 层无上层依赖"
exit 0
