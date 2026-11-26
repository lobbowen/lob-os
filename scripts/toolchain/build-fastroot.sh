#!/usr/bin/env bash
# Build fastroot (Apache-2.0, github.com/spizganed/fastroot) as LobOS native
# libraries. syscall-level userland emulation: chroot/bind paths, fake root,
# hard links, /proc synthesis, static-binary patching -- primitives LD_PRELOAD
# alone cannot provide. Source vendored under work/external/fastroot/.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT" || exit 2

SRC="$ROOT/work/external/fastroot/fastroot-main"
TC="$ROOT/work/glibc-build/tc"
OUT="$ROOT/container/app/src/main/jniLibs/arm64-v8a"
GCC="$TC/usr/bin/aarch64-linux-gnu-gcc-15"

if [ ! -f "$SRC/Makefile" ] || [ ! -x "$GCC" ]; then
  echo "报缺：fastroot 源码或 glibc cross toolchain 不在（$SRC / $TC）" >&2
  exit 2
fi

export LD_LIBRARY_PATH="$TC/usr/lib/x86_64-linux-gnu:$TC/lib/x86_64-linux-gnu:${LD_LIBRARY_PATH:-}"
# cross gcc's libc.so link script hard-codes /usr/aarch64-linux-gnu/lib;
# with --sysroot=$TC/usr it resolves under $TC/usr/usr/... — provide that view.
LIBSRC="$TC/usr/aarch64-linux-gnu/lib"
mkdir -p "$TC/usr/usr/aarch64-linux-gnu/lib"
for f in libc.so.6 libc_nonshared.a ld-linux-aarch64.so.1; do
  [ -e "$LIBSRC/$f" ] && [ ! -e "$TC/usr/usr/aarch64-linux-gnu/lib/$f" ] \
    && ln -sf "$LIBSRC/$f" "$TC/usr/usr/aarch64-linux-gnu/lib/$f"
done

cd "$SRC"
make clean >/dev/null 2>&1 || true
make CC="$GCC" \
  "CFLAGS=-O2 -Wall --sysroot=$TC/usr" \
  "HOOK=-fPIC -mgeneral-regs-only -mno-outline-atomics -fno-builtin -fno-tree-loop-distribute-patterns -fno-stack-protector -fvisibility=hidden --sysroot=$TC/usr" \
  all >/dev/null

cp -f "$SRC/libfastroot.so" "$OUT/libfr-shim.so"
cp -f "$SRC/fastroot-launch" "$OUT/libfr-launch.so"
chmod 644 "$OUT/libfr-shim.so" "$OUT/libfr-launch.so"
echo "  ✓ fastroot → libfr-launch.so ($(stat -c%s "$OUT/libfr-launch.so")) + libfr-shim.so ($(stat -c%s "$OUT/libfr-shim.so"))"
