#!/usr/bin/env bash
# 「借用 /system/bin/* 即越界」门禁 —— LAYERS.md §1.5 / §2 的机械判据。
#
# 理由：LAYERS.md 把「L1 里出现 /system/bin/* 的调用 → 越界」写成不可逆的依赖方向，
#   但 Step 1 的判据（grep /system/bin 全仓零命中）只靠记性。
#   与既有门禁同风格：可 CI 运行、退出码非零、有 ::error 注解。
#
# 为什么单独立一道（此前 check-layer-boundary.sh 管的是 D5/D6/D7 的类型名与目录）：
#   check-layer-boundary.sh 判「kernel/ 目录形状与类型名」，
#   判不到「某个字符串是不是被当成可执行路径用」——那是完全不同的一件事。
#
# 判据：
#   扫 container/app/src/main/java/**.kt 与 container/native/**.{c,h,cpp}
#   任何一行出现 /system/bin/ 即为越界，除非该行落在 ALLOW 表里。
#
# ★ ALLOW 表按「文件 + 行内容片段」双键锁定，不按行号 ——
#   行号会随任何一次编辑漂移，双键锁定意味着：
#   ① 真越界写进 ALLOW 表时，审 diff 的人看得见「新增了一条豁免」
#   ② 豁免的那行被删掉/改写后，ALLOW 自动失效并判红
set -uo pipefail
cd "$(cd "$(dirname "$0")/../.." && pwd)"

SRC_DIRS="container/app/src/main/java container/native"

# 每行格式：<相对路径>|<必须仍然匹配的片段>
# 片段要足够具体，不能是整行 —— 整行会随文案微调而失配而误判红。
ALLOW='
container/app/src/main/java/lobos/kernel/ipc/LocalExec.kt|不能静默落到 /system/bin/sh
container/app/src/main/java/lobos/services/supply/ShellEntry.kt|不能静默落到 /system/bin/sh
'

echo "== /system/bin 越界 =="

HITS=0
FILES=0

while IFS= read -r f; do
  rel="${f#./}"
  FILES=$((FILES + 1))
  # 判据只看行内出现；本仓禁止注释（check-no-comments.sh），
  # 所以不存在「这段在注释里所以无罪」的情形。
  while IFS= read -r ln; do
    [ -z "$ln" ] && continue
    hit="${ln#*:}"
    allowed=""
    while IFS= read -r rule; do
      [ -z "$rule" ] && continue
      path="${rule%%|*}"
      frag="${rule#*|}"
      [ "$path" = "$rel" ] || continue
      case "$hit" in
        *"$frag"*) allowed=1 ;;
      esac
    done <<< "$ALLOW"
    if [ -n "$allowed" ]; then
      echo "  [allow] $rel:${ln%%:*}  $hit"
    else
      echo "::error file=${rel},title=借用宿主 /system/bin 即越界::L1 不得把宿主系统程序当我们的件"
      echo "          $hit" | cut -c1-120
      HITS=$((HITS + 1))
    fi
  done < <(grep -n "/system/bin/" "$f" 2>/dev/null || true)
done < <(for d in $SRC_DIRS; do
           [ -d "$d" ] || { echo "::error title=门禁::找不到 $d"; exit 1; }
           find "$d" -type f \( -name '*.kt' -o -name '*.c' -o -name '*.h' -o -name '*.cpp' \) 2>/dev/null
         done | sort)

echo "[system-bin] 检查了 $FILES 个源文件；豁免 $(printf '%s' "$ALLOW" | grep -c '^container/') 条"
if [ "$HITS" -gt 0 ]; then
  echo ""
  echo "::error title=借用宿主 /system/bin::$HITS 处越界 —— 见舱外 docs/architecture/LAYERS.md §1.5 与 §2"
  echo "        要么改走我们自己的件（ShellEntry.require 是唯一解析点），要么显式豁免并写清理由。"
  exit 1
fi
echo "[ok] 无 /system/bin 越界"