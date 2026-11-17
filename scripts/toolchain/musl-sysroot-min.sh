#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C
HERE="$(dirname "$(readlink -f "${BASH_SOURCE[0]:-$0}")")"
ROOT_DIR="$(cd "$HERE/../.." && pwd)"

die() {
  local title="$1"; shift
  echo "::error title=$title::$(printf '%s\n' "$@")"
  exit 1
}

SYSROOT="${1:-$ROOT_DIR/work/musl-sysroot-min}"
DIST="$ROOT_DIR/work/musl-dist-min"
mkdir -p "$DIST" "$SYSROOT/usr" "$SYSROOT/lib"

bash "$ROOT_DIR/scripts/toolchain/fetch-pinned.sh" --pin musl "$DIST/musl.apk" \
  || die "musl 取不到" "钉值见 sources.musl"
bash "$ROOT_DIR/scripts/toolchain/fetch-pinned.sh" --pin musl-dev "$DIST/musl-dev.apk" \
  || die "musl-dev 取不到" "钉值见 sources.musl-dev"
bash "$ROOT_DIR/scripts/toolchain/fetch-pinned.sh" --pin linux-headers "$DIST/linux-headers.apk" \
  || die "linux-headers 取不到" "钉值见 sources.linux-headers"

rm -rf "$DIST/musl" "$DIST/musl-dev" "$DIST/linux-headers" && mkdir -p "$DIST/musl" "$DIST/musl-dev" "$DIST/linux-headers"
tar xzf "$DIST/musl.apk" --warning=no-unknown-keyword -C "$DIST/musl" 2>/dev/null || die "musl 解包失败"
tar xzf "$DIST/musl-dev.apk" --warning=no-unknown-keyword -C "$DIST/musl-dev" 2>/dev/null || die "musl-dev 解包失败"
tar xzf "$DIST/linux-headers.apk" --warning=no-unknown-keyword -C "$DIST/linux-headers" 2>/dev/null || die "linux-headers 解包失败"

rm -rf "$SYSROOT/usr/include" "$SYSROOT/usr/lib"
cp -a "$DIST/musl-dev/usr/include" "$SYSROOT/usr/"
cp -a "$DIST/musl-dev/usr/lib" "$SYSROOT/usr/"
for d in "$DIST"/linux-headers/usr/include/*; do
  [ -d "$d" ] || continue
  name="$(basename "$d")"
  [ -e "$SYSROOT/usr/include/$name" ] && continue
  cp -a "$d" "$SYSROOT/usr/include/"
done
cp -a "$DIST/musl/lib/ld-musl-aarch64.so.1" "$SYSROOT/lib/"

[ -f "$SYSROOT/usr/lib/crt1.o" ] || die "sysroot 缺 crt1.o"
[ -f "$SYSROOT/usr/include/stdio.h" ] || die "sysroot 缺 stdio.h"
echo "$SYSROOT"
