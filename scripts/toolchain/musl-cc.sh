#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C
HERE="$(dirname "$0")"
ROOT_DIR="$(cd "$HERE/../.." && pwd)"

die() {
  local title="$1"; shift
  echo "::error title=$title::$(printf '%s\n' "$@")"
  exit 1
}

SYSROOT="${LOBOS_MUSL_SYSROOT:-$ROOT_DIR/work/musl-sysroot}"
[ -f "$SYSROOT/usr/lib/crt1.o" ] || die "musl sysroot 没就位" \
  "先跑 scripts/toolchain/musl-sysroot.sh（当前找的位置：$SYSROOT）"

find_ndk() {
  local want="$1"
  local c
  for c in "${LOBOS_NDK:-}" \
           "${ANDROID_SDK_ROOT:-}/ndk/$want" \
           "${ANDROID_HOME:-}/ndk/$want" \
           "${ANDROID_NDK_LATEST_HOME:-}"; do
    [ -n "$c" ] && [ -d "$c/toolchains/llvm/prebuilt" ] && { echo "$c"; return 0; }
  done
  return 1
}

WANT_NDK="$(bash "$ROOT_DIR/scripts/toolchain/fetch-pinned.sh" --ndk 2>/dev/null || true)"
NDK="$(find_ndk "$WANT_NDK")" || die "找不到 NDK" \
  "可用 LOBOS_NDK 指定 NDK 根目录；钉值版本=$WANT_NDK"

HOST_OS="$(uname -s | tr 'A-Z' 'a-z')"
HOST_ARCH="$(uname -m)"
TC="$NDK/toolchains/llvm/prebuilt/${HOST_OS}-x86_64/bin"
[ -x "$TC/clang" ] || die "NDK 里没有 clang" "$TC/clang"
[ -x "$TC/ld.lld" ] || die "NDK 里没有 ld.lld" "$TC/ld.lld"

MODE=exe
SHARED=0
COMPILE_ONLY=0
for a in "$@"; do
  case "$a" in
    -c|/c) COMPILE_ONLY=1 ;;
    -shared) SHARED=1 ;;
  esac
done
[ "$SHARED" = 1 ] && MODE=shared
[ "$COMPILE_ONLY" = 1 ] && MODE=obj

OUT_NAME=""
PREV=""
for a in "$@"; do
  [ "$PREV" = "-o" ] && OUT_NAME="$(basename "$a")"
  PREV="$a"
done

VERSIONED_LIB=0
case "$OUT_NAME" in
  lib*.so.[0-9]*) VERSIONED_LIB=1 ;;
esac

SONAME_ARGS=()
if [ "$SHARED" = 1 ] && [ "$VERSIONED_LIB" = 1 ]; then
  HAS_SONAME=0
  for a in "$@"; do
    case "$a" in
      -Wl,-soname*|-soname*|--soname*|-h) HAS_SONAME=1 ;;
    esac
  done
  if [ "$HAS_SONAME" = 0 ]; then
    [ -n "${LOBOS_SONAME:-}" ] || die "共享库缺 SONAME" \
      "带版本号的共享库必须有身份：调用方给 -Wl,-soname，或用 LOBOS_SONAME=libfoo.so.1（$OUT_NAME）"
    SONAME_ARGS=("-Wl,-soname,${LOBOS_SONAME}")
  fi
fi

INTERP_ARGS=()
if [ -n "${LOBOS_MUSL_INTERP:-}" ]; then
  INTERP_ARGS=("-Wl,--dynamic-linker=${LOBOS_MUSL_INTERP}")
fi

COMMON=(--target=aarch64-linux-musl "--sysroot=$SYSROOT" "-fuse-ld=$TC/ld.lld")

case "$MODE" in
  obj)
    exec "$TC/clang" --target=aarch64-linux-musl "--sysroot=$SYSROOT" "$@"
    ;;
  shared)
    exec "$TC/clang" "${COMMON[@]}" -nostdlib "$SYSROOT/usr/lib/crti.o" \
      "${SONAME_ARGS[@]}" "$@" "$SYSROOT/usr/lib/crtn.o" -lc
    ;;
  *)
    exec "$TC/clang" "${COMMON[@]}" -nostdlib "$SYSROOT/usr/lib/crt1.o" \
      "${INTERP_ARGS[@]}" "$@" -lc
    ;;
esac