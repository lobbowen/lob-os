#!/usr/bin/env bash
# 按修复后的构建链重编单个 python 版本到独立 OUT，并打包。
# 用法: bash scripts/toolchain/rebuild-python-version.sh 3.12 3.12.10 <build_python>
set -euo pipefail
cd /home/bowen/LOBOS/work/lob-os

SLOT="$1"          # 3.12 / 3.11
VER="$2"           # 3.12.10
BUILD_PY="$3"      # 宿主 python 路径

export CC=/home/bowen/ndk-acquire/android-ndk-r30/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android35-clang
export CXX=/home/bowen/ndk-acquire/android-ndk-r30/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android35-clang++
export PY_SLOT="$SLOT"
export PY_VERSION="$VER"
export BUILD_PYTHON="$BUILD_PY"
export OUT="dist-python-$SLOT"

rm -rf "$OUT"
echo "[rebuild] python $VER (slot=$SLOT) 开始"
bash scripts/recipes/build-component-python3.sh 2>&1 | tail -5
PKG_NAME=python bash scripts/recipes/package-component.sh "python-$SLOT" 2>&1 | tail -3
echo "[rebuild] 完成:"
ls -l "$OUT"/component-python-*.zip
