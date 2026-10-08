#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

HERE="$(dirname "$0")"
ROOT_DIR="$(cd "$HERE/../.." && pwd)"

TOOL=""
KIND="key"
for a in "$@"; do
  case "$a" in
    tag) KIND="tag" ;;
    key) KIND="key" ;;
    asset) KIND="asset" ;;
    *)
      if [ -n "$TOOL" ]; then
        echo "只能给一个件名（收到 $a 与 $TOOL）" >&2
        exit 2
      fi
      TOOL="$a"
      ;;
  esac
done
[ -n "$TOOL" ] || { echo "用法: $0 <件名> | $0 tag <件名>" >&2; exit 2; }
TABLE="$ROOT_DIR/scripts/component-sources.json"

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
    bash|rg|busybox) echo "build-apk-only" ;;
    *) die ;;
  esac
}

bucket_for() {
  case "$1" in
    sysroot)                                       echo "base" ;;
    bash|rg|busybox|jq|curl)                       echo "base" ;;
    node|python3)                                  echo "rt" ;;
    git|sqlite3|npm|pnpm|llvmtoolchain)            echo "tool" ;;
    make|cmake|pkg-config)                         echo "tool" ;;
    *) die ;;
  esac
}

die() {
  echo "::error title=未知件名::$TOOL 不在已知列表里 —— 加新件时要在这里补 deps_for 与 bucket_for"
  exit 1
}

DEPS="$(deps_for "$TOOL")"
VER=""
for k in $DEPS; do
  v="$(bash "$ROOT_DIR/scripts/toolchain/fetch-pinned.sh" --src-version "$k" 2>/dev/null || true)"
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
    v="$(bash "$ROOT_DIR/scripts/toolchain/fetch-pinned.sh" --src-version "$k" 2>/dev/null || true)"
    P="$P$(echo "$k" | head -c6)-${v:0:8}; "
  done
  VER="$VER|patch:$P"
fi

if [ "$TOOL" = "node" ]; then
  NVER="$(bash "$ROOT_DIR/scripts/registry/read-node-versions.sh" default)"
  NABI="$(bash "$ROOT_DIR/scripts/registry/read-node-versions.sh" abi | tr -c 'A-Za-z0-9._-' '+')"
  VER="${NVER}+${NABI}"
fi

NDK="$(bash "$ROOT_DIR/scripts/toolchain/fetch-pinned.sh" --ndk 2>/dev/null || echo unknown)"
API=35
RUN_OS="${RUN_OS:-Linux}"
RUN_ARCH="${RUN_ARCH:-X64}"

if [ "$DEPS" = "build-apk-only" ]; then
  echo "::error title=该件不在这条链上::$TOOL 由 build-apk.yml 编（APK 内置 + OTA 补丁），"
  echo "::error::不在 build-component 矩阵里 —— 要给它算预制品 key 得先在 build-apk 那边接上。"
  exit 1
fi

DEPS_STR="$VER-ndk$NDK-api$API"
BUCKET="$(bucket_for "$TOOL")"

case "$KIND" in
  tag)
    echo "$BUCKET-$TOOL"
    ;;
  asset)
    SAFE="$(printf '%s' "$DEPS_STR" | tr -c 'A-Za-z0-9._' '+' | tr -s '+' '+')"
    echo "$TOOL-$SAFE.tar.gz"
    ;;
  *)
    echo "uw-$TOOL-$DEPS_STR-$RUN_OS-$RUN_ARCH"
    ;;
esac