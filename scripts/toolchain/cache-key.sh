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
    asset)      KIND="asset" ;;
    deps-list)  KIND="deps-list" ;;
    deps)       KIND="deps" ;;
    *)
      if [ -n "$TOOL" ]; then
        echo "只能给一个件名（收到 $a 与 $TOOL）" >&2
        exit 2
      fi
      TOOL="$a"
      ;;
  esac
done
[ -n "$TOOL" ] || {
  echo "用法: $0 <子命令> <件名>" >&2
  echo "  子命令: key · tag · deps · deps-list · asset" >&2
  exit 2
}

deps_for() {
      case "$1" in

      curl)       echo "openssl zlib" ;;
      openssl)    echo "zlib" ;;
      crypto)     echo "openssl" ;;
      git)        echo "curl openssl zlib" ;;
      python)     echo "openssl zlib" ;;
      node)       echo "" ;;

      bash|ripgrep|busybox|jq|sqlite|npm|pnpm|llvm|make|cmake|pkgconf) echo "" ;;
      zlib)      echo "" ;;

      libcxx|sysroot)                                               echo "" ;;
      *) die ;;
      esac
    }

bucket_for() {
      case "$1" in

      bash|ripgrep|busybox|jq)                             echo "base" ;;
      curl|zlib|openssl|crypto)                            echo "base" ;;

      node|python)echo "rt" ;;

      git|sqlite|npm|pnpm|llvm|make|cmake|pkgconf)        echo "tool" ;;

      libcxx|sysroot)                                   echo "apkonly" ;;
      *) die ;;
      esac
    }

KNOWN_TOOLS="$(bash "$ROOT_DIR/scripts/toolchain/known-tools.sh")"

die() {

  echo "::error title=未知件名::$TOOL 不在已知列表里 —— 加新件时要在这里补 deps_for 与 bucket_for" >&2
  echo "         本脚本认的件名（注意是 python 不是 python3、是 ripgrep 不是 rg）：" >&2
  echo "         $KNOWN_TOOLS" >&2
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
  for k in $(bash "$ROOT_DIR/scripts/toolchain/fetch-pinned.sh" --list-keys '^git-.*\.patch$'); do
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

SRC_HASH=""
case "$TOOL" in
  busybox) SRC_FILES="scripts/recipes/build-native-busybox.sh" ;;
  bash)    SRC_FILES="scripts/recipes/build-bundle-bash.sh" ;;
  ripgrep) SRC_FILES="scripts/recipes/build-bundle-rg.sh" ;;
  jq)      SRC_FILES="scripts/recipes/build-bundle-jq.sh" ;;
  curl)    SRC_FILES="scripts/recipes/build-bundle-curl.sh" ;;
  zlib)    SRC_FILES="scripts/recipes/build-bundle-zlib.sh" ;;

  openssl) SRC_FILES="scripts/recipes/build-bundle-openssl.sh scripts/patches/openssl-runpath-origins.patch scripts/patches/openssl-libname-variant.patch" ;;
  crypto)  SRC_FILES="scripts/recipes/build-bundle-openssl.sh scripts/patches/openssl-runpath-origins.patch scripts/patches/openssl-libname-variant.patch" ;;
  git)     SRC_FILES="scripts/recipes/build-component-git.sh scripts/patches/git-runpath-origins.patch" ;;

  node)    SRC_FILES="" ;;
  python)  SRC_FILES="scripts/recipes/build-component-python3.sh" ;;
  sqlite)  SRC_FILES="scripts/recipes/build-component-sqlite3.sh" ;;

  pkgconf) SRC_FILES="scripts/recipes/build-component-pkg-config.sh" ;;
  make)   SRC_FILES="scripts/recipes/build-component-make.sh" ;;
  cmake)  SRC_FILES="scripts/recipes/build-component-cmake.sh" ;;
  npm)    SRC_FILES="scripts/recipes/build-component-npm.sh" ;;
  pnpm)   SRC_FILES="scripts/recipes/build-component-pnpm.sh" ;;
  llvm)   SRC_FILES="scripts/recipes/build-component-llvmtoolchain.sh" ;;
  *) SRC_FILES="" ;;
esac

PKG_MECH="scripts/recipes/gen-component-meta.js scripts/recipes/package-bundle-zip.sh scripts/toolchain/elf-form.sh"
SRC_FILES="$SRC_FILES $PKG_MECH"

STRIP="$ROOT_DIR/scripts/toolchain/comment-strip.py"
if [ -n "$SRC_FILES" ] && [ -d "$ROOT_DIR/.git" ]; then
  SRC_HASH="$(cd "$ROOT_DIR" && git ls-files $SRC_FILES 2>/dev/null | while read -r f; do git show "HEAD:$f" 2>/dev/null | python3 "$STRIP" "$f" 2>/dev/null | sha256sum; done | sha256sum | cut -c1-12)"
  [ -n "$SRC_HASH" ] || SRC_HASH="nosrc"
fi
[ -n "$SRC_HASH" ] || SRC_HASH="nosrc"

FORM_TAG=""
case "$TOOL" in
  busybox)

    if [ "${LOBOS_BUSYBOX_STATIC:-0}" = "1" ]; then
      FORM_TAG="-static"
    fi
    ;;
esac
DEPS_STR="$DEPS_STR-src$SRC_HASH$FORM_TAG"

BUCKET="$(bucket_for "$TOOL")"

case "$KIND" in
  tag)
    echo "$BUCKET-$TOOL"
    ;;
  deps-list)

    deps_for "$TOOL"
    ;;
  asset)
    SAFE="$(printf '%s' "$DEPS_STR" | tr -c 'A-Za-z0-9._' '+' | tr -s '+' '+')"

    echo "$TOOL-$SAFE.zip"
    ;;
  *)
    echo "uw-$TOOL-$DEPS_STR-$RUN_OS-$RUN_ARCH"
    ;;
esac