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
# ★ 不要 source scripts/recipes/piece-env.sh：
#   那个是**给件准备的** —— 它要求传件名、会 cd 到仓根、定义 land_piece/gen_meta
#   并按件的形状落位。垫片三样都不要；而且它开头 `${1:?用法: source
#   piece-env.sh <件名>}` 在没传参时直接 exit 1 —— 曾经让 APK 构建在这里挂掉
#   （而 CC 其实由 CI 注入，压根用不着它）。
#
#   垫片需要的只有编译器。CC 由 CI 注入；本地没有就跑 locate-ndk.sh。
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
cd "$ROOT"

ABI="${ABI:-arm64-v8a}"
OUT="$ROOT/container/app/src/main/jniLibs/$ABI"
WORK="$ROOT/work/kernel-compat"

die() { echo "::error title=$1::$(printf '%s\n' "${@:2}")" >&2; exit 1; }

# 编译器：CI 注入了就用，没注入就自己定位（locate-ndk 会写 GITHUB_ENV）
if [ -z "${CC:-}" ]; then
  [ -f "$ROOT/scripts/toolchain/locate-ndk.sh" ] \
    || die "缺编译器" "环境里没有 CC，也没有 scripts/toolchain/locate-ndk.sh"
  _GHE="$(mktemp)"
  GITHUB_ENV="$_GHE" bash "$ROOT/scripts/toolchain/locate-ndk.sh" \
    || die "定位 NDK 失败" "locate-ndk.sh 没跑通（见上面的输出）"
  while IFS="=" read -r _k _v; do
    case "$_k" in
      CC|CXX|LLVM_AR|LLVM_RANLIB|TRIPLE) export "$_k=$_v" ;;
    esac
  done < "$_GHE"
  rm -f "$_GHE"
fi
[ -n "${CC:-}" ] || die "定位 NDK 失败" "locate-ndk.sh 跑完了但没给出 CC"

mkdir -p "$OUT" "$WORK"

# 至少是这个字节数的 ELF
so_ok() {
  local f="$1" min="$2" sz
  [ -f "$f" ] || { echo "!! 没产出：$f" >&2; return 1; }
  sz="$(stat -c%s "$f" 2>/dev/null || echo 0)"
  [ "$sz" -ge "$min" ] || { echo "!! 太小（$sz < $min）：$f" >&2; return 1; }
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

# ── ptyprobe：PTY 能力探测（-static 与原脚本一致，别改成 -shared）──────
"$CC" -static -O2 \
  -o "$WORK/liblobosptyprobe.so" \
  container/native/d2/pty-probe.c 2>"$WORK/cc-ptyprobe.log" \
  || { echo "!! ptyprobe 编不出来" >&2; tail -20 "$WORK/cc-ptyprobe.log" >&2; exit 1; }
so_ok "$WORK/liblobosptyprobe.so" 1000
cp -f "$WORK/liblobosptyprobe.so" "$OUT/liblobosptyprobe.so"
echo "  ✓ ptyprobe   → liblobosptyprobe.so"

# ── ptysession：PTY 宿主（被当可执行程序驱动，不是 preload）────────────
"$CC" -static -O2 \
  -o "$WORK/librivospty.so" \
  container/native/d3/pty-session.c 2>"$WORK/cc-ptysession.log" \
  || { echo "!! ptysession 编不出来" >&2; tail -20 "$WORK/cc-ptysession.log" >&2; exit 1; }
so_ok "$WORK/librivospty.so" 1000
cp -f "$WORK/librivospty.so" "$OUT/librivospty.so"
echo "  ✓ ptysession → librivospty.so"

# ── flock：文件锁（node addon，依赖 node 头文件）────────────────────
# node 头文件没有就不带这一件 —— 它是可选增强，不该让整条链失败。
if [ -n "${NODE_INCLUDE_DIR:-}" ] && [ -d "${NODE_INCLUDE_DIR:-}" ]; then
  "$CC" -shared $CFLAGS -DNAPI_VERSION=9 -I "$NODE_INCLUDE_DIR" \
    -o "$WORK/liblobosflock.so" \
    container/native/d2/flock.c 2>"$WORK/cc-flock.log" \
    && { so_ok "$WORK/liblobosflock.so" 1000 && cp -f "$WORK/liblobosflock.so" "$OUT/liblobosflock.so" && echo "  ✓ flock      → liblobosflock.so"; } \
    || echo "  - flock 编不出来（node 头文件相关），这项不随包带" >&2
else
  echo "  - flock 需要 node 头文件（NODE_INCLUDE_DIR 未给），不随包带" >&2
fi

find "$OUT" -maxdepth 1 -type f \( -name 'liblobos*.so' -o -name 'librivospty.so' \) \
  -exec chmod 644 {} + 2>/dev/null || true

echo ""
echo "[compat] 垫片已落 $OUT"
ls -la "$OUT"/liblobosposix.so "$OUT"/liblobosptyprobe.so "$OUT"/librivospty.so 2>/dev/null \
  || echo "[compat] 有产物缺失"