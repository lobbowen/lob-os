#!/usr/bin/env bash
# 分层依赖校验（APK 内独立系统）。
# 硬检查：系统机制层不得依赖上层业务包（必须通过）。
# 进度报告：android.* 依赖应收敛到 host/ 宿适配层（当前是 TODO，不阻断）。
# 判据见 docs/spec/LAYER-BOUNDARY.md。
set -uo pipefail
cd "$(dirname "$0")/../.." || exit 2

BASE="container/app/src/main/java/lobos"
KDIR="$BASE/kernel"
violations=0

check() {
  local dir="$1" pattern="$2" label="$3"
  local hits
  hits=$(grep -rn "$pattern" "$dir" --include='*.kt' 2>/dev/null | grep -vE ':[0-9]+: *\*|:[0-9]+: */\*')
  if [ -n "$hits" ]; then
    echo "VIOLATION [$label]:"
    echo "$hits" | sed 's/^/  /' | head -20
    violations=$((violations + $(echo "$hits" | wc -l)))
  fi
}

echo "== 硬检查：系统机制层不依赖上层 =="
check "$KDIR" '^import lobos\.services'  'kernel imports services'
check "$KDIR" '^import lobos\.host'      'kernel imports host adapter'
check "$KDIR" '^import lobos\.api'       'kernel imports api'
check "$KDIR" '^import lobos\.app'       'kernel imports app'
check "$KDIR" 'lobos\.services\.'        'kernel FQN references services'

if [ "$violations" -gt 0 ]; then
  echo "FAIL: 系统机制层存在 $violations 处上层依赖"
  exit 1
fi
echo "OK: 系统机制层无上层业务依赖"

echo "== 进度报告：Android 依赖收敛（判据见 LAYER-BOUNDARY.md） =="
outside=$(grep -rl '^import android' "$BASE" --include='*.kt' 2>/dev/null | grep -v '/host/' | wc -l)
hostside=$(grep -rl '^import android' "$BASE/host" --include='*.kt' 2>/dev/null | wc -l)
echo "  host 宿适配层含 android import: $hostside 文件（正确）"
echo "  host 之外仍含 android import: $outside 文件（待收敛 TODO）"
echo "  目标：host 之外为 0（系统层通过 SPI 拿 Android 能力）"
exit 0
