#!/usr/bin/env bash
# 二层架构校验：
#   1) lobos/ 顶层只有 android 与 system 两个目录
#   2) system(内核层) 不含 APK/UI/生命周期类 Android 依赖
#   3) FR_* 执行环境只由 android/systemdisk(连接点) 构造
# 允许 android.system.Os (syscall 封装) 与 android.util.Base64 (纯工具)。
set -uo pipefail
cd "$(dirname "$0")/../.." || exit 2
BASE=container/app/src/main/java/lobos
SYS=$BASE/system
CONNECT=$BASE/android/systemdisk
echo "== 二层架构校验 =="
fail=0

# 1. 顶层只有 android/system
tops=$(find "$BASE" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort | tr '\n' ' ')
if [ "$tops" != "android system " ]; then
  echo "FAIL: lobos/ 顶层应只有 android 与 system，实际: $tops"
  fail=1
else
  echo "  OK: lobos/ 顶层 = android + system"
fi

# 2. system(内核层) 不含 APK/UI/生命周期 Android 依赖
PAT='android\.(content|app|activity|os\.Bundle|view|widget|graphics|ui|media|telephony|location|provider)'
hits=$(grep -rnE "$PAT" "$SYS" --include='*.kt' 2>/dev/null)
if [ -n "$hits" ]; then
  echo "FAIL: system(内核层) 含 APK/UI/生命周期 Android 依赖:"
  echo "$hits" | sed 's/^/    /'
  fail=1
else
  echo "  OK: system(内核层) 无 APK/UI/生命周期 Android 依赖"
fi

# 3. FR_* 只在连接点构造
fr_hits=$(grep -rlE '"FR_(ROOT|LIBPATH|SHIM|CWD|LAUNCH)"' "$BASE" --include='*.kt' 2>/dev/null | grep -v "$CONNECT" || true)
if [ -n "$fr_hits" ]; then
  echo "FAIL: android/systemdisk 之外仍有 FR_* 环境构造:"
  echo "$fr_hits" | sed 's/^/    /'
  fail=1
else
  echo "  OK: FR_* 环境仅由 android/systemdisk(连接点) 构造"
fi

if [ "$fail" -gt 0 ]; then echo "FAIL: 二层架构校验未通过"; exit 1; fi
echo "OK: 二层架构校验通过"
