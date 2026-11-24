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
# 通用版（cortex signals.c+syscalls.c 移植）：SIGSYS handler + sigaction 族 hook，
# 兜住 Android seccomp 拦的全部 syscall（424-512 含 clone3、rseq、uid 族等），
# 且防止 guest 程序覆盖/屏蔽 handler。这是所有 glibc 原生程序的通用底座。
SRC="$ROOT/container/native/guest-sigsys/sigsys-universal.c"
OUT="$ROOT/container/app/src/main/jniLibs/arm64-v8a/libguest-sigsys.so"
# 工具链优先稳定目录（/tmp 会被周期清理），回退旧路径
TC_DEFAULT="$ROOT/work/glibc-build/tc"
[ -x "$TC_DEFAULT/usr/bin/aarch64-linux-gnu-gcc-15" ] || TC_DEFAULT="/tmp/glibc-toolchain/root"
TC="${GLIBC_TC_PREFIX:-$TC_DEFAULT}"

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
