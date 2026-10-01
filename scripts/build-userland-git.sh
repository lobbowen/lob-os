#!/usr/bin/env bash
set -euo pipefail

HERE=$(dirname "$0")
cd "$HERE/.."
ROOT_DIR=$(pwd)

if [ -z "${CC:-}" ]; then
  echo "::error title=缺 CC::需要 CC（aarch64-linux-android21-clang）"
  exit 1
fi

OUT="${OUT:-dist}"
mkdir -p "$ROOT_DIR/$OUT/bin" work

echo "[git] 取源码（钉值表的 git 那一格）"
bash "$ROOT_DIR/scripts/fetch-pinned.sh" --pin git "$ROOT_DIR/work/git.tar.xz" \
  --version-file "$ROOT_DIR/$OUT/git.version"
echo "[git] 源码包 $(stat -c%s work/git.tar.xz) 字节"
rm -rf work/git-src && mkdir -p work/git-src
if ! tar xJf work/git.tar.xz -C work/git-src --strip-components=1; then
  echo "::error title=解包失败::tar.xz 损坏或上游换了压缩格式"
  exit 1
fi
[ -f work/git-src/Makefile ] || { echo "::error title=源码树异常::没有 Makefile"; exit 1; }
echo "[git] 源码树就位"

DEPS="$ROOT_DIR/work/deps"
mkdir -p "$DEPS"
TC_DIR=$(dirname "$CC")
ANDROID_API=23
if [ ! -x "$TC_DIR/aarch64-linux-android$ANDROID_API-clang" ]; then
  echo "::error title=缺 API$ANDROID_API 的 clang::NDK 里没有 aarch64-linux-android$ANDROID_API-clang"
  exit 1
fi
export CC="$TC_DIR/aarch64-linux-android$ANDROID_API-clang"
echo "[git] 编译 API = $ANDROID_API（$CC）"
AR_BIN=${AR_BIN:-$TC_DIR/llvm-ar}
RANLIB_BIN=${RANLIB_BIN:-$TC_DIR/llvm-ranlib}
export ANDROID_NDK_ROOT="${ANDROID_NDK_LATEST_HOME:-}"
if [ -z "$ANDROID_NDK_ROOT" ]; then
  ANDROID_NDK_ROOT=$(cd "$TC_DIR/../../../../.." && pwd)
fi
echo "[git] NDK root = $ANDROID_NDK_ROOT"

bash "$ROOT_DIR/scripts/fetch-pinned.sh" --pin zlib "$ROOT_DIR/work/zlib.tar.gz"
rm -rf "$ROOT_DIR/work/zlib" && mkdir -p "$ROOT_DIR/work/zlib"
tar xzf "$ROOT_DIR/work/zlib.tar.gz" -C "$ROOT_DIR/work/zlib" --strip-components=1
cd "$ROOT_DIR/work/zlib"
CHOST=aarch64-linux-android CC="$CC" AR="$AR_BIN" RANLIB="$RANLIB_BIN" ./configure --prefix="$DEPS" --static >/dev/null
make -j2 >/dev/null
make install >/dev/null
ZLIB_LIBS=$(ls "$DEPS/lib" | tr "\n" " ")
echo "[git] zlib 就位：$ZLIB_LIBS"

bash "$ROOT_DIR/scripts/fetch-pinned.sh" --pin openssl "$ROOT_DIR/work/openssl.tar.gz"
rm -rf "$ROOT_DIR/work/openssl" && mkdir -p "$ROOT_DIR/work/openssl"
tar xzf "$ROOT_DIR/work/openssl.tar.gz" -C "$ROOT_DIR/work/openssl" --strip-components=1
cd "$ROOT_DIR/work/openssl"
export ANDROID_API="$ANDROID_API"
export ANDROID_NDK_HOME="$ANDROID_NDK_ROOT"
CFG_LOG="$ROOT_DIR/work/openssl-configure.log"
export PATH="$TC_DIR:$PATH"
export SOURCE_DATE_EPOCH="$(bash "$ROOT_DIR/scripts/fetch-pinned.sh" --time-base)"
if ! ./Configure android-arm64 -fPIC -D__ANDROID_API__=$ANDROID_API --prefix="$DEPS" --openssldir="$DEPS/ssl" no-shared no-tests no-ui-console > "$CFG_LOG" 2>&1; then
  echo "::error title=openssl Configure 失败::下面是最后 30 行（真正的致命行在这里）"
  tail -n 30 "$CFG_LOG" || true
  exit 1
