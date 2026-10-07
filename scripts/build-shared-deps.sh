#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

HERE="$(dirname "$0")"
cd "$HERE/.."
ROOT_DIR="$(pwd)"

DEPS="${DEPS:-$ROOT_DIR/work/deps}"
ANDROID_API="${ANDROID_API:-23}"
JOBS="${JOBS:-2}"

die() {
  local title="$1"; shift
  echo "::error title=$title::$(printf '%s\n' "$@")"
  exit 1
}

[ -n "${CC:-}" ] || die "缺 CC" "需要 NDK 的 clang"
TC="$(dirname "$CC")"
AR_BIN="$TC/llvm-ar"
RANLIB_BIN="$TC/llvm-ranlib"
for t in "$AR_BIN" "$RANLIB_BIN"; do
  [ -x "$t" ] || die "缺工具" "$t 不存在"
done
mkdir -p "$DEPS" "$ROOT_DIR/work"

echo "[deps] 前缀=$DEPS API=$ANDROID_API CC=$CC"

echo "[deps] zlib"
bash "$ROOT_DIR/scripts/fetch-pinned.sh" --pin zlib "$ROOT_DIR/work/zlib.tar.gz"
rm -rf "$ROOT_DIR/work/zlib" && mkdir -p "$ROOT_DIR/work/zlib"
tar xzf "$ROOT_DIR/work/zlib.tar.gz" -C "$ROOT_DIR/work/zlib" --strip-components=1
(
  set -e
  cd "$ROOT_DIR/work/zlib"
  CHOST=aarch64-linux-android CC="$CC" AR="$AR_BIN" RANLIB="$RANLIB_BIN" \
    ./configure --prefix="$DEPS" --static >/dev/null
  make -j"$JOBS" >/dev/null
  make install >/dev/null
)
[ -f "$DEPS/lib/libz.a" ] || die "zlib 没产出" "$DEPS/lib 下没有 libz.a"
echo "[deps] zlib 就位：$(ls "$DEPS/lib" | tr '\n' ' ')"

echo "[deps] openssl"
bash "$ROOT_DIR/scripts/fetch-pinned.sh" --pin openssl "$ROOT_DIR/work/openssl.tar.gz"
rm -rf "$ROOT_DIR/work/openssl" && mkdir -p "$ROOT_DIR/work/openssl"
tar xzf "$ROOT_DIR/work/openssl.tar.gz" -C "$ROOT_DIR/work/openssl" --strip-components=1
export ANDROID_API
NDK_ROOT="$(cd "$TC/../../../../.." && pwd)"
[ -d "$NDK_ROOT" ] || die "定位 NDK 失败" \
  "从 CC 反推：$TC/../../../../.. 得到 '$NDK_ROOT'，它不是目录" \
  "CC=$CC —— 它由 CI 的 scripts/locate-ndk.sh 注入。"
export ANDROID_NDK_HOME="$NDK_ROOT" ANDROID_NDK_ROOT="$NDK_ROOT"
export SOURCE_DATE_EPOCH="$(bash "$ROOT_DIR/scripts/fetch-pinned.sh" --time-base)"
(
  set -e
  cd "$ROOT_DIR/work/openssl"
  export PATH="$TC:$PATH"
  ./Configure android-arm64 -fPIC -D__ANDROID_API__=$ANDROID_API \
    --prefix="$DEPS" --openssldir="$DEPS/ssl" \
    no-shared no-tests no-ui-console > "$ROOT_DIR/work/deps-openssl-configure.log" 2>&1 \
    || { echo "=== openssl Configure 失败取证（末 30 行）==="; tail -30 "$ROOT_DIR/work/deps-openssl-configure.log"; exit 1; }
  make -j"$JOBS" build_libs > "$ROOT_DIR/work/deps-openssl-build.log" 2>&1 \
    || { echo "=== openssl 编译失败取证（末 30 行）==="; tail -30 "$ROOT_DIR/work/deps-openssl-build.log"; exit 1; }
)
bash "$ROOT_DIR/scripts/verify-userland-build-date.sh" "$ROOT_DIR/work/openssl" \
  || die "openssl 构建时间基准不合格" "上条命令已打印原因"
