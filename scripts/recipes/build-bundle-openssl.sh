#!/usr/bin/env bash

set -uo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/bundle-env.sh" openssl

OUT_DIR="$WORK/out"
mkdir -p "$OUT_DIR"
TC="$(dirname "$CC")"
export PATH="$TC:$PATH"
export ANDROID_NDK_HOME="$NDK_ROOT" ANDROID_NDK_ROOT="$NDK_ROOT"
export SOURCE_DATE_EPOCH="$(bash "$ROOT_DIR/scripts/toolchain/fetch-pinned.sh" --source-date-epoch 2>/dev/null || echo 0)"

bash "$ROOT_DIR/scripts/toolchain/fetch-pinned.sh" --pin openssl "$ROOT_DIR/work/openssl.tar.gz" \
  || die "openssl 源码取不到" "钉值见 scripts/component-sources.json"
rm -rf "$WORK/src" && mkdir -p "$WORK/src"
tar xzf "$ROOT_DIR/work/openssl.tar.gz" -C "$WORK/src" --strip-components=1

(
  set -e
  cd "$WORK/src"

  for PATCH_NAME in openssl-runpath-origins.patch openssl-libname-variant.patch; do
    bash "$ROOT_DIR/scripts/toolchain/fetch-pinned.sh" --pin "$PATCH_NAME" "$WORK/$PATCH_NAME"
    if ! patch -p1 -i "$WORK/$PATCH_NAME"; then
      echo "::error title=补丁打不上::$PATCH_NAME —— 而上游 openssl 版本与这一份补丁不匹配？"
      exit 1
    fi
    echo "[openssl] 补丁已打：$PATCH_NAME"
  done

  ./Configure android-arm64 shared -fPIC -D__ANDROID_API__=$API \
    '-Wl,--enable-new-dtags' \
    --prefix="$OUT_DIR" --openssldir="$OUT_DIR/ssl" \
    no-tests no-ui-console > "$WORK/configure.log" 2>&1 \
    || { echo "=== openssl Configure 失败取证（末 30 行）==="; tail -30 "$WORK/configure.log"; exit 1; }
  make -j"$JOBS" build_libs > "$WORK/build.log" 2>&1 \
    || { echo "=== openssl 编译失败取证（末 30 行）==="; tail -30 "$WORK/build.log"; exit 1; }
  make install_sw > "$WORK/install.log" 2>&1 \
    || { echo "=== openssl install_sw 失败取证（末 30 行）==="; tail -30 "$WORK/install.log"; exit 1; }
)

[ -f "$OUT_DIR/include/openssl/opensslv.h" ] || [ -f "$WORK/src/include/openssl/opensslv.h" ] \
  || die "openssl 没产出版本头" "opensslv.h 应该在（找不到说明 Configure 或 make 失败）"
echo "[openssl] 版本头在位"

SSL="$OUT_DIR/lib/libssl-lobos.so"
[ -f "$SSL" ] || SSL="$(ls "$OUT_DIR"/lib/libssl.so* 2>/dev/null | head -1)"
[ -n "$SSL" ] && [ -f "$SSL" ] || die "openssl 没产出 libssl" "$OUT_DIR/lib 下没有 libssl*（改名**后**的**那一个**）"

  OPENSSL_BINS=""
  [ -e "$OUT_DIR/bin/openssl" ] && OPENSSL_BINS="openssl"

  OPENSSL_LIBS=1 emit_piece openssl "$OUT_DIR" lib/libssl-lobos.so 1000 "$OPENSSL_BINS"
