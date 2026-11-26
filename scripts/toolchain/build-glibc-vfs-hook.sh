#!/usr/bin/env bash
# Build the glibc-flavoured VFS hook for guest programs loaded by the glibc
# dynamic linker. bionic's liblobosposix.so cannot be loaded by glibc guests
# (it depends on bionic libc.so), so guest runtime path operations had no VFS
# translation -- only exec-time translation happened in exec-path.c.
#
# This so interposes the path syscalls (open/stat/readlink/mkdir/rename/...)
# using the same vfs-root.h translation as the bionic hook. It is injected via
# LD_PRELOAD in the glibc guest branch alongside libguest-sigsys.so.
set -euo pipefail
cd "$(dirname "$0")/../.." || exit 2

TC=work/glibc-build/tc
GCC=$TC/usr/bin/aarch64-linux-gnu-gcc-15
LD=$TC/usr/bin/aarch64-linux-gnu-ld.bfd
LIBDIR=$(pwd)/dist-glibc-runtime/lib
OUT=container/app/src/main/jniLibs/arm64-v8a
WORK=${WORK:-/tmp/glibc-vfs-hook-build}

if [ ! -x "$GCC" ] || [ ! -d "$LIBDIR" ]; then
  echo "报缺：glibc cross toolchain 或 runtime 不在（$TC / $LIBDIR）" >&2
  exit 2
fi

export LD_LIBRARY_PATH="$TC/usr/lib/x86_64-linux-gnu:$TC/lib/x86_64-linux-gnu:${LD_LIBRARY_PATH:-}"
rm -rf "$WORK" && mkdir -p "$WORK"

# The VFS path hooks. exec-path.c is deliberately excluded (would recurse
# through the loader); sigsys has its own glibc so already.
SRCS="container/native/d1/tmp-paths.c container/native/d1/open-fallback.c"
OBJS=""
for f in $SRCS; do
  o="$WORK/$(basename "$f" .c).o"
  "$GCC" --sysroot="$TC/usr" -shared -fPIC -O2 -I container/native/d1 -c "$f" -o "$o" \
    2>"$WORK/cc.log" || { echo "!! 编译 $f 失败" >&2; tail -20 "$WORK/cc.log" >&2; exit 1; }
  OBJS="$OBJS $o"
done

# shellcheck disable=SC2086
"$LD" -shared -o "$WORK/liblobos-vfs-glibc.so" $OBJS \
  -L"$LIBDIR" -l:libc.so.6 --allow-shlib-undefined \
  2>"$WORK/ld.log" || { echo "!! 链接失败" >&2; tail -20 "$WORK/ld.log" >&2; exit 1; }

cp -f "$WORK/liblobos-vfs-glibc.so" "$OUT/liblobos-vfs-glibc.so"
chmod 644 "$OUT/liblobos-vfs-glibc.so"
echo "  ✓ glibc VFS hook → liblobos-vfs-glibc.so ($(stat -c%s "$OUT/liblobos-vfs-glibc.so") bytes)"
echo "  hooks: $(nm -D "$OUT/liblobos-vfs-glibc.so" | grep -cE ' T (open|openat|stat|fstatat|readlink|mkdir|rename|unlink)$')"
