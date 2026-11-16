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

ID="${1:-}"
SUFFIX="${2:-musl}"
[ -n "$ID" ] || die "用法" "$0 <件名> [后缀=musl]；件名见 scripts/component-sources.json 的 sources"

NDK="${LOBOS_NDK:-${ANDROID_NDK_LATEST_HOME:-}}"
[ -n "$NDK" ] || die "没给 NDK" "用 LOBOS_NDK 指定 NDK 根目录"
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
[ -x "$TC/clang" ] || die "NDK 里没有 clang" "$TC/clang"

SYSROOT="${LOBOS_MUSL_SYSROOT:-$ROOT_DIR/work/musl-sysroot}"
[ -f "$SYSROOT/usr/lib/crt1.o" ] || die "musl sysroot 没就位" "先跑 scripts/toolchain/musl-sysroot.sh"

CC="$ROOT_DIR/scripts/toolchain/musl-cc.sh"
AR="$TC/llvm-ar"
STRIP="$TC/llvm-strip"
READELF="$TC/llvm-readelf"

W="$ROOT_DIR/work/musl-$ID"
SRC="$W/src"
STAGE="$W/stage"
OUT="$W/out"
OUTPKG="$ROOT_DIR/work"

mkdir -p "$W"

ver_of() {
  bash "$ROOT_DIR/scripts/toolchain/fetch-pinned.sh" --src-version "$1" 2>/dev/null || true
}

need_prefix() {
  local dep="$1"
  local p="$ROOT_DIR/work/musl-$dep/out"
  if [ ! -d "$p/lib" ]; then
    echo "[$ID] 先建依赖 $dep"
    bash "$HERE/build-musl-piece.sh" "$dep" "$SUFFIX" >/dev/null
  fi
}

