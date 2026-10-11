#!/usr/bin/env bash
# 代码里不许有注释。
#
# 理由：论证写在注释里，下一个人会读到，然后代码一改注释就成了谎言。
# 设计论证该在 commit message 与文档里，不该跟着代码变。
#
# 范围：container/app/src/main/java/**（Kotlin 源码）。
# scripts/ 与 .github/workflows/ 不在范围内 —— 那里有 shebang 与用法说明，
# 有实际价值。
#
# 判据：行首（可含空白）出现 // 、/* 、* 或 */ 的行。
# 字符串里的 "//"（如 URL）不在行首，不算。
set -uo pipefail
cd "$(cd "$(dirname "$0")/../.." && pwd)"

ROOT="container/app/src/main/java"
[ -d "$ROOT" ] || { echo "::error title=门禁::找不到 $ROOT"; exit 1; }

BAD=0
COUNT=0

while IFS= read -r f; do
  rel="${f#$ROOT/}"
  # 找出注释行：行首（可含空白）以 // 或 /* 或 * 或 */ 开头
  # 行内注释不算 —— 那不是「注释块」，判据聚焦在独占行的注释。
  n="$(grep -cE '^[[:space:]]*(//|/\*|\*|\*/)' "$f" 2>/dev/null | head -1 || true)"
  if [ "$n" != "0" ]; then
    echo "[error] $rel 有 $n 行注释"
    grep -nE '^[[:space:]]*(//|/\*|\*|\*/)' "$f" | head -3 | sed 's/^/          /'
    BAD=$((BAD + n))
  fi
  COUNT=$((COUNT + 1))
done < <(find "$ROOT" -name '*.kt' | sort)

if [ "$BAD" != "0" ]; then
  echo ""
  echo "::error title=代码里有注释::$BAD 行（$COUNT 个文件）—— 论证放 commit message 或文档，不放代码"
  exit 1
fi

echo "[ok] $COUNT 个 Kotlin 文件，零注释"