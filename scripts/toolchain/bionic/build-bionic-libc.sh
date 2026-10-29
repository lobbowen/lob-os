#!/usr/bin/env bash
# 编 bionic 的 libc.so（=> 而那 loader 那一族与那 libc 那一族那两件的前置）。
#
# 这一份逐字记的是那读数归 07 与那产物与那编归 05 那两条线的分界：
#   => 而那读数（=> 而那设备侧逐字怎么起、=> 而那产物逐字多大）归 07；
#   => 而那编（=> 而那怎么编出来）归 05（=> 就是这一份）。
#
# 用法：build-bionic-libc.sh <bionic 源码树> <兄弟项目根> <产物落点>
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

# 兄弟项目根（=> 而那它们逐字是那几族：system/libbase、system/core、system/libziparchive、
# external/libcxx、external/libcxxabi、external/gwp_asan、external/jemalloc_new、platform/external/libunwind）。
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

#  缺陷名（C-102）那 atomic 那一族逐字在那 bionic 那 bits/stdatomic.h 与那 libc++ 的 <atomic> 之间撞了。
#    => 而那处置：=> 而那 bits/stdatomic.h 逐字用那 INC_DIR/bits-stdatomic.h 那一份（=> 而那**它**逐字是那**委托**给那 libc++ 的 <atomic> 的那一份）、
#      => 而而那**它**逐字**先**把那**拷贝**到**那**源码树**那一层**（=> 而那 INCLUDE/bits/ 底下**那一个**）、
#      => 而那 INCLUDE 逐字**先**建**好**。
#  => 而那**拷贝**过**来**的**那**一份**逐字**盖**在**那**源码树**自己**的**那**一份**上**（=> 而而那**它**们**逐字是**那**两**件**事**：「那**是**什么**」与「那**为什么**要**盖**」）。
SA="$SRC/libc/include/bits/stdatomic.h"
if [ -f "$SA" ] && ! cmp -s "$SA" "$INC_DIR/bits-stdatomic.h"; then
  cp "$SA" "$WORK/stdatomic.h.orig"
  cp "$INC_DIR/bits-stdatomic.h" "$SA"
fi
mkdir -p "$WORK/patched/bits"
cp "$INC_DIR/bits-stdatomic.h" "$WORK/patched/bits/stdatomic.h"

#  => 而那 <bionic/xxx.h> **那一族**逐字**要**那**一个** **bionic/** **那一层**（=> 而而那**它**们**逐字是**那**两**件**事**：「那**引**的是**什么**」与「那**它**在**哪**一层**」）。
#     => 而那 bionic 那 libc/platform/bionic 底下**那**几个**头**逐字**在**那**一处**、=> 而那**引**的**是**那「<bionic/xxx.h>」。
mkdir -p "$WORK/patched/bionic"
for h in "$SRC"/libc/platform/bionic/*.h; do
  [ -e "$h" ] && cp "$h" "$WORK/patched/bionic/"
done
trap 'rm -rf "$WORK"' EXIT

INC=(-nostdinc)

#  => 而那**拷贝**过**来**的**那**一份**逐字**要**在**那 bionic **自己**的**那** libc/include **之前**（=> 而而那**它**们**逐字是**那**两**件**事**）。
INC+=(-isystem "$LIBCXX")

#  => 而那**改过**的**那**一份 bits/stdatomic.h 逐字**要**在**那 bionic **自己**的**那**一份 **之前**（=> 而而那**它**们**逐字是**那**两**件**事**）。
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
#  => 而那 libc++ **那一族**逐字**要**在**那 bionic **自己**的**那** libc/include **之前**（=> 而而那**它**们**逐字是**那**两**件**事**）。
#  => 而那 libc++ **那一族** 逐字**可以**在**那 SIB **底下**、=> 而而那**它**也**可以**在**那 LIBCXX **那一层**（=> 而那 work2 与那 work3 **那两**个**根**）。
INC+=(-isystem "$RES" -isystem "$SYS" -isystem "$ASM")

#  缺陷名（C-102）那 atomic 那一族逐字在那 bionic 那 bits/stdatomic.h 与那 libc++ 的 <atomic> 之间撞了。
#    => 而那成因：=> 而那 bionic 那 bits/stdatomic.h :153 那一行 = 那「#define atomic_is_lock_free(obj) ...」，
#      => 而而那它逐字把那 libc++ 自己的那个函数名拆了（=> 而那 libc++ 的 atomic :1166 那一行）。
#      => 而而那后果逐字 = 而那编过的个数上不去。
#    => 而那处置：=> 而那三步：
#      (1) 而那 bits/stdatomic.h 逐字先给那 memory_order 那一族（=> 而那 :128 到那 :135 那几行）；
#      (2) 而那那几个宏逐字undef 了（=> 而那 atomic_is_lock_free 与那那几个操作宏）；
#      (3) 而那 libc++ 的 <atomic> 逐字后给那 std::atomic 那一族。
echo "[build-bionic-libc] 那 atomic 那一族逐字用那 INC_DIR/atomic-shim.h（=> 而那三步）"

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
[ "$FAIL" -eq 0 ] || { echo "[build-bionic-libc] 还有编不过的、=> 而那产物逐字不出来" >&2; exit 1; }

echo "[build-bionic-libc] 链接那产物"
"${CLANG}" -nostdlib -shared -fPIC -O1 -w -o "$OUT" "${OBJS[@]}"
echo "[build-bionic-libc] 产物 = $OUT（$(stat -c %s "$OUT") 字节）"
