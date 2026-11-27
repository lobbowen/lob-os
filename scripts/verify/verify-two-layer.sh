#!/usr/bin/env bash
# 二层架构校验：内核层不含 APK/UI/生命周期类 Android 依赖。
# 允许 android.system.Os (syscall 封装) 与 android.util.Base64 (纯工具)。
set -uo pipefail
cd "$(dirname "$0")/../.." || exit 2
KDIR=container/app/src/main/java/lobos/kernel
echo "== 二层架构校验 =="
fail=0
# 禁止的 Android 依赖模式
PAT='android\.(content|app|activity|os\.Bundle|view|widget|graphics|ui|media|telephony|location|provider)'
hits=$(grep -rnE "$PAT" "$KDIR" --include='*.kt' 2>/dev/null)
if [ -n "$hits" ]; then
  echo "FAIL: kernel 含 APK/UI/生命周期 Android 依赖:"
  echo "$hits" | sed 's/^/  /'
  fail=1
else
  echo "  OK: kernel 无 APK/UI/生命周期 Android 依赖"
fi
# connect(system)是唯一访问 FR_* 的地方
fr_hits=$(grep -rlE 'FR_ROOT|FR_LIBPATH|FR_SHIM|FR_CWD' container/app/src/main/java/lobos/ --include='*.kt' 2>/dev/null | grep -v '/system/' || true)
if [ -n "$fr_hits" ]; then
  echo "WARN: system/ 之外仍有 FR_* 引用:"
  echo "$fr_hits" | sed 's/^/  /'
fi
if [ "$fail" -gt 0 ]; then exit 1; fi
echo "OK: 二层架构校验通过"
