#!/usr/bin/env bash
# 编 flock —— 我们自己写的 C，编成 liblobosflock.so 给程序用。
#
# 依赖 node 头文件（flock.c 是 node addon，用 node_api.h）
set -uo pipefail
# shellcheck disable=SC1091
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/piece-env.sh" flock

# ★ 取官方单独的 -headers.tar.gz，不解 node 源码树。
#   此前解的是 node-vX.tar.gz（源码包），tarball 里**没有 include/**——
#   node 的 include/node 是 configure 跑生成器（tools/gyp）才产出的，
#   不编 node 就不会生成。所以那一步必然报「缺 node_api.h」。
#   node 官方为此单发一个 -headers.tar.gz，里面就是 include/node/。
rm -rf "$WORK/node-headers"
if ! bash scripts/toolchain/fetch-pinned.sh --pin nodeHeaders "$WORK/node-headers.tar.gz"; then
  die "node 头文件取不到" "sha256 见 scripts/component-sources.json 的 sources.nodeHeaders"
fi
mkdir -p "$WORK/node-headers"
tar -xzf "$WORK/node-headers.tar.gz" -C "$WORK/node-headers" --strip-components=1
INC="$WORK/node-headers/include/node"
[ -f "$INC/node_api.h" ] || {
  echo "=== node 头文件解包后的取证 ==="
  ls -la "$WORK/node-headers" 2>/dev/null | head -20
  echo "--- include 下有什么 ---"
  ls "$WORK/node-headers/include" 2>/dev/null | head -20 || echo "（没有 include 目录）"
  die "头文件异常" "缺 $INC/node_api.h（上面是取证）"
}
echo "[flock] node 头文件在位：$(ls "$INC" | wc -l) 个头文件"

"$CC" -shared -fPIC -O2 -DNAPI_VERSION=9 -I "$INC" -o "$WORK/liblobosflock.so" \
  container/native/d2/flock.c || die "flock 编译失败" "源码 container/native/d2/flock.c"
land_piece flock liblobosflock.so 1000