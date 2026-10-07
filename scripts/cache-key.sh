#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

HERE="$(dirname "$0")"
ROOT_DIR="$(cd "$HERE/.." && pwd)"

TOOL=""
KIND="key"
for a in "$@"; do
  case "$a" in
    tag) KIND="tag" ;;
    key) KIND="key" ;;
    *) [ -n "$TOOL" ] && die "只能给一个件名（收到 $a 与 $TOOL）"; TOOL="$a" ;;
  esac
done
[ -n "$TOOL" ] || { echo "用法: $0 <件名> | $0 tag <件名>" >&2; exit 2; }
TABLE="$ROOT_DIR/scripts/userland-sources.json"

deps_for() {
  case "$1" in
    make)        echo "make" ;;
    cmake)       echo "cmake" ;;
    python3)     echo "python openssl zlib" ;;
    pkg-config)  echo "pkgconf" ;;
    curl)        echo "curl openssl zlib" ;;
    git)         echo "git openssl zlib curl" ;;
    jq)          echo "jq" ;;
    sqlite3)     echo "sqlite" ;;
    sysroot)     echo "" ;;
    node|npm|pnpm) echo "" ;;
    llvmtoolchain) echo "llvm" ;;
    *) die ;;
  esac
}

die() { echo "::error title=未知件名::$TOOL 不在已知列表里" \
             "—— 加新件时要在这里补一条 deps_for，否则缓存 key 算不准"; exit 1; }

DEPS="$(deps_for "$TOOL")"
VER=""
for k in $DEPS; do
  v="$(bash "$ROOT_DIR/scripts/fetch-pinned.sh" --src-version "$k" 2>/dev/null || true)"
  [ -n "$v" ] || v="none"
  VER="$VER$k-$v"
done
[ -n "$VER" ] || VER="nodeps"

if [ "$TOOL" = "git" ]; then
  P=""
  for k in $(node -e '
    const t=require(process.argv[1]);
    process.stdout.write(Object.keys(t.sources).filter(x=>/^git-.*\.patch$/.test(x)).join(" "));
  ' "$TABLE"); do
    v="$(bash "$ROOT_DIR/scripts/fetch-pinned.sh" --src-version "$k" 2>/dev/null || true)"
    P="$P$(echo "$k" | head -c6)-${v:0:8}; "
  done
  VER="$VER|patch:$P"
fi

NDK="$(bash "$ROOT_DIR/scripts/fetch-pinned.sh" --ndk 2>/dev/null || echo unknown)"
API=23

RUN_OS="${RUN_OS:-Linux}"
RUN_ARCH="${RUN_ARCH:-X64}"

if [ "$KIND" = "tag" ]; then
  SAFE="$(printf '%s' "uw-$TOOL-$VER-ndk$NDK-api$API" \
    | tr -c 'A-Za-z0-9._-' '-' \
    | tr -s '-' '-' \
    | sed 's/^[-.]*//; s/[-.]*$//')"
  echo "${SAFE}-${RUN_OS}-${RUN_ARCH}"
else
  echo "uw-$TOOL-$VER-ndk$NDK-api$API-$RUN_OS-$RUN_ARCH"
fi