fi
BUILD_LOG="$ROOT_DIR/work/openssl-build.log"
if ! make -j2 build_libs > "$BUILD_LOG" 2>&1; then
  echo "::error title=openssl 编译失败::最后 30 行"
  tail -n 30 "$BUILD_LOG" || true
  exit 1
fi
bash "$ROOT_DIR/scripts/verify-userland-build-date.sh" "$ROOT_DIR/work/openssl"
make install_sw >/dev/null
echo "[git] openssl 就位：$(ls "$DEPS/lib" | grep -c "[.]a") 个 .a"

bash "$ROOT_DIR/scripts/fetch-pinned.sh" --pin curl "$ROOT_DIR/work/curl.tar.gz"
rm -rf "$ROOT_DIR/work/curl" && mkdir -p "$ROOT_DIR/work/curl"
tar xzf "$ROOT_DIR/work/curl.tar.gz" -C "$ROOT_DIR/work/curl" --strip-components=1
cd "$ROOT_DIR/work/curl"
export PKG_CONFIG_PATH="$DEPS/lib/pkgconfig"
export CC="$CC" AR="$AR_BIN" RANLIB="$RANLIB_BIN"
export CPPFLAGS="-I$DEPS/include" LDFLAGS="-L$DEPS/lib"
if ! ./configure --host=aarch64-linux-android --build=x86_64-pc-linux-gnu --prefix="$DEPS" \
  --with-openssl="$DEPS" --with-zlib="$DEPS" --with-ca-path=/system/etc/security/cacerts \
  --disable-shared --enable-static --disable-ldap --without-libssh2 --without-libidn2 \
  --without-nghttp2 --without-brotli --without-zstd --without-libpsl --disable-manual \
  --disable-ftp --disable-file --disable-dict --disable-telnet --disable-tftp \
  --disable-pop3 --disable-imap --disable-smtp --disable-gopher --disable-mqtt --disable-rtsp \
  --enable-http \
  ac_cv_lib_crypto_HMAC_Update=yes ac_cv_lib_crypto_HMAC_Init_ex=yes \
  curl_cv_lib_crypto_HMAC_Update=yes curl_cv_lib_crypto_HMAC_Init_ex=yes \
  ac_cv_lib_ssl_SSL_new=yes ac_cv_lib_ssl_SSL_connect=yes ac_cv_lib_ssl_SSL_get_peer_certificate=yes \
  curl_cv_openssl_with_ldl=yes curl_cv_openssl_with_ldl_and_lpthread=yes \
  LIBS="-lssl -lcrypto -lz -ldl" CC="$CC" AR="$AR_BIN" RANLIB="$RANLIB_BIN" CPPFLAGS="-I$DEPS/include" LDFLAGS="-L$DEPS/lib" > "$ROOT_DIR/work/curl-configure.log" 2>&1; then
  echo "::error title=curl Configure 失败::下面是真因"
  echo "==== config.log 里 HMAC_Update 那段（编译/链接命令与报错都在这里）===="
  grep -n -B 14 -A 8 "HMAC_Update" "$ROOT_DIR/work/curl/config.log" | head -n 90 || true
  echo "==== config.log 尾 30 行 ===="
  tail -n 30 "$ROOT_DIR/work/curl/config.log" || true
  echo "==== config.log 定位 ===="
  ls -la "$ROOT_DIR/work/curl/config.log" 2>/dev/null || find "$ROOT_DIR/work/curl" -maxdepth 2 -name config.log || true
  echo "==== configure 输出尾 15 行 ===="
  tail -n 15 "$ROOT_DIR/work/curl-configure.log" || true
  exit 1
else
  echo "[git] curl Configure 通过"
