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

ID="${1:-zlib-musl}"
PREFIX_ID="${LOBOS_PREFIX_ID:-zlib}"
VER=""
WORK="$ROOT_DIR/work/musl-$ID"
STAGE="$WORK/stage"
OUT="$ROOT_DIR/work"

CC="$ROOT_DIR/scripts/toolchain/musl-cc.sh"
NDK="${LOBOS_NDK:-}"
[ -n "$NDK" ] || die "没给 NDK" "用 LOBOS_NDK 指定（本脚本要用它的 llvm-ar 与 llvm-readelf）"

rm -rf "$WORK"
mkdir -p "$WORK/src" "$STAGE/lib"

bash "$ROOT_DIR/scripts/toolchain/fetch-pinned.sh" --pin "$PREFIX_ID" "$WORK/src.tar.gz" \
  || die "源码取不到" "钉值见 scripts/component-sources.json 的 sources.$PREFIX_ID"
VER="$(bash "$ROOT_DIR/scripts/toolchain/fetch-pinned.sh" --src-version "$PREFIX_ID" 2>/dev/null || true)"
[ -n "$VER" ] || die "读不到版本" "sources.$PREFIX_ID.version"

tar xzf "$WORK/src.tar.gz" -C "$WORK/src" --strip-components=1 || die "源码解包失败" "$WORK/src.tar.gz"

(
  cd "$WORK/src"
  CC="$CC" AR="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar" \
    CFLAGS="-O2 -fPIC" ./configure --static >/dev/null 2>&1 || die "configure 失败" "$WORK/src"
  make -j"$(nproc)" libz.a >/dev/null 2>&1 || die "静态库编不出来" "$WORK/src"
  LOBOS_SONAME="libz.so.1" \
    make LDSHARED="$CC -shared" SHAREDLIB=libz.so SHAREDLIBV="libz.so.$VER" SHAREDLIBM=libz.so.1 \
    "libz.so.$VER" >/dev/null 2>&1 || die "共享库编不出来" "$WORK/src"
)

cp "$WORK/src/libz.so.$VER" "$STAGE/lib/"
( cd "$STAGE/lib" && ln -sf "libz.so.$VER" libz.so.1 && ln -sf "libz.so.$VER" libz.so )

LLVM_READELF="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" \
  bash -c "source '$ROOT_DIR/scripts/toolchain/elf-form.sh'; elf_write_shape '$ID' '' '$STAGE' '$STAGE/piece-shape.json' '$VER' lib" \
  >/dev/null 2>&1 || die "shape 生成失败" "$STAGE"

node "$ROOT_DIR/scripts/recipes/gen-component-meta.js" "$ID" "$VER" "$STAGE/component-meta.json" --payload "$STAGE" >/dev/null 2>&1 \
  || die "meta 生成失败" "$STAGE"

PKG="$OUT/component-$ID-$VER.zip"
rm -f "$PKG"
( cd "$STAGE" && zip -q -r -y "$PKG" lib piece-shape.json component-meta.json ) || die "打包失败" "$PKG"

echo "$PKG"