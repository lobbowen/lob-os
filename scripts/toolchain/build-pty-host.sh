#!/usr/bin/env bash
# Build the PTY host (librivospty.so) for the interactive terminal.
# All V1 compatibility shims (liblobosposix / glibc loader / hooked bash /
# SIGSYS / VFS hook) were removed: programs run in the Ubuntu rootfs via
# fastroot, and the terminal uses /bin/bash from the system disk.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
cd "$ROOT"

ABI="${ABI:-arm64-v8a}"
OUT="$ROOT/container/app/src/main/jniLibs/$ABI"
WORK="$ROOT/work/pty-host"

die() { echo "::error title=$1::$(printf '%s\n' "${@:2}")" >&2; exit 1; }

if [ -z "${CC:-}" ]; then
  [ -f "$ROOT/scripts/toolchain/locate-ndk.sh" ] \
    || die "缺编译器" "环境里没有 CC，也没有 scripts/toolchain/locate-ndk.sh"
  _GHE="$(mktemp)"
  GITHUB_ENV="$_GHE" bash "$ROOT/scripts/toolchain/locate-ndk.sh" \
    || die "定位 NDK 失败" "locate-ndk.sh 没跑通"
  while IFS="=" read -r _k _v; do
    case "$_k" in
      CC|CXX|LLVM_AR|LLVM_RANLIB|TRIPLE) export "$_k=$_v" ;;
    esac
  done < "$_GHE"
  rm -f "$_GHE"
fi
[ -n "${CC:-}" ] || die "定位 NDK 失败" "locate-ndk.sh 跑完了但没给出 CC"

mkdir -p "$OUT" "$WORK"

"$CC" -O2 -fPIE -pie \
  -o "$WORK/librivospty.so" \
  container/native/d3/pty-session.c \
  2>"$WORK/cc-ptysession.log" \
  || { echo "!! ptysession 编不出来" >&2; tail -20 "$WORK/cc-ptysession.log" >&2; exit 1; }

SZ="$(stat -c%s "$WORK/librivospty.so" 2>/dev/null || echo 0)"
[ "$SZ" -ge 1000 ] || die "产物过小" "librivospty.so=$SZ"
cp -f "$WORK/librivospty.so" "$OUT/librivospty.so"
chmod 644 "$OUT/librivospty.so"
echo "  ptysession -> librivospty.so ($SZ bytes)"

# Confirm no legacy hook dependency remains.
if command -v readelf >/dev/null 2>&1; then
  if readelf -d "$OUT/librivospty.so" 2>/dev/null | grep -q 'liblobosposix'; then
    die "残留V1链接" "librivospty.so 仍依赖 liblobosposix.so"
  fi
fi
