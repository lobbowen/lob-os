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
  local want
  want="$(python3 -c "import json,sys;print(json.load(open(sys.argv[1],encoding='utf-8'))['sources'][sys.argv[2]]['sha256'])" \
    "$ROOT_DIR/scripts/component-sources.json" "$key" 2>/dev/null || true)"
  local have=""
  [ -s "$t" ] && have="$(sha256sum "$t" | cut -d' ' -f1)"
  if [ -n "$want" ] && [ "$have" = "$want" ]; then
    echo "[$ID] 复用已校验的源码 $(basename "$t")"
  else
    bash "$ROOT_DIR/scripts/toolchain/fetch-pinned.sh" --pin "$key" "$t" >/dev/null \
      || die "源码取不到" "钉值见 scripts/component-sources.json 的 sources.$key"
  fi
  case "$t" in
    *) unzip -q -o "$t" -d "$SRC" >>"$W/build.log" 2>&1 && {
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
    CC="$CC" CFLAGS="-O2 -fPIC" ./configure --prefix="$OUT" >>"$W/build.log" 2>&1
    make -j"$(nproc)" libz.a >>"$W/build.log" 2>&1
    LOBOS_SONAME="libz.so.1" make LDSHARED="$CC -shared" \
      SHAREDLIB=libz.so SHAREDLIBV="libz.so.$ver" SHAREDLIBM=libz.so.1 \
      "libz.so.$ver" >>"$W/build.log" 2>&1
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
      --prefix="$OUT" --openssldir="$OUT/ssl" >>"$W/build.log" 2>&1
    make -j"$(nproc)" >>"$W/build.log" 2>&1
    make install_sw >>"$W/build.log" 2>&1
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
        --without-libidn2 >>"$W/build.log" 2>&1
    make -j"$(nproc)" >>"$W/build.log" 2>&1
    make install >>"$W/build.log" 2>&1
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
      >>"$W/build.log" 2>&1
    make -j"$(nproc)" bash CFLAGS_FOR_BUILD="-std=gnu89 -O2" >>"$W/build.log" 2>&1
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
    make defconfig >>"$W/build.log" 2>&1
    for k in ASH_INTERNAL_GLOB TEST1 BUSYBOX; do
      grep -q "^CONFIG_${k}=y" .config || sed -i "s/^# CONFIG_${k} is not set/CONFIG_${k}=y/" .config
    done
    sed -i 's/^CONFIG_TC=y/# CONFIG_TC is not set/' .config || true
    make clean >>"$W/build.log" 2>&1 || true
    rm -f busybox busybox_unstripped
    PATH="$XT:$PATH" make CROSS_COMPILE=aarch64-linux-musl- \
      LDFLAGS="-Wl,--allow-multiple-definition" HOSTCC=gcc HOSTCFLAGS="-O2" >"$W/build.log" 2>&1
    rm -rf "$INS"
    PATH="$XT:$PATH" make CROSS_COMPILE=aarch64-linux-musl- \
      LDFLAGS="-Wl,--allow-multiple-definition" \
      CONFIG_PREFIX="$INS" install >>"$W/build.log" 2>&1
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

build_node() {
  need_prefix zlib
  need_prefix openssl
  local ver; ver="$(ver_of node)"
  fetch_src node
  local Z="$ROOT_DIR/work/musl-zlib/out" O="$ROOT_DIR/work/musl-openssl/out"
  rm -rf "$OUT"; mkdir -p "$OUT"
  (
    cd "$SRC"
    CC="$CC" CXX="$ROOT_DIR/scripts/toolchain/musl-cxx.sh" AR="$AR" \
      CC_host=gcc CXX_host=g++ LINK_host=g++ \
      ./configure --dest-cpu=arm64 --dest-os=linux --prefix="$OUT" --cross-compiling \
        --shared-openssl --shared-openssl-includes="$O/include" --shared-openssl-libpath="$O/lib" \
        --shared-zlib --shared-zlib-includes="$Z/include" --shared-zlib-libpath="$Z/lib" \
        --without-npm --without-corepack --without-intl \
        > "$W/configure.log" 2>&1
    make -j"$(nproc)" CC_host=gcc CXX_host=g++ LINK_host=g++ > "$W/build.log" 2>&1
    make install CC_host=gcc CXX_host=g++ LINK_host=g++ > "$W/install.log" 2>&1
  ) || die "node 编不出来" "日志在 $W/configure.log 与 $W/build.log"
  [ -x "$OUT/bin/node" ] || die "node 没产出可执行文件" "$OUT/bin"
  mkdir -p "$STAGE/bin"
  cp "$OUT/bin/node" "$STAGE/bin/node"
  SCOPE="bin"
}

build_python() {
  need_prefix zlib
  need_prefix openssl
  local ver; ver="$(ver_of python)"
  fetch_src python
  local Z="$ROOT_DIR/work/musl-zlib/out" O="$ROOT_DIR/work/musl-openssl/out"
  local HOSTPY="$W/hostpy/bin/python3"
  if [ ! -x "$HOSTPY" ]; then
    local HP="$W/hostpy"
    rm -rf "$W/hostbuild" "$HP"
    mkdir -p "$W/hostbuild"
    (
      cd "$W/hostbuild"
      "$SRC/configure" --prefix="$HP" --without-ensurepip > "$W/host-configure.log" 2>&1
      make -j"$(nproc)" > "$W/host-build.log" 2>&1
      make install > "$W/host-install.log" 2>&1
    ) || die "宿主 python $ver 编不出来" "日志 $W/host-build.log"
    [ -x "$HOSTPY" ] || die "宿主 python 没产出" "$HP"
  fi
  rm -rf "$OUT"; mkdir -p "$OUT"
  (
    cd "$SRC"
    CC="$CC" CXX="$ROOT_DIR/scripts/toolchain/musl-cxx.sh" AR="$AR" RANLIB="$TC/llvm-ranlib" \
      ./configure --host=aarch64-linux-musl --build=x86_64-pc-linux-gnu \
        --prefix="$OUT" --with-build-python="$HOSTPY" --without-ensurepip \
        --with-openssl="$O" --with-zlib="$Z" \
        CPPFLAGS="-I$O/include -I$Z/include" LDFLAGS="-L$O/lib -L$Z/lib" \
        ac_cv_buggy_getaddrinfo=no ac_cv_file__dev_ptmx=no ac_cv_file__dev_ptc=no \
        ac_cv_func_getaddrinfo=yes \
        PKG_CONFIG_LIBDIR="$SYSROOT/usr/lib/pkgconfig:$SYSROOT/usr/share/pkgconfig" \
        PKG_CONFIG_SYSROOT_DIR="$SYSROOT" \
        > "$W/configure.log" 2>&1
    make -j"$(nproc)" > "$W/build.log" 2>&1
    make install > "$W/install.log" 2>&1
  ) || die "python 编不出来" "日志 $W/build.log 与 $W/configure.log"
  [ -x "$OUT/bin/python3" ] || die "python 没产出可执行文件" "$OUT/bin"
  mkdir -p "$STAGE/bin" "$STAGE/lib"
  cp "$OUT/bin/python$ver" "$STAGE/bin/python3" 2>/dev/null || cp "$OUT/bin/python3" "$STAGE/bin/python3"
  PYLIB="$(ls -d "$OUT"/lib/python3.*/ 2>/dev/null | head -1)"
  [ -n "$PYLIB" ] || die "python 没装出标准库" "$OUT/lib"
  cp -a "${PYLIB%/}" "$STAGE/lib/"
  [ -d "$STAGE/lib/$(basename "${PYLIB%/}")/lib-dynload" ] || die "python 标准库缺 lib-dynload" "$STAGE/lib"
  SCOPE="bin lib"
}

build_cmake() {
  local ver; ver="$(ver_of cmake)"
  fetch_src cmake
  local TC_F="$W/musl-toolchain.cmake"
  cat > "$TC_F" <<CMK
set(CMAKE_SYSTEM_NAME Linux)
set(CMAKE_SYSTEM_PROCESSOR aarch64)
set(CMAKE_C_COMPILER "$CC")
set(CMAKE_CXX_COMPILER "$ROOT_DIR/scripts/toolchain/musl-cxx.sh")
set(CMAKE_SYSROOT "$SYSROOT")
set(CMAKE_FIND_ROOT_PATH "$SYSROOT")
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY BOTH)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE BOTH)
set(CMAKE_TRY_COMPILE_TARGET_TYPE STATIC_LIBRARY)
CMK
  rm -rf "$OUT" "$W/build"; mkdir -p "$OUT" "$W/build"
  (
    cd "$W/build"
    cmake -S "$SRC" -B "$W/build" -DCMAKE_TOOLCHAIN_FILE="$TC_F" \
      -DCMAKE_INSTALL_PREFIX="$OUT" -DCMAKE_BUILD_TYPE=Release -DBUILD_TESTING=OFF \
      -DOPENSSL_ROOT_DIR="$ROOT_DIR/work/musl-openssl/out" \
      -DZLIB_ROOT="$ROOT_DIR/work/musl-zlib/out" \
      > "$W/configure.log" 2>&1
    cmake --build "$W/build" -j"$(nproc)" --target cmake > "$W/build.log" 2>&1
    cmake --build "$W/build" --target install > "$W/install.log" 2>&1
  ) || die "cmake 编不出来" "日志在 $W/configure.log 与 $W/build.log"
  [ -x "$OUT/bin/cmake" ] || die "cmake 没产出可执行文件" "$OUT/bin"
  mkdir -p "$STAGE/bin"
  cp "$OUT/bin/cmake" "$STAGE/bin/cmake" 2>/dev/null || true
  [ -x "$STAGE/bin/cmake" ] || find "$OUT" -maxdepth 3 -name cmake -type f -exec cp {} "$STAGE/bin/cmake" \; 2>/dev/null || true
  [ -x "$STAGE/bin/cmake" ] || die "cmake 落位失败" "$OUT"
  SCOPE="bin"
}

