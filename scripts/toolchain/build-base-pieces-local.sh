#!/usr/bin/env bash
# 本地重编 9 个随 APK 内置的 base 件，产出到 container/app/src/main/assets/pieces/<id>.zip
# 全部用仓内 recipe + 本地 NDK 从源码编译，不取 GitHub Release。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

export CC="${CC:-/home/bowen/ndk-acquire/android-ndk-r30/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android35-clang}"
export CXX="${CXX:-/home/bowen/ndk-acquire/android-ndk-r30/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android35-clang++}"
NDK_TC="/home/bowen/ndk-acquire/android-ndk-r30/toolchains/llvm/prebuilt/linux-x86_64/bin"
export LLVM_STRIP="${LLVM_STRIP:-$NDK_TC/llvm-strip}"
export LLVM_AR="${LLVM_AR:-$NDK_TC/llvm-ar}"
export LLVM_RANLIB="${LLVM_RANLIB:-$NDK_TC/llvm-ranlib}"
export LLVM_READELF="${LLVM_READELF:-$NDK_TC/llvm-readelf}"

ASSETS="container/app/src/main/assets/pieces"
mkdir -p "$ASSETS"
if [ "${1:-}" = "--fresh" ]; then rm -f "$ASSETS"/*.zip; fi

build() {
  local id="$1" recipe="$2"
  if [ -f "$ASSETS/$id.zip" ]; then
    echo "[base] $id 已归集，跳过"
    return 0
  fi
  echo "[base] 构建 $id ($recipe)"
  bash "scripts/recipes/$recipe"
  local f
  f="$(ls dist/component-$id-*.zip 2>/dev/null | head -1)"
  [ -n "$f" ] || f="$(ls work/component-$id-*.zip 2>/dev/null | head -1)"
  [ -n "$f" ] || { echo "::error title=$id 没产出::找不到 component-$id-*.zip"; exit 1; }
  cp -f "$f" "$ASSETS/$id.zip"
  echo "[base] $id.zip 已归集"
}

# 8 个有独立 recipe 的件
build bash      build-bundle-bash.sh
build busybox   build-native-busybox.sh
build zlib      build-bundle-zlib.sh       # curl/git 依赖它的头文件，必须先编
build openssl   build-bundle-openssl.sh    # 同时产出 libssl(openssl件)
build curl      build-bundle-curl.sh
build jq        build-bundle-jq.sh
build ripgrep   build-bundle-rg.sh
echo "[base] 构建 git (build-component-git.sh)"
if [ ! -f "$ASSETS/git.zip" ]; then
  bash scripts/recipes/build-component-git.sh
  PKG_NAME=git bash scripts/recipes/package-component.sh git
  cp -f "$(ls dist/component-git-*.zip 2>/dev/null | head -1)" "$ASSETS/git.zip"
  echo "[base] git.zip 已归集"
else
  echo "[base] git 已归集，跳过"
fi

# crypto：与 openssl 同源，从 openssl 编出的 libcrypto-lobos.so 单独成件
echo "[base] 构建 crypto（从 openssl 产物拆 libcrypto-lobos.so）"
CRYPTO_OUT="$ROOT/work/crypto/out"
SSL_OUT="$ROOT/work/openssl/out"
mkdir -p "$CRYPTO_OUT/lib"
LIBCRYPTO="$(ls "$SSL_OUT"/lib/libcrypto-lobos.so "$SSL_OUT"/lib/libcrypto.so* 2>/dev/null | head -1 || true)"
[ -n "$LIBCRYPTO" ] || { echo "::error title=crypto 无来源::openssl OUT_DIR 里没有 libcrypto"; exit 1; }
cp -Pf "$LIBCRYPTO" "$CRYPTO_OUT/lib/libcrypto-lobos.so"
(
  source "$ROOT/scripts/recipes/bundle-env.sh" crypto
  emit_piece crypto "$CRYPTO_OUT" lib/libcrypto-lobos.so 1000 ""
)
cp -f "$(ls dist/component-crypto-*.zip 2>/dev/null | head -1)" "$ASSETS/crypto.zip"
echo "[base] crypto.zip 已归集"

# 把 dist/ 里的件按 id 拷到 assets/pieces/<id>.zip
echo "[base] 归集到 $ASSETS"
for id in bash busybox crypto curl git jq openssl ripgrep zlib; do
  f=$(ls dist/component-$id-*.zip 2>/dev/null | head -1)
  [ -n "$f" ] || { echo "::error title=base件缺失::$id 没有产出 component-$id-*.zip"; exit 1; }
  cp -f "$f" "$ASSETS/$id.zip"
  echo "  $id.zip <- $(basename "$f")"
done

echo "=== base 件全部就位 ==="
ls -l "$ASSETS"/*.zip | awk '{print "  "$5" "$NF}'
