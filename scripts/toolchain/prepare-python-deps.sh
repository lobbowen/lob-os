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

PREFIX="${1:-$ROOT_DIR/work/python-deps}"
PKGS_DIR="$ROOT_DIR/work/python-deps-pkgs"
DOWN="https://packages.termux.dev/apt/termux-main"

mkdir -p "$PREFIX" "$PKGS_DIR"

declare -A DEB=(
  [libffi]="pool/main/libf/libffi/libffi_3.8.1_aarch64.deb"
  [openssl]="pool/main/o/openssl/openssl_1%3A3.6.5_aarch64.deb"
  [libsqlite]="pool/main/libs/libsqlite/libsqlite_3.54.1-1_aarch64.deb"
  [libbz2]="pool/main/libb/libbz2/libbz2_1.0.8-8_aarch64.deb"
  [liblzma]="pool/main/libl/liblzma/liblzma_5.8.4_aarch64.deb"
  [zstd]="pool/main/z/zstd/zstd_1.5.7-1_aarch64.deb"
)

for name in "${!DEB[@]}"; do
  deb="$PKGS_DIR/$name.deb"
  if [ ! -s "$deb" ]; then
    curl -fsSL --retry 3 --max-time 300 "$DOWN/${DEB[$name]}" -o "$deb" \
      || die "下载 $name 失败" "${DEB[$name]}"
  fi
  x="$PKGS_DIR/x-$name"
  rm -rf "$x" && mkdir -p "$x"
  (cd "$x" && ar x "$deb" && tar xf data.tar.* 2>/dev/null) || die "$name 解包失败"
  src="$x/data/data/com.termux/files/usr"
  cp -a "$src/include/." "$PREFIX/include/" 2>/dev/null || true
  cp -a "$src/lib/." "$PREFIX/lib/" 2>/dev/null || true
done

mkdir -p "$PREFIX/lib/pkgconfig"
for pc in "$PREFIX"/lib/pkgconfig/*.pc; do
  [ -f "$pc" ] || continue
  sed -i "s|/data/data/com.termux/files/usr|$PREFIX|g" "$pc"
done

for lib in ffi ssl crypto sqlite3 bz2 lzma zstd; do
  found=0
  for f in "$PREFIX"/lib/lib${lib}.so* "$PREFIX"/lib/lib${lib}.a; do
    [ -e "$f" ] && found=1 && break
  done
  [ "$found" = 1 ] || echo "[py-deps] 警告：没找到 lib$lib"
done
[ -f "$PREFIX/lib/pkgconfig/libffi.pc" ] || die "libffi.pc 没就位" "$PREFIX/lib/pkgconfig"

echo "[py-deps] 就位 $PREFIX"
