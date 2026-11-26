#!/usr/bin/env bash
# Native ABI 契约校验：契约表变量必须在 Kotlin 写入端与 C 读取端都存在。
# 契约见 docs/contracts/NATIVE-ABI-CONFIG.md。
set -uo pipefail
cd "$(dirname "$0")/../.." || exit 2

CONTRACT="docs/contracts/NATIVE-ABI-CONFIG.md"
KT_DIR="container/app/src/main/java/lobos"
C_DIR="container/native"

if [ ! -f "$CONTRACT" ]; then echo "MISSING $CONTRACT"; exit 2; fi

# 从契约表第一列提取 LOBOS_* 键
keys=$(grep -oE '\| `LOBOS_[A-Z_]+`' "$CONTRACT" | tr -d '|` ' | sort -u)
fail=0
echo "== Native ABI 契约校验 =="
for k in $keys; do
  kt=$(grep -rl "\"$k\"" "$KT_DIR" --include='*.kt' 2>/dev/null | wc -l)
  c=$(grep -rl "getenv(\"$k\")" "$C_DIR" 2>/dev/null | wc -l)
  if [ "$kt" -eq 0 ] || [ "$c" -eq 0 ]; then
    echo "  FAIL $k: Kotlin=$kt C=$c"
    fail=1
  else
    echo "  OK   $k: Kotlin=$kt C=$c"
  fi
done

if [ "$fail" -gt 0 ]; then
  echo "FAIL: 契约变量两端不一致"
  exit 1
fi
echo "OK: Native ABI 契约一致"
exit 0
