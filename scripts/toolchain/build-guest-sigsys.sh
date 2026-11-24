#!/usr/bin/env bash
# Build the glibc-side SIGSYS handler used by the Linux guest compatibility layer.
# Android seccomp (untrusted_app) answers rseq(293) and friends with SIGSYS;
# glibc 2.35+ calls rseq at startup, so a guest process dies with signal 31
# unless a SIGSYS handler emulates a graceful result.
#
# The handler must be a *glibc ABI* .so (loaded by the guest's ld-linux),
# built with a real aarch64 glibc cross toolchain. We obtain the toolchain
# from Ubuntu debs without root (dpkg-deb -x into a local prefix) and build
# a tiny shared object following ssfuisu/cortex signals.c + syscalls.c
# (Apache-2.0, the technique used by DroidDeck nativeguest).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
SRC="$ROOT/container/native/guest-sigsys/sigsys.c"
OUT="$ROOT/container/app/src/main/jniLibs/arm64-v8a/libguest-sigsys.so"
TC="${GLIBC_TC_PREFIX:-/tmp/glibc-toolchain/root}"

if [ ! -x "$TC/usr/bin/aarch64-linux-gnu-gcc-15" ]; then
  echo "[sigsys] glibc cross toolchain 不在 $TC，跳过（见 docs 第十一轮获取方式）" >&2
  exit 0
fi
export LD_LIBRARY_PATH="$TC/usr/lib/x86_64-linux-gnu:$TC/lib/x86_64-linux-gnu:${LD_LIBRARY_PATH:-}"
GCC="$TC/usr/bin/aarch64-linux-gnu-gcc-15"
LD="$TC/usr/bin/aarch64-linux-gnu-ld.bfd"
SYSROOT="$TC/usr/aarch64-linux-gnu"
OBJ="$(mktemp /tmp/guest-sigsys.XXXXXX.o)"
trap 'rm -f "$OBJ"' EXIT
"$GCC" --sysroot="$SYSROOT" -O2 -fPIC -c -o "$OBJ" "$SRC"
"$LD" -shared -o "$OUT" "$OBJ" "$SYSROOT/lib/libc.so.6"
chmod 644 "$OUT"
echo "[sigsys] 已生成 $OUT"
