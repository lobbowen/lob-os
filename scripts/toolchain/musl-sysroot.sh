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

SYSROOT="${1:-$ROOT_DIR/work/musl-sysroot}"
DIST="$ROOT_DIR/work/musl-dist"
mkdir -p "$DIST" "$SYSROOT/usr" "$SYSROOT/lib"

bash "$ROOT_DIR/scripts/toolchain/fetch-pinned.sh" --pin musl "$DIST/musl.apk" \
  || die "musl 取不到" "钉值见 scripts/component-sources.json 的 sources.musl"
bash "$ROOT_DIR/scripts/toolchain/fetch-pinned.sh" --pin musl-dev "$DIST/musl-dev.apk" \
  || die "musl-dev 取不到" "钉值见 scripts/component-sources.json 的 sources.musl-dev"
bash "$ROOT_DIR/scripts/toolchain/fetch-pinned.sh" --pin linux-headers "$DIST/linux-headers.apk" \
  || die "linux-headers 取不到" "钉值见 scripts/component-sources.json 的 sources.linux-headers"

rm -rf "$DIST/musl" "$DIST/musl-dev" "$DIST/linux-headers"
mkdir -p "$DIST/musl" "$DIST/musl-dev" "$DIST/linux-headers"
tar xzf "$DIST/musl.apk" --warning=no-unknown-keyword -C "$DIST/musl" 2>/dev/null || die "musl 解包失败" "$DIST/musl.apk"
tar xzf "$DIST/musl-dev.apk" --warning=no-unknown-keyword -C "$DIST/musl-dev" 2>/dev/null
tar xzf "$DIST/linux-headers.apk" --warning=no-unknown-keyword -C "$DIST/linux-headers" 2>/dev/null || die "musl-dev 解包失败" "$DIST/musl-dev.apk"

[ -d "$DIST/musl-dev/usr/include" ] || die "musl-dev 里没有 include" "$DIST/musl-dev"
[ -d "$DIST/musl-dev/usr/lib" ] || die "musl-dev 里没有 lib" "$DIST/musl-dev"
[ -f "$DIST/musl/lib/ld-musl-aarch64.so.1" ] || die "musl 里没有加载器" "$DIST/musl/lib"

rm -rf "$SYSROOT/usr/include" "$SYSROOT/usr/lib"
cp -a "$DIST/musl-dev/usr/include" "$SYSROOT/usr/"
cp -a "$DIST/musl-dev/usr/lib" "$SYSROOT/usr/"
for src_dir in "$DIST"/linux-headers/usr/include/*; do
  [ -d "$src_dir" ] || continue
  name="$(basename "$src_dir")"
  [ -e "$SYSROOT/usr/include/$name" ] && continue
  cp -a "$src_dir" "$SYSROOT/usr/include/"
done
cp -a "$DIST/musl/lib/ld-musl-aarch64.so.1" "$SYSROOT/lib/"

[ -f "$SYSROOT/usr/include/linux/mman.h" ] || die "sysroot 缺内核头" "$SYSROOT/usr/include/linux"
for f in crt1.o crti.o crtn.o libc.a; do
  [ -f "$SYSROOT/usr/lib/$f" ] || die "sysroot 不完整：缺 $f" "$SYSROOT/usr/lib"
done

echo "$SYSROOT"