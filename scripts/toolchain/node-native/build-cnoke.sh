#!/usr/bin/env bash
set -euo pipefail

# cnoke.cjs 自研构建体系的模块（非 node-gyp）交叉编译到 Android arm64
# 用法: ANDROID_NDK=<ndk> build-cnoke.sh <模块目录> <cnoke源码子目录> [输出.node]
MODULE_DIR="${1:?用法: build-cnoke.sh <包目录> <源码目录如src/koffi> [输出.node]}"
SRC_DIR="${2:-src/koffi}"
OUT="${3:-}"
NDK="${ANDROID_NDK:?请先设置 ANDROID_NDK}"
API="${ANDROID_API:-24}"

TC="$NDK/build/cmake/android.toolchain.cmake"
[ -f "$TC" ] || { echo "缺 NDK toolchain: $TC" >&2; exit 1; }
[ -f "$MODULE_DIR/cnoke.cjs" ] || { echo "缺 cnoke.cjs: $MODULE_DIR" >&2; exit 1; }

TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
export CC="$TOOLCHAIN/aarch64-linux-android${API}-clang"
export CXX="$TOOLCHAIN/aarch64-linux-android${API}-clang++"

COMMON_ARGS=(configure -P "$MODULE_DIR" -D "$SRC_DIR" --release -t android_arm64
  -d CMAKE_TOOLCHAIN_FILE="$TC" -d ANDROID_ABI=arm64-v8a -d ANDROID_PLATFORM=android-${API})

cd "$MODULE_DIR"
node cnoke.cjs "${COMMON_ARGS[@]}"
node cnoke.cjs build -P "$MODULE_DIR" -D "$SRC_DIR" --release -t android_arm64 \
  -d CMAKE_TOOLCHAIN_FILE="$TC" -d ANDROID_ABI=arm64-v8a -d ANDROID_PLATFORM=android-${API}

NODE_FILE="$(find build -name '*.node' | head -1)"
[ -n "$NODE_FILE" ] || { echo "编译未产出 .node" >&2; exit 1; }
if [ -n "$OUT" ]; then cp "$NODE_FILE" "$OUT"; NODE_FILE="$OUT"; fi
echo "产出: $NODE_FILE"
"$TOOLCHAIN/llvm-readelf" -d "$NODE_FILE" 2>/dev/null | grep NEEDED | sed 's/^/  /' || true
