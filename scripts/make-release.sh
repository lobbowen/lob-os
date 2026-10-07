#!/usr/bin/env bash
set -euo pipefail

VER="${1:?用法: ./scripts/make-release.sh <node-version>}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STAGE_DIR="$ROOT/dist/node"

SRC="$STAGE_DIR/bin/node"
[ -f "$SRC" ] || {
  echo "缺少 node 件: $SRC"
  echo "先跑 scripts/build-component-node.sh（它从 Release 取已编译的 libnode.so 落成商店件）"
  exit 1
}

GOT="$(sha256sum "$SRC" | cut -d' ' -f1)"
SIZE="$(stat -c%s "$SRC")"

echo "发布件: $SRC"
echo "sha256: $GOT"
echo "size:   $SIZE"
echo "version: $VER"
echo
cat <<'TXT'
下一步（商店通道，三步）：

1. 打包成内容寻址的件包：
     bash scripts/package-component.sh node
   产出 dist/component-node-<版本>-<sha12>-android-arm64.zip
   （包名带 sha12 前缀，同版本重建不会覆盖旧键）

2. 投到商店并重发清单（清单会带 node 条目）：
     node scripts/publish-component-manifest.js dist          # 签名，需 keys/ota-private.pem
     node scripts/upload-qiniu.js dist/component-node-*.zip component/<同名 zip>

3. 设备端：os.catalog action=refresh 刷新清单 → 装 node
   落点 files/usr/lib/toolchain/node/，usr/bin/node 由 SupplyProvisioner 建链

版本真相有两处，互为对照：商店清单里 node 条目的 version，
与实际二进制（node -p process.versions.node）。两者不一致就是发布出了问题。
TXT
