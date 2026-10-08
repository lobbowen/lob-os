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
bash scripts/toolchain/fetch-pinned.sh --pin node "/tmp/node-headers.tar.xz" \
  || die "node 源码取不到" "sha256 见 scripts/component-sources.json"
tar -xJf /tmp/node-headers.tar.xz -C /tmp/node-headers --strip-components=1
INC=/tmp/node-headers/include/node
[ -f "$INC/node_api.h" ] || die "头文件异常" "缺 $INC/node_api.h"

"$CC" -shared -fPIC -O2 -DNAPI_VERSION=9 -I "$INC" -o "$WORK/liblobosflock.so" \
  container/native/d2/flock.c || die "flock 编译失败" "源码 container/native/d2/flock.c"
land_piece flock liblobosflock.so 1000