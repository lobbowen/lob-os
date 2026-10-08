#!/usr/bin/env bash
# 编 openssl —— **一件一个脚本**，产出 libssl.so 与 libcrypto.so。
#
# crypto 不是独立的上游项目（OpenSSL 的一部分），但它是独立的 .so、能单独升级，
# 所以落位上是两件 —— 与 verify.json 的 crypto 条目一致（sameAs: openssl）。
#
# 此前与 zlib、curl 一起编到同一个 $DEPS/ 下（no-shared 静态），
# 依赖它的件把依赖静态链了进去。现在产出共享库，只记 DT_NEEDED。
set -uo pipefail
# shellcheck disable=SC1091
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/piece-env.sh" openssl

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
  # shared（原来 no-shared）· -fPIC 供动态链接 · 去掉 no-tests/no-ui-console
  ./Configure android-arm64 shared -fPIC -D__ANDROID_API__=$API \
    --prefix="$OUT_DIR" --openssldir="$OUT_DIR/ssl" \
    no-tests no-ui-console > "$WORK/configure.log" 2>&1 \
    || { echo "=== openssl Configure 失败取证（末 30 行）==="; tail -30 "$WORK/configure.log"; exit 1; }
  make -j"$JOBS" build_libs > "$WORK/build.log" 2>&1 \
    || { echo "=== openssl 编译失败取证（末 30 行）==="; tail -30 "$WORK/build.log"; exit 1; }
  make install_sw > "$WORK/install.log" 2>&1 \
    || { echo "=== openssl install_sw 失败取证（末 30 行）==="; tail -30 "$WORK/install.log"; exit 1; }
)

// ★ 此前这里校验 buildinf.h（构建时间基准）——
//   OpenSSL 3.x 不再生成那个文件（那个判据是 1.x 时代的），所以它成了空尺子。
//   判据改成「构建产物里要有版本头」：openssl/version.h 或 include/openssl/opensslv.h
[ -f "$OUT_DIR/include/openssl/opensslv.h" ] || [ -f "$WORK/src/include/openssl/opensslv.h" ] \
  || die "openssl 没产出版本头" "opensslv.h 应该在（找不到说明 Configure 或 make 失败）"
echo "[openssl] 版本头在位"

# libssl.so 与 libcrypto.so 是两件 —— 分别落位、分别建全局软链
for pair in "openssl:libssl.so" "crypto:libcrypto.so"; do
  id="${pair%%:*}"; so="${pair##*:}"
  SO="$(ls "$OUT_DIR"/lib/$so* 2>/dev/null | head -1)"
  [ -n "$SO" ] || die "$id 没产出共享库" "$OUT_DIR/lib 下没有 $so*"
  land_piece "$id" "$SO" 1000
done
echo "[ok] openssl 与 crypto 是共享库（不是静态链进别的二进制）"
