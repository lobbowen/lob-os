#!/usr/bin/env bash
# 编 curl —— **一件一个脚本**，产出共享库 libcurl.so。
#
# 关键改动：原来 --disable-shared --enable-static（静态），
# 于是 git 编的时候 -lcurl 把整个 curl 链了进去。
# 现在产出 .so，git 只记 DT_NEEDED libcurl.so，运行时从全局软链找。
#
# configure 参数从 configure.log 里查证过（configure 脚本自己会打印），
# 不靠猜—— 保留必要的 ac_cv_* 交叉编探测。
set -uo pipefail
# shellcheck disable=SC1091
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/piece-env.sh" curl

OUT_DIR="$WORK/out"
mkdir -p "$OUT_DIR"

# curl 依赖 openssl 与 zlib —— 依赖的是**全局落位的那份**（base 筐的预装件），
# 不是另编一份。configure 需要一个 --with-openssl 前缀，
# 而它检查那个前缀下有没有 include/openssl/ssl.h 与 lib/libssl.so。
# 那正是我们落位的形状：usr/lib/<id>/<版本>/{include,lib}/
# 用/usr/lib 这个前缀（全局软链都在那儿）。
SYSROOT_PREFIX="$WORK/../zlib-prefix"
mkdir -p "$SYSROOT_PREFIX/lib" "$SYSROOT_PREFIX/include"
# zlib/openssl 的落位目录由各自的脚本产出，这里直接指向它们
ZLIB_PREFIX="$ROOT_DIR/work/zlib/out"
OPENSSL_PREFIX="$ROOT_DIR/work/openssl/out"
[ -f "$OPENSSL_PREFIX/include/openssl/ssl.h" ] \
  || die "openssl 头文件不在位" "$OPENSSL_PREFIX/include/openssl/ssl.h（openssl 那件失败了吧）"
[ -f "$ZLIB_PREFIX/include/zlib.h" ] \
  || die "zlib 头文件不在位" "$ZLIB_PREFIX/include/zlib.h（zlib 那件失败了吧）"

bash "$ROOT_DIR/scripts/toolchain/fetch-pinned.sh" --pin curl "$ROOT_DIR/work/curl.tar.gz" \
  || die "curl 源码取不到" "钉值见 scripts/component-sources.json"
rm -rf "$WORK/src" && mkdir -p "$WORK/src"
tar xzf "$ROOT_DIR/work/curl.tar.gz" -C "$WORK/src" --strip-components=1

(
  set -e
  cd "$WORK/src"
  # shared（原来 --disable-shared --enable-static）· 只留 http
  ./configure --host=aarch64-linux-android --build=x86_64-pc-linux-gnu \
    --prefix="$OUT_DIR" --with-openssl="$OPENSSL_PREFIX" --with-zlib="$ZLIB_PREFIX" \
    --with-ca-path=/system/etc/security/cacerts \
    --enable-shared --disable-static --disable-ldap --without-libssh2 \
    --without-nghttp2 --without-brotli --without-zstd --without-libpsl \
    --disable-ftp --disable-file --disable-dict --disable-telnet --disable-tftp \
    --disable-pop3 --disable-imap --disable-smtp --disable-gopher --disable-mqtt \
    --enable-http \
    ac_cv_lib_crypto_HMAC_Update=yes ac_cv_lib_crypto_HMAC_Init_ex=yes \
    curl_cv_lib_crypto_HMAC_Update=yes curl_cv_lib_crypto_HMAC_Init_ex=yes \
    ac_cv_lib_ssl_SSL_new=yes curl_cv_lib_ssl_SSL_connect=yes curl_cv_lib_ssl_SSL_library_init=yes \
    curl_cv_openssl_with_ldl=yes curl_cv_openssl_with_ldl_and_lpthread=yes \
    CC="$CC" AR="$AR_BIN" RANLIB="$RANLIB_BIN" > "$WORK/configure.log" 2>&1 \
    || { echo "=== curl configure 失败取证（openssl 相关 + 末 20 行）==="; \
         grep -i openssl "$WORK/configure.log" | tail -12 || true; \
         tail -20 "$WORK/configure.log"; exit 1; }
  make -j"$JOBS" -C lib > "$WORK/build.log" 2>&1 \
    || { echo "=== curl 编译失败取证（末 30 行）==="; tail -30 "$WORK/build.log"; exit 1; }
  make -C lib install > "$WORK/install.log" 2>&1 \
    || { echo "=== curl install 失败取证（末 30 行）==="; tail -30 "$WORK/install.log"; exit 1; }
  make -C include install > /dev/null 2>&1 || true
)

SO="$(ls "$OUT_DIR"/lib/libcurl.so* 2>/dev/null | head -1)"
[ -n "$SO" ] || die "curl 没产出共享库" "$OUT_DIR/lib 下没有 libcurl.so*"
[ -f "$OUT_DIR/include/curl/curl.h" ] || die "curl 头文件没装上" "依赖它的件编不过 http.c"

land_piece curl "$SO" 1000
echo "[ok] curl 是共享库（git 编的时候只记 NEEDED，不静态链进去）"
