#!/usr/bin/env bash
# 编内核的兼容性垫片 —— 四个我们自己的 C，随源码走、随 APK 走。
#
# 与「件」的区别（这是为什么它们不再走 build-piece-*.sh）：
#   · 不生成 component-meta.json
#   · 不铺位到 usr/lib/<id>/<版本>/、不建全局软链、不进件清单
#   · 只有一个版本，永不 OTA 替换 —— 它们是内核的一部分，不是可替换的外部软件
#
# 产物直接进 container/app/src/main/jniLibs/arm64-v8a/，由
# kernel/compat/Compat.kt 按名字定位。
#
# 复用 scripts/recipes/piece-env.sh 的 CC/NDK/API 环境变量，
# 但不调用它的 land_piece（那是按件铺位的重活）。
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/piece-env.sh" 2>/dev/null || {
  echo "!! 编不出来：拿不到 CC/NDK（piece-env.sh 没就位）" >&2
  exit 1
}

OUT="container/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$OUT"

# check_so：至少是这个字节数的 aarch64 ELF
so_ok() {
  local f="$1" min="$2"
  [ -f "$f" ] || { echo "!! 没产出：$f" >&2; return 1; }
  local sz; sz=$(stat -c%s "$f" 2>/dev/null || echo 0)
  [ "$sz" -ge "$min" ] || { echo "!! 太小（$sz < $min）：$f" >&2; return 1; }
  return 0
}

CFLAGS="-O2 -fPIC"

# ── posix：LD_PRELOAD 垫片（路径与链接语义兜底）──────────────────────
"$CC" -shared $CFLAGS \
  container/native/d1/link-interpose.c \
  container/native/d1/open-fallback.c \
  container/native/d1/tmp-paths.c \
  container/native/d1/exec-path.c \
  -ldl \
  -o "$WORK/liblobosposix.so" 2>"$WORK/cc-posix.log" \
  || { echo "!! posix 编不出来" >&2; tail -20 "$WORK/cc-posix.log" >&2; exit 1; }
so_ok "$WORK/liblobosposix.so" 500
cp -f "$WORK/liblobosposix.so" "$OUT/liblobosposix.so"
echo "  ✓ posix      → liblobosposix.so"

# ── ptyprobe：PTY 能力探测 ─────────────────────────────────────────
"$CC" -static -O2 \
  -o "$WORK/liblobosptyprobe.so" \
  container/native/d2/pty-probe.c 2>"$WORK/cc-ptyprobe.log" \
  || { echo "!! ptyprobe 编不出来" >&2; tail -20 "$WORK/cc-ptyprobe.log" >&2; exit 1; }
so_ok "$WORK/liblobosptyprobe.so" 1000
cp -f "$WORK/liblobosptyprobe.so" "$OUT/liblobosptyprobe.so"
echo "  ✓ ptyprobe   → liblobosptyprobe.so"

# ── ptysession：PTY 宿主（被当可执行程序驱动，不是 preload）─────────
"$CC" -static -O2 \
  -o "$WORK/librivospty.so" \
  container/native/d3/pty-session.c 2>"$WORK/cc-ptysession.log" \
  || { echo "!! ptysession 编不出来" >&2; tail -20 "$WORK/cc-ptysession.log" >&2; exit 1; }
so_ok "$WORK/librivospty.so" 1000
cp -f "$WORK/librivospty.so" "$OUT/librivospty.so"
echo "  ✓ ptysession → librivospty.so"

# ── flock：文件锁（node addon，依赖 node 头文件）────────────────────
NODE_INC="${NODE_INCLUDE_DIR:-}"
if [ -n "$NODE_INC" ] && [ -d "$NODE_INC" ]; then
  "$CC" -shared $CFLAGS -DNAPI_VERSION=9 -I "$NODE_INC" \
    -o "$WORK/liblobosflock.so" \
    container/native/d2/flock.c 2>"$WORK/cc-flock.log" \
    && { so_ok "$WORK/liblobosflock.so" 1000 && cp -f "$WORK/liblobosflock.so" "$OUT/liblobosflock.so" && echo "  ✓ flock      → liblobosflock.so"; } \
    || echo "  - flock 编不出来（缺 node 头文件），这项不随包带" >&2
else
  echo "  - flock 需要 node 头文件（NODE_INCLUDE_DIR 未给），不随包带" >&2
fi

echo ""
echo "[compat] 垫片已落 $OUT"
ls -la "$OUT"/liblobosposix.so "$OUT"/liblobosptyprobe.so "$OUT"/librivospty.so 2>/dev/null \
  || echo "[compat] 有产物缺失"
