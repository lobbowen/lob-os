#!/usr/bin/env bash
set -euo pipefail

MODULE_DIR="${1:?用法: build.sh <含binding.gyp的模块目录> [输出.node路径]}"
OUT="${2:-}"
NDK="${ANDROID_NDK:?请先设置 ANDROID_NDK}"
API="${ANDROID_API:-24}"
NODE_HEADER_DIR="${NODE_HEADER_DIR:-$HOME/.cache/node-gyp/$(node -p 'process.versions.node')}"

[ -f "$MODULE_DIR/binding.gyp" ] || { echo "缺 binding.gyp: $MODULE_DIR" >&2; exit 1; }
[ -d "$NODE_HEADER_DIR" ] || { echo "缺 node headers: $NODE_HEADER_DIR（先 node-gyp install）" >&2; exit 1; }

TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
COMPAT="$(mktemp -d)"
trap 'rm -rf "$COMPAT"' EXIT

# bionic 无独立 libutil；forkpty/openpty 在 libc 里，空库仅满足 -lutil 链接参数
printf 'int lobos_libutil_compat(void) { return 0; }\n' > "$COMPAT/stub.c"
"$TOOLCHAIN/aarch64-linux-android${API}-clang" -c "$COMPAT/stub.c" -o "$COMPAT/stub.o"
"$TOOLCHAIN/llvm-ar" rcs "$COMPAT/libutil.a" "$COMPAT/stub.o"

export CC="$TOOLCHAIN/aarch64-linux-android${API}-clang"
export CXX="$TOOLCHAIN/aarch64-linux-android${API}-clang++"
export AR="$TOOLCHAIN/llvm-ar"
export LDFLAGS="-L$COMPAT"

cd "$MODULE_DIR"
if [ ! -d node_modules/node-addon-api ]; then
  npm install node-addon-api --no-save --ignore-scripts >/dev/null 2>&1 || true
fi
node-gyp clean >/dev/null 2>&1 || true
node-gyp configure --target="$(node -p 'process.versions.node')" --arch=arm64 --nodedir="$NODE_HEADER_DIR" >/dev/null
node-gyp build --arch=arm64 >/dev/null

NODE_FILE="$(find build/Release -maxdepth 1 -name '*.node' | head -1)"
[ -n "$NODE_FILE" ] || { echo "编译未产出 .node" >&2; exit 1; }
if [ -n "$OUT" ]; then cp "$NODE_FILE" "$OUT"; NODE_FILE="$OUT"; fi
echo "产出: $NODE_FILE"
echo "依赖:"
"$TOOLCHAIN/llvm-readelf" -d "$NODE_FILE" 2>/dev/null | grep NEEDED | sed 's/^/  /' || true