fi
if [ ! -f "$ROOT_DIR/work/curl/Makefile" ]; then
  echo "::error title=curl 没生成 Makefile::下面是 configure 输出尾 40 行、pwd 与目录内容"
  echo "==== pwd ===="
  pwd || true
  echo "==== configure 输出尾 40 行 ===="
  tail -n 40 "$ROOT_DIR/work/curl-configure.log" || true
  echo "==== 目录内容（前 25 项）===="
  ls -la "$ROOT_DIR/work/curl" | head -n 25 || true
  echo "==== 找 Makefile* ===="
  find "$ROOT_DIR/work/curl" -maxdepth 1 -name "Makefile*" || true
  exit 1
fi
echo "[git] curl Makefile 已生成"
CURL_LOG="$ROOT_DIR/work/curl-build.log"
if ! make -C lib -j2 > "$CURL_LOG" 2>&1; then
  echo "::error title=curl 库编译失败::最后 30 行"
  tail -n 30 "$CURL_LOG" || true
  exit 1
fi
if ! make -C lib install > "$CURL_LOG" 2>&1; then
  echo "::error title=curl 库安装失败::最后 30 行"
  tail -n 30 "$CURL_LOG" || true
  exit 1
fi
echo "[git] curl 库已装（跳过命令行工具）"
mkdir -p "$DEPS/include"
rm -rf "$DEPS/include/curl"
cp -r "$ROOT_DIR/work/curl/include/curl" "$DEPS/include/"
if [ ! -f "$DEPS/include/curl/curl.h" ]; then
  echo "::error title=curl 头文件没装上::git 会编不过 http.c"
  exit 1
fi
echo "[git] curl 头文件已装"
echo "[git] curl 就位（静态）"

cd "$ROOT_DIR/work/git-src"

cd "$ROOT_DIR/work/git-src"

PATCHES="config.mak.uname.patch run-command.c.patch disable-fdsan.patch disable_daemon_syslog.patch compat-posix.h.patch config.c.patch help.c.patch tempfile.c.patch"
for p in $PATCHES; do
  bash "$ROOT_DIR/scripts/fetch-pinned.sh" --pin "git-$p" "$ROOT_DIR/work/$p"
  if ! patch -p1 -i "$ROOT_DIR/work/$p"; then
    echo "::error title=补丁打不上::$p —— 上游 git 版本与 Termux 补丁集不匹配？"
    exit 1
  fi
  echo "[git] 补丁已打：$p"
done
export CC
TC=$(dirname "$CC")
export AR="$TC/llvm-ar"

IMAP_LINES=$(grep -c '^PROGRAM_OBJS += imap-send[.]o' Makefile || true)
if [ "$IMAP_LINES" != "0" ]; then
  sed -i '/^PROGRAM_OBJS += imap-send[.]o/d' Makefile
  echo "[git] 已从构建目标里去掉 git-imap-send"
else
  echo "::error title=没找到 imap-send 的目标行::上游 Makefile 变了，得重新确认怎么排除"
  exit 1
fi
export RANLIB="$TC/llvm-ranlib"

MAKE_ARGS="CC=$CC AR=$AR RANLIB=$RANLIB PTHREAD_LIBS= NO_RUST=1 CURLDIR=$ROOT_DIR/work/deps OPENSSLDIR=$ROOT_DIR/work/deps uname_S=Linux uname_M=aarch64 prefix=$ROOT_DIR/$OUT CSPRNG_METHOD= HAVE_SYNC_FILE_RANGE= HAVE_GETRUSAGE= HAVE_SYSINFO= NO_EXPAT=1 NO_GETTEXT=1 NO_ICONV=1 NO_TCLTK=1 NO_NSEC=1 NO_INSTALL_HARDLINKS=1 NO_PERL=1 NO_PYTHON=1 RUNTIME_PREFIX=1 ac_cv_fread_reads_directories=yes ac_cv_header_libintl_h=no ac_cv_iconv_omits_bom=no ac_cv_snprintf_returns_bogus=no"
echo "[git] make（$MAKE_ARGS）"
if ! make -j2 $MAKE_ARGS CURL_LIBCURL="-L$ROOT_DIR/work/deps/lib -lcurl -lssl -lcrypto -lz -ldl" CURL_LIBS="-lcurl -lssl -lcrypto -lz" OPENSSL_LIBSSL="-lssl -lcrypto" CPPFLAGS="-I$ROOT_DIR/work/deps/include" LDFLAGS="-L$ROOT_DIR/work/deps/lib" all; then
  echo "::error title=make 失败::见上"
  exit 1