fetch_src() {
  local key="$1"
  rm -rf "$SRC"; mkdir -p "$SRC"
  local t="$W/src.tar"
  bash "$ROOT_DIR/scripts/toolchain/fetch-pinned.sh" --pin "$key" "$t" >/dev/null \
    || die "源码取不到" "钉值见 scripts/component-sources.json 的 sources.$key"
  case "$t" in
    *) unzip -q -o "$t" -d "$SRC" >/dev/null 2>&1 && {
         local inner; inner="$(find "$SRC" -maxdepth 1 -mindepth 1 -type d | head -1)"
         if [ -n "$inner" ] && [ "$(find "$SRC" -maxdepth 1 -mindepth 1 | wc -l)" = "1" ]; then
           ( cd "$SRC" && shopt -s dotglob && mv "$inner"/* . 2>/dev/null; rm -rf "$inner" )
         fi
         rm -f "$t"; return 0
       } 2>/dev/null || true
       tar xf "$t" -C "$SRC" --strip-components=1 2>/dev/null || {
         rm -rf "$SRC"; mkdir -p "$SRC"
         tar xjf "$t" -C "$SRC" --strip-components=1 2>/dev/null || {
           rm -rf "$SRC"; mkdir -p "$SRC"
           tar xJf "$t" -C "$SRC" --strip-components=1
         }
       } ;;
  esac
  rm -f "$t"
  [ -n "$(ls -A "$SRC" 2>/dev/null)" ] || die "源码解包后为空" "$SRC"
}

build_zlib() {
  local ver; ver="$(ver_of zlib)"
  fetch_src zlib
  rm -rf "$OUT"; mkdir -p "$OUT/lib" "$OUT/include"
  (
    cd "$SRC"
    CC="$CC" CFLAGS="-O2 -fPIC" ./configure --prefix="$OUT" >/dev/null 2>&1
    make -j"$(nproc)" libz.a >/dev/null 2>&1
    LOBOS_SONAME="libz.so.1" make LDSHARED="$CC -shared" \
      SHAREDLIB=libz.so SHAREDLIBV="libz.so.$ver" SHAREDLIBM=libz.so.1 \
      "libz.so.$ver" >/dev/null 2>&1
  ) || die "zlib 编不出来" "$SRC"
  cp "$SRC/libz.so.$ver" "$OUT/lib/"
  ( cd "$OUT/lib" && ln -sf "libz.so.$ver" libz.so.1 && ln -sf "libz.so.$ver" libz.so )
  cp "$SRC"/*.h "$OUT/include/" 2>/dev/null || true
  mkdir -p "$STAGE/lib"
  cp -P "$OUT/lib/libz.so"* "$STAGE/lib/"
  SCOPE="lib"
}

build_openssl() {
  local ver; ver="$(ver_of openssl)"
  fetch_src openssl
  rm -rf "$OUT"; mkdir -p "$OUT"
  (
    cd "$SRC"
    CC="$CC" AR="$AR" ./Configure linux-aarch64 shared no-tests no-ui-console \
      --prefix="$OUT" --openssldir="$OUT/ssl" >/dev/null 2>&1
    make -j"$(nproc)" >/dev/null 2>&1
    make install_sw >/dev/null 2>&1
  ) || die "openssl 编不出来" "$SRC"
  [ -f "$OUT/lib/libcrypto.so.3" ] || die "openssl 没产出 libcrypto" "$OUT/lib"
  mkdir -p "$STAGE/lib" "$STAGE/bin"
  cp -P "$OUT/lib/libcrypto.so"* "$OUT/lib/libssl.so"* "$STAGE/lib/"
  cp "$OUT/bin/openssl" "$STAGE/bin/"
  SCOPE="lib bin"
}

build_crypto() {
  need_prefix openssl
  local ver; ver="$(ver_of crypto)"
  [ -n "$ver" ] || ver="$(ver_of openssl)"
  rm -rf "$OUT"; mkdir -p "$OUT/lib" "$OUT/include"
  cp -P "$ROOT_DIR/work/musl-openssl/out/lib/libcrypto.so"* "$OUT/lib/"
  cp -a "$ROOT_DIR/work/musl-openssl/out/include/openssl" "$OUT/include/" 2>/dev/null || true
  mkdir -p "$STAGE/lib"
  cp -P "$OUT/lib/libcrypto.so"* "$STAGE/lib/"
  SCOPE="lib"
}

build_curl() {
  need_prefix zlib
  need_prefix openssl
  local ver; ver="$(ver_of curl)"
  fetch_src curl
  rm -rf "$OUT"; mkdir -p "$OUT"
  local Z="$ROOT_DIR/work/musl-zlib/out" O="$ROOT_DIR/work/musl-openssl/out"
  (
    cd "$SRC"
    CC="$CC" CFLAGS="-O2 -fPIC" CPPFLAGS="-I$O/include -I$Z/include" \
      LDFLAGS="-L$O/lib -L$Z/lib" \
      ./configure --host=aarch64-linux-musl --prefix="$OUT" --enable-shared --disable-static \
        --with-ssl="$O" --with-zlib="$Z" --disable-ldap --disable-ldaps \
        --without-libpsl --without-brotli --without-zstd --disable-ftp --disable-rtsp \
        --without-libidn2 >/dev/null 2>&1
    make -j"$(nproc)" >/dev/null 2>&1
    make install >/dev/null 2>&1
  ) || die "curl 编不出来" "$SRC"
  mkdir -p "$STAGE/lib"
  cp -P "$OUT/lib/libcurl.so"* "$STAGE/lib/"
  SCOPE="lib"
}

build_bash() {
  local ver; ver="$(ver_of bash)"
  fetch_src bash
  rm -rf "$OUT"; mkdir -p "$OUT"
  (
    cd "$SRC"
    CC="$CC" ./configure --host=aarch64-linux-musl --build=x86_64-pc-linux-gnu \
      --prefix="$OUT" --disable-nls --without-bash-malloc \
      CFLAGS="-O2 -Wno-error=implicit-function-declaration -Wno-error=int-conversion -Wno-unused-result" \
      LDFLAGS="-Wl,--allow-multiple-definition" \
      bash_cv_getcwd_malloc=yes bash_cv_func_sigsetjmp=present \
      bash_cv_printf_a_format=yes bash_cv_dev_fd_standard=yes bash_cv_unusable_rtsigs=no \
      >/dev/null 2>&1
    make -j"$(nproc)" bash CFLAGS_FOR_BUILD="-std=gnu89 -O2" >/dev/null 2>&1
  ) || die "bash 编不出来" "$SRC"
  mkdir -p "$STAGE/bin"
  cp "$SRC/bash" "$STAGE/bin/bash"
  mkdir -p "$OUT/bin"; cp "$SRC/bash" "$OUT/bin/"
  SCOPE="bin"
}

build_busybox() {
  local ver; ver="$(ver_of busybox)"
  fetch_src busybox
  local XT="$W/xtool"
  rm -rf "$XT"; mkdir -p "$XT"
  ln -sf "$CC" "$XT/aarch64-linux-musl-gcc"
  ln -sf "$AR" "$XT/aarch64-linux-musl-ar"
  ln -sf "$TC/llvm-nm" "$XT/aarch64-linux-musl-nm"
  ln -sf "$STRIP" "$XT/aarch64-linux-musl-strip"
  ln -sf "$TC/llvm-objcopy" "$XT/aarch64-linux-musl-objcopy"
  ln -sf "$TC/llvm-ranlib" "$XT/aarch64-linux-musl-ranlib"
  local INS="$W/install"
  (
    cd "$SRC"
    make defconfig >/dev/null 2>&1
    for k in ASH_INTERNAL_GLOB TEST1 BUSYBOX; do
      grep -q "^CONFIG_${k}=y" .config || sed -i "s/^# CONFIG_${k} is not set/CONFIG_${k}=y/" .config
    done
    sed -i 's/^CONFIG_TC=y/# CONFIG_TC is not set/' .config || true
    make clean >/dev/null 2>&1 || true
    rm -f busybox busybox_unstripped
    PATH="$XT:$PATH" make -j"$(nproc)" CROSS_COMPILE=aarch64-linux-musl- \
      LDFLAGS="-Wl,--allow-multiple-definition" HOSTCC=gcc HOSTCFLAGS="-O2" >/dev/null 2>&1
    rm -rf "$INS"
    PATH="$XT:$PATH" make CROSS_COMPILE=aarch64-linux-musl- \
      LDFLAGS="-Wl,--allow-multiple-definition" \
      CONFIG_PREFIX="$INS" install >/dev/null 2>&1
  ) || die "busybox 编不出来" "$SRC"
  "$READELF" -h "$SRC/busybox" 2>/dev/null | grep -qi 'AArch64' \
    || die "busybox 编成了别的架构" "产物不是 AArch64（说明构建没用上我们的工具链）：$SRC/busybox"
  mkdir -p "$STAGE/bin"
  cp "$SRC/busybox" "$STAGE/bin/libbusybox.so"
  chmod 0755 "$STAGE/bin/libbusybox.so"
  ln -sf libbusybox.so "$STAGE/bin/busybox"
  local n=0 l b
  for l in "$INS/bin/"* "$INS/sbin/"* "$INS/usr/bin/"*; do
    [ -L "$l" ] || continue
    b="$(basename "$l")"
    [ "$b" = busybox ] && continue
    ln -sf ./libbusybox.so "$STAGE/bin/$b"
    n=$((n+1))
  done
  SCOPE="bin"
}

build_make() {
  local ver; ver="$(ver_of make)"
  fetch_src make
  rm -rf "$OUT"; mkdir -p "$OUT"
  (
    cd "$SRC"
    CC="$CC" AR="$AR" RANLIB="$TC/llvm-ranlib" ./configure --host=aarch64-linux-musl       --build=x86_64-pc-linux-gnu --prefix="$OUT" --disable-nls >/dev/null 2>&1
    make -j"$(nproc)" >/dev/null 2>&1
    make install >/dev/null 2>&1
  ) || die "make 编不出来" "$SRC"
  [ -x "$OUT/bin/make" ] || die "make 没产出可执行文件" "$OUT/bin"
  mkdir -p "$STAGE/bin"
  cp "$OUT/bin/make" "$STAGE/bin/make"
  SCOPE="bin"
}

build_pkgconf() {
  local ver; ver="$(ver_of pkgconf)"
  fetch_src pkgconf
  rm -rf "$OUT"; mkdir -p "$OUT"
  (
    cd "$SRC"
    CC="$CC" ./configure --host=aarch64-linux-musl --build=x86_64-pc-linux-gnu \
      --prefix="$OUT" >/dev/null 2>&1
    make -j"$(nproc)" >/dev/null 2>&1
    make install >/dev/null 2>&1
  ) || die "pkgconf 编不出来" "$SRC"
  local bin="$OUT/bin/pkgconf"
  [ -x "$bin" ] || bin="$OUT/bin/pkg-config"
  [ -x "$bin" ] || die "pkgconf 没产出可执行文件" "$OUT/bin"
  mkdir -p "$STAGE/bin" "$STAGE/lib"
  cp "$bin" "$STAGE/bin/pkg-config"
  cp -P "$OUT/lib/libpkgconf.so"* "$STAGE/lib/" 2>/dev/null || true
  SCOPE="lib bin"
}

build_sqlite() {
  local ver; ver="$(ver_of sqlite)"
  fetch_src sqlite
  local sd
  sd="$(find "$SRC" -maxdepth 1 -type d -name 'sqlite-amalgamation-*' | head -1)"
  [ -n "$sd" ] || sd="$SRC"
  [ -f "$sd/sqlite3.c" ] || die "sqlite 源码里没有 sqlite3.c" "$sd"
  rm -rf "$OUT"; mkdir -p "$OUT/bin"
  CC="$CC" "$CC" -O2 -DNDEBUG -DSQLITE_THREADSAFE=1 -DSQLITE_ENABLE_FTS5 \
    -DSQLITE_ENABLE_JSON1 -o "$OUT/bin/sqlite3" "$sd/shell.c" "$sd/sqlite3.c" -lm -ldl \
    || die "sqlite 编不出来" "$sd"
  mkdir -p "$STAGE/bin"
  cp "$OUT/bin/sqlite3" "$STAGE/bin/sqlite3"
  SCOPE="bin"
}

build_jq() {
  local ver; ver="$(ver_of jq)"
  fetch_src jq
  rm -rf "$OUT"; mkdir -p "$OUT"
  (
    cd "$SRC"
    CC="$CC" ./configure --host=aarch64-linux-musl --build=x86_64-pc-linux-gnu \
      --prefix="$OUT" --disable-maintainer-mode --with-oniguruma=builtin \
      >/dev/null 2>&1
    make -j"$(nproc)" LDFLAGS="-static" >/dev/null 2>&1
  ) || die "jq 编不出来" "$SRC"
  [ -f "$SRC/jq" ] || die "jq 没产出可执行文件" "$SRC"
  mkdir -p "$STAGE/bin"
  cp "$SRC/jq" "$STAGE/bin/jq"
  SCOPE="bin"
}

case "$ID" in
  zlib)    build_zlib ;;
  openssl) build_openssl ;;
  crypto)  build_crypto ;;
  curl)    build_curl ;;
  bash)    build_bash ;;
  busybox) build_busybox ;;
  jq)      build_jq ;;
  make)    build_make ;;
  pkgconf) build_pkgconf ;;
  sqlite)  build_sqlite ;;
  *) die "这一件还没有 musl 构建分支" "现有：zlib openssl crypto curl bash busybox jq make pkgconf sqlite（在 $HERE/build-musl-piece.sh 里加分支）" ;;
esac

VER="$(ver_of "$ID")"
[ -n "$VER" ] || VER="$(ver_of openssl)"

PIECE_ID="$ID$([ "$SUFFIX" = "none" ] && echo "" || echo "-$SUFFIX")"
ENTRY=""
[ -f "$STAGE/bin/$ID" ] && ENTRY="bin/$ID"
[ -z "$ENTRY" ] && [ -f "$STAGE/bin/libbusybox.so" ] && ENTRY="bin/libbusybox.so"

node "$ROOT_DIR/scripts/recipes/gen-component-meta.js" "$PIECE_ID" "$VER" "$STAGE/component-meta.json" --payload "$STAGE" >/dev/null 2>&1 \
  || die "meta 生成失败" "$STAGE"

ENTRY="$(python3 -c "import json,sys;print(json.load(open(sys.argv[1])).get('entry',''))" "$STAGE/component-meta.json" 2>/dev/null || true)"

LLVM_READELF="$READELF" bash -c \
  "source '$ROOT_DIR/scripts/toolchain/elf-form.sh'; cd '$STAGE'; elf_write_shape '$PIECE_ID' '$ENTRY' '$STAGE' '$STAGE/piece-shape.json' '$VER' $SCOPE" \
  >/dev/null 2>&1 || die "shape 生成失败" "$STAGE"

PKG="$OUTPKG/component-$PIECE_ID-$VER.zip"
rm -f "$PKG"
( cd "$STAGE" && zip -q -r -y "$PKG" $SCOPE piece-shape.json component-meta.json ) || die "打包失败" "$PKG"
echo "$PKG"