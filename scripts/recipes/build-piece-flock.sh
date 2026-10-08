#!/usr/bin/env bash
# 编 flock —— 我们自己写的 C，编成 liblobosflock.so 给程序用。
#
# 依赖 node 头文件（flock.c 是 node addon，用 node_api.h）
# 依赖 node 头文件（flock.c 是 node addon，用 node_api.h）
set -uo pipefail
# shellcheck disable=SC1091
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/piece-env.sh" flock

NODE_VERSION="${NODE_VERSION:-$(bash scripts/registry/read-node-versions.sh default 2>/dev/null || echo "")}"
[ -n "$NODE_VERSION" ] || die "取不到 node 版本" "node 头文件从哪来？scripts/registry/read-node-versions.sh 没读出 default"

rm -rf /tmp/node-headers /tmp/node-headers.tar.xz
mkdir -p /tmp/node-headers
# 失败时给取证 —— 此前 flock 只报一行 FAILED，什么线索都没有
fetch_node() {
  echo "=== node 头文件获取的取证 ==="
  echo "--- 钉值表里 node 那一格 ---"
  node -e "process.stdout.write(JSON.stringify(require('./scripts/component-sources.json').sources.node, null, 1))" \
    || echo "（读不到 component-sources.json）"
  echo "--- fetch-pinned 说要什么 ---"
  bash scripts/toolchain/fetch-pinned.sh --pin node 2>&1 | head -20
  echo "--- work/ 下有什么 ---"
  ls -la "$WORK" 2>/dev/null | head -10 || echo "（$WORK 不存在）"
}
if ! bash scripts/toolchain/fetch-pinned.sh --pin node "$WORK/node-headers.tar.xz"; then
  fetch_node
  die "node 源码取不到" "sha256 见 scripts/component-sources.json（上面是取证）"
fi
mkdir -p "$WORK/node-headers"
tar -xJf "$WORK/node-headers.tar.xz" -C "$WORK/node-headers" --strip-components=1
INC="$WORK/node-headers/include/node"
[ -f "$INC/node_api.h" ] || {
  echo "=== node 头文件解包后的取证 ==="
  ls -la "$WORK/node-headers" 2>/dev/null | head -20
  echo "--- include 下有什么 ---"
  ls "$WORK/node-headers/include" 2>/dev/null | head -20 || echo "（没有 include 目录）"
  die "头文件异常" "缺 $INC/node_api.h（上面是取证）"
}

"$CC" -shared -fPIC -O2 -DNAPI_VERSION=9 -I "$INC" -o "$WORK/liblobosflock.so" \
  container/native/d2/flock.c || die "flock 编译失败" "源码 container/native/d2/flock.c"
land_piece flock liblobosflock.so 1000