build_git() {
  need_prefix zlib
  need_prefix openssl
  need_prefix curl
  local ver; ver="$(ver_of git)"
  fetch_src git
  local Z="$ROOT_DIR/work/musl-zlib/out" O="$ROOT_DIR/work/musl-openssl/out" C="$ROOT_DIR/work/musl-curl/out"
  rm -rf "$OUT"; mkdir -p "$OUT"
  (
    cd "$SRC"
    local MK=(
      "CC=$CC" "AR=$AR" "RANLIB=$TC/llvm-ranlib" "PTHREAD_LIBS=" "NO_RUST=1"
      "CURLDIR=$C" "OPENSSLDIR=$O" "ZLIB_PATH=$Z"
      "uname_S=Linux" "uname_M=aarch64"
      "prefix=$OUT" "gitexecdir=$OUT/libexec/git-core" "template_dir=$OUT/share/git-core/templates"
      "NO_GETTEXT=1" "NO_ICONV=1" "NO_TCLTK=1" "NO_PYTHON=1" "NO_PERL=1" "NO_REGEX=NeedsStartEnd" "NO_EXPAT=1"
      "NO_INSTALL_HARDLINKS=1" "NO_RUST=1" "INSTALL_SYMLINKS=1"
      "CFLAGS=-O2 -I$Z/include -I$O/include -I$C/include"
      "CURL_LIBCURL=-L$C/lib -L$O/lib -L$Z/lib -lcurl -lssl -lcrypto -lz"
    )
    make -j"$(nproc)" "${MK[@]}" >>"$W/build.log" 2>&1
    make "${MK[@]}" install >>"$W/build.log" 2>&1
  ) || die "git 编不出来" "$SRC"
  [ -x "$OUT/bin/git" ] || die "git 没产出可执行文件" "$OUT/bin"
  [ -d "$OUT/libexec/git-core" ] || die "缺 libexec/git-core" "$OUT"
  mkdir -p "$STAGE/bin" "$STAGE/libexec"
  cp "$OUT/bin/git" "$STAGE/bin/git"
  cp -a "$OUT/libexec/git-core" "$STAGE/libexec/"
  SCOPE="bin libexec"
}

