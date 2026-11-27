#!/usr/bin/env bash
# 目录结构规范校验：包深度、单目录文件数、包声明一致性
set -uo pipefail
cd "$(dirname "$0")/../.." || exit 2

ROOT="container/app/src/main/java"
UIROOT="container/ui/src/main/java"
fail=0
warn=0

echo "== 目录结构校验 =="

# 1. 包深度 ≤ 5 (lobos/层/域/子域/文件.kt，如 android/host/device/)
deep=$( { find "$ROOT" -type f -name '*.kt' 2>/dev/null | sed "s|^$ROOT/||"; find "$UIROOT" -type f -name '*.kt' 2>/dev/null | sed "s|^$UIROOT/||"; } | awk -F/ '{print NF}' | sort -n | tail -1)
echo "  最大包路径深度: $deep 段（lobos/android/host/device/X.kt = 5段）"
if [ "$deep" -gt 5 ]; then
  echo "  FAIL: 包路径深度超过 4 段"
  fail=1
fi

# 2. 单目录文件数 > 20 告警
echo "  单目录文件数 > 15:"
over=$(find "$ROOT" -type d 2>/dev/null | while read d; do
  n=$(find "$d" -maxdepth 1 -type f -name '*.kt' | wc -l)
  [ "$n" -gt 20 ] && echo "    $n ${d#$ROOT/}"
done)
if [ -n "$over" ]; then echo "$over"; warn=$((warn+1)); else echo "    无"; fi

# 3. 包声明与目录一致
echo "  包声明一致性:"
mismatch=$(python3 - <<'PY'
import os,re
for root in ['container/app/src/main/java','container/ui/src/main/java']:
    for dp,_,fns in os.walk(root):
        for fn in fns:
            if not fn.endswith('.kt'): continue
            p=os.path.join(dp,fn)
            expected=os.path.relpath(dp,root).replace(os.sep,'.')
            t=open(p,encoding='utf-8').read()
            m=re.search(r'^package\s+([\w.]+)',t,re.M)
            if m and m.group(1)!=expected:
                print(f"    {p}: package {m.group(1)} != {expected}")
PY
)
if [ -n "$mismatch" ]; then echo "$mismatch"; fail=1; else echo "    全部一致"; fi

# 4. android.* import 分布（仅统计，阶段1.5目标）
echo "  android.* import 分布:"
tmp=$(mktemp)
find "$ROOT" -type f -name '*.kt' | while read f; do
  if grep -q '^import android' "$f"; then echo "    ${f#$ROOT/}"; fi
done > "$tmp"
head -15 "$tmp"
total=$(wc -l < "$tmp")
echo "    ... 共 $total 处（阶段1.5目标：host层之外清零）"
rm -f "$tmp"

if [ "$fail" -gt 0 ]; then
  echo "FAIL: 目录结构校验未通过"
  exit 1
fi
echo "OK: 目录结构校验通过（$warn 项告警）"
exit 0