make -C "$ROOT_DIR/work/openssl" install_sw >/dev/null
for a in libssl.a libcrypto.a; do
  [ -f "$DEPS/lib/$a" ] || die "openssl 没产出 $a" "$DEPS/lib 下没有它"
done
echo "[deps] openssl 就位（静态）"

echo "[deps] curl"
bash "$ROOT_DIR/scripts/fetch-pinned.sh" --pin curl "$ROOT_DIR/work/deps-curl.tar.gz"
rm -rf "$ROOT_DIR/work/deps-curl" && mkdir -p "$ROOT_DIR/work/deps-curl"
tar xzf "$ROOT_DIR/work/deps-curl.tar.gz" -C "$ROOT_DIR/work/deps-curl" --strip-components=1
export PKG_CONFIG_PATH="$DEPS/lib/pkgconfig"
export CPPFLAGS="-I$DEPS/include"
export LDFLAGS="-L$DEPS/lib"
export LIBS="-lssl -lcrypto -lz -ldl"
(
  set -e
  cd "$ROOT_DIR/work/deps-curl"
  ./configure --host=aarch64-linux-android --build=x86_64-pc-linux-gnu \
    --prefix="$DEPS" --with-openssl="$DEPS" --with-zlib="$DEPS" \
    --with-ca-path=/system/etc/security/cacerts \
    --disable-shared --enable-static --disable-ldap --without-libssh2 --without-libidn2 \
    --without-nghttp2 --without-brotli --without-zstd --without-libpsl --disable-manual \
    --disable-ftp --disable-file --disable-dict --disable-telnet --disable-tftp \
    --disable-pop3 --disable-imap --disable-smtp --disable-gopher --disable-mqtt --disable-rtsp \
    --enable-http \
    ac_cv_lib_crypto_HMAC_Update=yes ac_cv_lib_crypto_HMAC_Init_ex=yes \
    curl_cv_lib_crypto_HMAC_Update=yes curl_cv_lib_crypto_HMAC_Init_ex=yes \
    ac_cv_lib_ssl_SSL_new=yes curl_cv_lib_ssl_SSL_connect=yes curl_cv_lib_ssl_SSL_get_peer_certificate=yes \
    curl_cv_openssl_with_ldl=yes curl_cv_openssl_with_ldl_and_lpthread=yes \
    CC="$CC" AR="$AR_BIN" RANLIB="$RANLIB_BIN" > "$ROOT_DIR/work/deps-curl-configure.log" 2>&1 \
    || { echo "=== curl configure 失败取证（openssl 相关 + 末 20 行）==="; \
         grep -i openssl "$ROOT_DIR/work/deps-curl-configure.log" | tail -12 || true; \
         tail -20 "$ROOT_DIR/work/deps-curl-configure.log"; exit 1; }
  make -j"$JOBS" -C lib > "$ROOT_DIR/work/deps-curl-build.log" 2>&1 \
    || { echo "=== curl 编译失败取证（末 30 行）==="; tail -30 "$ROOT_DIR/work/deps-curl-build.log"; exit 1; }
  make -C lib install >/dev/null
  make -C include install >/dev/null
)
[ -f "$DEPS/lib/libcurl.a" ] || die "curl 没产出 libcurl.a" "$DEPS/lib 下没有它"
[ -f "$DEPS/include/curl/curl.h" ] || die "curl 头文件没装上" "git 会编不过 http.c"
echo "[deps] curl 就位（静态）"

echo "[deps] 前缀内容："
ls "$DEPS/lib"/*.a 2>/dev/null | while read -r f; do echo "  $(basename "$f") $(stat -c%s "$f") 字节"; done