fi
echo "[git] make install"
if ! make $MAKE_ARGS CURL_LIBCURL="-L$ROOT_DIR/work/deps/lib -lcurl -lssl -lcrypto -lz -ldl" CURL_LIBS="-lcurl -lssl -lcrypto -lz" OPENSSL_LIBSSL="-lssl -lcrypto" CPPFLAGS="-I$ROOT_DIR/work/deps/include" LDFLAGS="-L$ROOT_DIR/work/deps/lib" install; then
  echo "::error title=install 失败::见上"
  exit 1
fi

[ -x "$ROOT_DIR/$OUT/bin/git" ] || { echo "::error title=没产出 bin/git::install 落点与预期不符"; ls -la "$ROOT_DIR/$OUT" || true; exit 1; }
if [ ! -d "$ROOT_DIR/$OUT/libexec/git-core" ]; then
  echo "::error title=缺 libexec/git-core::git 的子命令目录没装上（RUNTIME_PREFIX/prefix 落点问题）"
  exit 1
fi
rm -f "$ROOT_DIR/$OUT/bin/git-credential-"* 2>/dev/null || true

GITCORE="$ROOT_DIR/$OUT/libexec/git-core"

STRIPPED=0
if [ -n "${LLVM_STRIP:-}" ] && [ -x "${LLVM_STRIP}" ]; then
  for f in "$ROOT_DIR/$OUT/bin/git" "$GITCORE"/*; do
    [ -f "$f" ] || continue
    [ -L "$f" ] && continue
    if "$LLVM_STRIP" "$f" 2>/dev/null; then STRIPPED=$((STRIPPED+1)); fi
  done
  echo "[git] 已 strip $STRIPPED 个二进制（strip 前 bin/git $(stat -c%s "$ROOT_DIR/$OUT/bin/git") 字节）"
else
  echo "::error title=没有 LLVM_STRIP::未 strip 的件体积会离谱（CI 实测 102 MiB）—— 中止"
  exit 1
fi
BINSIZE=$(stat -c%s "$ROOT_DIR/$OUT/bin/git")
BININODE=$(stat -c%i "$ROOT_DIR/$OUT/bin/git")
FARM="$ROOT_DIR/$OUT/link-farm.txt"
: > "$FARM"
LINKED=0
SLIMMED=0
KEPT=0
for f in "$GITCORE"/*; do
  NAME=$(basename "$f")
  [ -L "$f" ] || [ -e "$f" ] || continue
  if [ -L "$f" ]; then
    TGT=$(readlink "$f")
    printf '%s\t%s\n' "libexec/git-core/$NAME" "$TGT" >> "$FARM"
    LINKED=$((LINKED+1))
  elif [ -f "$f" ] && [ ! -L "$f" ]; then
    FSIZE=$(stat -c%s "$f")
    FINODE=$(stat -c%i "$f")
    if [ "$FINODE" = "$BININODE" ] || [ "$FSIZE" = "$BINSIZE" ]; then
      printf '%s\t%s\n' "libexec/git-core/$NAME" "../../bin/git" >> "$FARM"
      rm -f "$f"
      SLIMMED=$((SLIMMED+1))
    else
      KEPT=$((KEPT+1))
    fi
  fi
done
echo "[git] 链接农场：符号链接 $LINKED、副本瘦身 $SLIMMED、真独立文件 $KEPT"
TREE=$(du -sm "$ROOT_DIR/$OUT" | cut -f1)
echo "[git] 件树体积 ${TREE} MiB"
if [ "$TREE" -gt 60 ]; then
  echo "::error title=件太大::${TREE} MiB —— 链接农场没生效？（bin/git $BINSIZE 字节）"
  exit 1
fi
SIZE=$(stat -c%s "$ROOT_DIR/$OUT/bin/git")
SUBS=$(ls "$ROOT_DIR/$OUT/libexec/git-core" | wc -l)
echo "[git] 产出 bin/git（$SIZE 字节），libexec/git-core $SUBS 项"
