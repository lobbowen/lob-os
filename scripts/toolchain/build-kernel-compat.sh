#!/usr/bin/env bash

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
cd "$ROOT"

ABI="${ABI:-arm64-v8a}"
OUT="$ROOT/container/app/src/main/jniLibs/$ABI"
WORK="$ROOT/work/kernel-compat"

die() { echo "::error title=$1::$(printf '%s\n' "${@:2}")" >&2; exit 1; }

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

so_ok() {
  local f="$1" min="$2" sz
  [ -f "$f" ] || { echo "!! 没产出：$f" >&2; return 1; }
  sz="$(stat -c%s "$f" 2>/dev/null || echo 0)"
  [ "$sz" -ge "$min" ] || { echo "!! 太小（$sz < $min）：$f" >&2; return 1; }
}

CFLAGS="-O2 -fPIC"

"$CC" -shared $CFLAGS \
  container/native/d1/link-interpose.c \
  container/native/d1/open-fallback.c \
  container/native/d1/tmp-paths.c \
  container/native/d1/lobos-env.c \
    container/native/d1/exec-path.c \
  -ldl \
  -o "$WORK/liblobosposix.so" 2>"$WORK/cc-posix.log" \
  || { echo "!! posix 编不出来" >&2; tail -20 "$WORK/cc-posix.log" >&2; exit 1; }

so_ok "$WORK/liblobosposix.so" 500
cp -f "$WORK/liblobosposix.so" "$OUT/liblobosposix.so"
echo "  ✓ posix      → liblobosposix.so"

"$CC" -O2 -fPIE -pie \
  -o "$WORK/librivospty.so" \
  container/native/d3/pty-session.c \
  -Wl,--no-as-needed -L"$WORK" -llobosposix \
  2>"$WORK/cc-ptysession.log" \
  || { echo "!! ptysession 编不出来" >&2; tail -20 "$WORK/cc-ptysession.log" >&2; exit 1; }
so_ok "$WORK/librivospty.so" 1000
cp -f "$WORK/librivospty.so" "$OUT/librivospty.so"
echo "  ✓ ptysession → librivospty.so"

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
ls -la "$OUT"/liblobosposix.so "$OUT"/librivospty.so 2>/dev/null \
  || echo "[compat] 有产物缺失"