build_make() {
  local ver; ver="$(ver_of make)"
  fetch_src make
  rm -rf "$OUT"; mkdir -p "$OUT"
  (
    cd "$SRC"
    CC="$CC" AR="$AR" RANLIB="$TC/llvm-ranlib" ./configure --host=aarch64-linux-musl       --build=x86_64-pc-linux-gnu --prefix="$OUT" --disable-nls >>"$W/build.log" 2>&1
    make -j"$(nproc)" >>"$W/build.log" 2>&1
    make install >>"$W/build.log" 2>&1
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
      --prefix="$OUT" >>"$W/build.log" 2>&1
    make -j"$(nproc)" >>"$W/build.log" 2>&1
    make install >>"$W/build.log" 2>&1
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
      >>"$W/build.log" 2>&1
    make -j"$(nproc)" LDFLAGS="-static" >>"$W/build.log" 2>&1
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
  git)     build_git ;;
  cmake)   build_cmake ;;
  python)  build_python ;;
  node)    build_node ;;
  *) die "这一件还没有 musl 构建分支" "现有：zlib openssl crypto curl bash busybox jq make pkgconf sqlite git cmake python node（在 $HERE/build-musl-piece.sh 里加分支）" ;;
esac

VER="$(ver_of "$ID")"
[ -n "$VER" ] || VER="$(ver_of openssl)"

PIECE_ID="$ID$([ "$SUFFIX" = "none" ] && echo "" || echo "-$SUFFIX")"
ENTRY=""
[ -f "$STAGE/bin/$ID" ] && ENTRY="bin/$ID"
[ -z "$ENTRY" ] && [ -f "$STAGE/bin/libbusybox.so" ] && ENTRY="bin/libbusybox.so"

BUILD_SHA="$(git -C "$ROOT_DIR" rev-parse HEAD 2>/dev/null || true)"
BUILD_RUN="${GITHUB_RUN_ID:-local}"
node "$ROOT_DIR/scripts/recipes/gen-component-meta.js" "$PIECE_ID" "$VER" "$STAGE/component-meta.json" \
  --payload "$STAGE" --build-sha="$BUILD_SHA" --build-run="$BUILD_RUN" >>"$W/build.log" 2>&1 \
  || die "meta 生成失败" "$STAGE"

ENTRY="$(python3 -c "import json,sys;print(json.load(open(sys.argv[1])).get('entry',''))" "$STAGE/component-meta.json" 2>/dev/null || true)"

LLVM_READELF="$READELF" bash -c \
  "source '$ROOT_DIR/scripts/toolchain/elf-form.sh'; cd '$STAGE'; elf_write_shape '$PIECE_ID' '$ENTRY' '$STAGE' '$STAGE/piece-shape.json' '$VER' $SCOPE" \
  >>"$W/build.log" 2>&1 || die "shape 生成失败" "$STAGE"

PKG="$OUTPKG/component-$PIECE_ID-$VER.zip"
rm -f "$PKG"
( cd "$STAGE" && zip -q -r -y "$PKG" $SCOPE piece-shape.json component-meta.json ) || die "打包失败" "$PKG"
echo "$PKG"