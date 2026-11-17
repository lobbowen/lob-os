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

DST="${1:-$ROOT_DIR/container/app/src/main/assets/musl}"
DIST="$ROOT_DIR/work/musl-dist-runtime"

bash "$ROOT_DIR/scripts/toolchain/fetch-pinned.sh" --pin musl "$DIST-musl.apk" \
  || die "musl 取不到" "钉值见 scripts/component-sources.json 的 sources.musl"

rm -rf "$DIST" "$DST" && mkdir -p "$DIST"
tar xzf "$DIST-musl.apk" --warning=no-unknown-keyword -C "$DIST" 2>/dev/null \
  || die "musl 解包失败" "$DIST-musl.apk"

[ -f "$DIST/lib/ld-musl-aarch64.so.1" ] || die "musl 包里没有加载器" "$DIST/lib"

mkdir -p "$DST"
cp -a "$DIST/lib/ld-musl-aarch64.so.1" "$DST/ld-musl-aarch64.so.1"
cp -a "$DIST/lib/ld-musl-aarch64.so.1" "$DST/libc.musl-aarch64.so.1"

[ -s "$DST/ld-musl-aarch64.so.1" ] || die "加载器落位失败" "$DST"
echo "[musl-runtime] 就位 $DST（加载器 + libc，$(stat -c%s "$DST/ld-musl-aarch64.so.1") 字节）"
