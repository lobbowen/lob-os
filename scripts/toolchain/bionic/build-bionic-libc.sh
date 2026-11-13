#!/usr/bin/env bash

set -euo pipefail

SRC="${1:?用法: build-bionic-libc.sh <bionic 源码树> <兄弟项目根> <产物落点>}"
SIB="${2:?用法: build-bionic-libc.sh <bionic 源码树> <兄弟项目根> <产物落点> [libc++ 头目录]}"
LIBCXX="${4:-$SIB/libcxx/include}"
EXTRA="${5:-$SIB}"
OUT="${3:?用法: build-bionic-libc.sh <bionic 源码树> <兄弟项目根> <产物落点>}"

NDK_ROOT="${NDK_ROOT:-/home/bowen/ndk-acquire/sdk/ndk/30.0.16248370}"
PRE="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64"
CLANG="$PRE/bin/aarch64-linux-android35-clang"
RES="$PRE/lib/clang/21/include"
SYS="$PRE/sysroot/usr/include"
ASM="$SYS/aarch64-linux-android"

HERE="$(cd "$(dirname "$0")" && pwd)"
INC_DIR="$HERE/include"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

SA="$SRC/libc/include/bits/stdatomic.h"
if [ -f "$SA" ] && ! cmp -s "$SA" "$INC_DIR/bits-stdatomic.h"; then
  cp "$SA" "$WORK/stdatomic.h.orig"
  cp "$INC_DIR/bits-stdatomic.h" "$SA"
fi
mkdir -p "$WORK/patched/bits"
cp "$INC_DIR/bits-stdatomic.h" "$WORK/patched/bits/stdatomic.h"

mkdir -p "$WORK/patched/bionic"
for h in "$SRC"/libc/platform/bionic/*.h; do
  [ -e "$h" ] && cp "$h" "$WORK/patched/bionic/"
done
trap 'rm -rf "$WORK"' EXIT

INC=(-nostdinc)

INC+=(-isystem "$LIBCXX")

INC+=(-I"$WORK/patched")
for d in \
  "$SIB/libbase/include" "$SIB/libziparchive/include" "$SIB/core/libutils/include" \
  "$SIB/core/debuggerd/include" "$SIB/core/libcutils/include" "$SIB/core/libcutils/include/private" \
  "$SIB/gwp_asan" "$SIB/jemalloc_new/include" "$SIB/libcxxabi/include" "$SIB/libcxx/include" \
  "$EXTRA/gwp_asan" "$EXTRA/jemalloc_new/include" "$EXTRA/libcxxabi/include" "$EXTRA/libcxx/include" \
  "$SRC/libc/kernel/uapi" "$SRC/libc" "$SRC/libc/include" "$SRC/libc/private" \
  "$SRC/libc/platform/common" "$SRC/libc/platform/bionic" "$SRC/libc/platform/system/include" \
  "$SRC/libc/system/include" "$SRC/libc/system/core/include" "$SRC/libc/bionic/libc/include" \
  "$SRC/libc/async_safe/include" "$SRC/libc/async_safe/include/async_safe" \
  "$SRC/libc/platform/bionic" "$SRC/libc/system_properties/include" "$SRC/libc/stdio" \
  "$SRC/libziparchive" "$SRC/libziparchive/include" "$SRC/libunwindstack/include" \
  "$SRC/external/selinux/libselinux/include" "$SRC/external/selinux/libsepol" \
  "$SRC/system/libziparchive" "$SRC/system/libziparchive/include" \
  ; do
  [ -d "$d" ] && INC+=(-I"$d")
done

INC+=(-isystem "$RES" -isystem "$SYS" -isystem "$ASM")

echo "[build-bionic-libc] 那 atomic 那一族逐字用那 INC_DIR/atomic-shim.h（三步）"

OBJS=()
OK=0
FAIL=0
for s in "$SRC"/libc/bionic/*.cpp "$SRC"/libc/bionic/*.c; do
  [ -e "$s" ] || continue
  n="$(basename "$s")"
  o="$WORK/${n%.*}.o"
  std=-std=c11
  case "$n" in *.cpp) std=-std=c++20 ;; esac
  args=("$CLANG" -fPIC -D__ANDROID_API__=35 "$std" -O1 -w -fno-exceptions -fno-rtti)
  case "$n" in *.cpp) args+=(-include "$INC_DIR/atomic-shim.h" -include "$INC_DIR/config_site") ;; esac
  if "${args[@]}" "${INC[@]}" -c "$s" -o "$o" 2>"$WORK/err"; then
    OBJS+=("$o"); OK=$((OK+1))
  else
    FAIL=$((FAIL+1))
    echo "[build-bionic-libc] 编不过：$n" >&2
    head -3 "$WORK/err" >&2
  fi
done
echo "[build-bionic-libc] 编过 $OK 个、编不过 $FAIL 个"
[ "$FAIL" -eq 0 ] || { echo "[build-bionic-libc] 还有编不过的、产物逐字不出来" >&2; exit 1; }

echo "[build-bionic-libc] 链接那产物"
"${CLANG}" -nostdlib -shared -fPIC -O1 -w -o "$OUT" "${OBJS[@]}"
echo "[build-bionic-libc] 产物 = $OUT（$(stat -c %s "$OUT") 字节）"
