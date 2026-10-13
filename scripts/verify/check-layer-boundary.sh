#!/usr/bin/env bash
# 分层边界门禁 —— 让 DECISIONS.md 的 D5/D6/D7 不只靠记性。
#
# 理由：规范被自己违反过五次（piece / program / manifest / permission / ProgramPackageVerifier）。
# 那几次不是不知道规范，是没人拦。这一道把它们变成机器能验的。
#
# 判据来自 docs/architecture/DECISIONS.md：
#   D5  设备授权与能力属 device/，不属于 kernel/
#   D6  电源策略属 services/，不属于 kernel/
#   D7  「不许用业务词」管的是【类型名】，
#       不管磁盘上的契约文件名（如 program-manifest.json）
#
set -uo pipefail
cd "$(cd "$(dirname "$0")/../.." && pwd)"

ROOT="container/app/src/main/java/lobos"
KERNEL="$ROOT/kernel"
[ -d "$KERNEL" ] || { echo "::error title=层门禁::找不到 $KERNEL"; exit 1; }

BANNED_DIRS="device power"
BANNED_WORDS="program catalog ota piece permission manifest quickapp"

VIOL=0
COUNT=0

echo "== 分层边界 =="

for d in $BANNED_DIRS; do
  if [ -d "$KERNEL/$d" ]; then
    echo "::error title=分层越界::kernel/$d/ 存在 —— D5/D6 已把它移出"
    VIOL=$((VIOL + 1))
  fi
done

TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT

for f in $(find "$KERNEL" -name "*.kt" | sort); do
  COUNT=$((COUNT + 1))
  grep -E '^[[:space:]]*(public |internal |private |abstract |open |data |sealed |enum )*(class|interface|object|enum|typealias) ' "$f" 2>/dev/null > "$TMP" || true
  while IFS= read -r line; do
    [ -z "$line" ] && continue
    low=$(printf "%s" "$line" | tr "A-Z" "a-z")
    for w in $BANNED_WORDS; do
      case "$low" in
        *" $w"*|*" $w:"*)
          echo "::error file=${f#"$KERNEL"/},title=内核类型名带业务词::$w —— 见 DECISIONS.md 的 D7"
          echo "  $line" | cut -c1-100
          VIOL=$((VIOL + 1))
          ;;
      esac
    done
  done < "$TMP"
done

echo "[layer] 检查了 $COUNT 个内核文件；$BANNED_DIRS 两类目录；业务词 $BANNED_WORDS 六个"
if [ "$VIOL" -gt 0 ]; then
  echo "::error title=分层边界::$VIOL 处越界 —— 见 docs/architecture/DECISIONS.md 的 D5/D6/D7"
  exit 1
fi
echo "[ok] 分层边界未越界"
