#!/usr/bin/env bash
set -euo pipefail

HERE=$(dirname "$0")
cd "$HERE/../.."
ROOT_DIR=$(pwd)

TOOL="${1:?需要工具名}"
# 版本号读不到就报缺，不编一个值（那个值会一路进文件名与 meta，
# 让「本轮编的」与「上一次留下的」分不出来）。范式照 :13 与 :21。
VER=$(cat "dist/${TOOL}.version" 2>/dev/null || true)
[ -n "$VER" ] || { echo "::error title=缺版本号::dist/${TOOL}.version 不在或为空 —— 不编值（这一版没有版本号就无法判断哪个包是本轮编的）；先跑产出它的 recipe"; exit 1; }
RAW="work/.component-${TOOL}-raw.zip"
mkdir -p "$(dirname "$RAW")"
ENTRY=$(bash "$ROOT_DIR/scripts/registry/read-component-entry.sh" "$TOOL")
# 两处版本号来源：钉值表（声明）与本产物那份（事实）。不一致就是发布出了问题。
PINNED=$(node -e 'const t=require(process.argv[1]);const s=(t.sources||{})[process.argv[2]]||{};process.stdout.write(String(s.version||""))' "$ROOT_DIR/scripts/component-sources.json" "$TOOL")
[ -z "$PINNED" ] || [ "$PINNED" = "$VER" ] || { echo "::error title=版本号两处不一致::钉值表 component-sources.json 写的是 $PINNED，而本产物那份写的是 $VER"; exit 1; }
[ -f "dist/$ENTRY" ] || { echo "::error title=缺件::dist/$ENTRY 不在（清单入口声明=$ENTRY），先构建"; exit 1; }
STAGE=$(mktemp -d)
cp -a dist/. "$STAGE/"
rm -f "$STAGE"/*.version "$STAGE"/SHA256SUMS $(find "$STAGE" -maxdepth 1 -name '*.zip') 2>/dev/null || true

# 件自描述：包里带全部信息，安装器只读包，不需要外部清单。
#字段来自 component-verify.json（构建期钉值），不手写。
META_SRC="$ROOT_DIR/scripts/component-verify.json"
[ -f "$META_SRC" ] || { echo "::error title=缺元信息表::$META_SRC 不在"; exit 1; }
node "$ROOT_DIR/scripts/recipes/gen-component-meta.js" "$TOOL" "$VER" "$ENTRY" "$META_SRC" "$STAGE/component-meta.json"

find "$STAGE" -exec touch -h -t 198001010000.00 {} +
echo "[package] 打包（zip）：component-meta.json + bin + 其它 prefix 目录"
( cd "$STAGE" && zip -q -r -X -y "$ROOT_DIR/$RAW" . )
rm -rf "$STAGE"
SHA=$(sha256sum "$RAW" | cut -c1-12)
ZIP="dist/component-${TOOL}-${VER}-${SHA}-android-arm64.zip"
mv "$RAW" "$ZIP"
NAME=$(basename "$ZIP")
cd dist
sha256sum "$NAME" >> SHA256SUMS
cd ..
echo "[ok] $ZIP"
sha256sum "$ZIP"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  SUM=$(sha256sum "$ZIP")
  { echo '### component 产物 sha256（C 的清单就钉这个值）'; echo '```'; echo "$SUM"; echo '```'; } >> "$GITHUB_STEP_SUMMARY"
fi
