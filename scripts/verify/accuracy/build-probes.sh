#!/usr/bin/env bash
# Build the accuracy probes for every environment the harness needs.
set -euo pipefail
cd "$(dirname "$0")/../../.." || exit 2
OUT=scripts/verify/accuracy/bin
mkdir -p "$OUT"

# 1) Host x86_64 Linux probe = the POSIX-semantics reference baseline.
gcc -O0 -o "$OUT/probe-host" scripts/verify/accuracy/probe.c
echo "  host probe: $OUT/probe-host"

# 2) aarch64 static probe = runs on-device via raw exec (Android-native ref)
#    and is the form that exercises LobOS raw_execve when invoked without hook.
CC=/home/bowen/ndk-acquire/android-ndk-r30/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android35-clang
if [ -x "$CC" ]; then
  "$CC" -static -o "$OUT/probe-aarch64-static" scripts/verify/accuracy/probe.c
  echo "  aarch64 static probe: $OUT/probe-aarch64-static"
fi

# 3) aarch64 glibc dynamic probe (exercises the glibc loader + hook path).
#    Uses the self-built cross toolchain with explicit sysroot libs.
TC=work/glibc-build/tc
GCC=$TC/usr/bin/aarch64-linux-gnu-gcc-15
LD=$TC/usr/bin/aarch64-linux-gnu-ld.bfd
if [ -x "$GCC" ] && [ -d dist-glibc-runtime/lib ]; then
  export LD_LIBRARY_PATH="$TC/usr/lib/x86_64-linux-gnu:$TC/lib/x86_64-linux-gnu:${LD_LIBRARY_PATH:-}"
  SYS=$(pwd)/dist-glibc-runtime
  # link with the cross gcc, but force the sysroot include/lib via -B and -L
  "$GCC" -nostdinc -I"$SYS/include" -I"$GCC/../../../../aarch64-linux-gnu/include" \
    -c scripts/verify/accuracy/probe.c -o "$OUT/probe-glibc.o" 2>/dev/null || true
  if [ -f "$OUT/probe-glibc.o" ]; then
    "$LD" --dynamic-linker /lib/ld-linux-aarch64.so.1 \
      -o "$OUT/probe-glibc" "$OUT/probe-glibc.o" \
      -L "$SYS/lib" -l:libc.so.6 \
      -rpath-link "$SYS/lib" 2>/dev/null && echo "  aarch64 glibc probe: $OUT/probe-glibc" \
      || rm -f "$OUT/probe-glibc"
  fi
fi
echo "done"
