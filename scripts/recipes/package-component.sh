#!/usr/bin/env bash
set -euo pipefail

HERE=$(dirname "$0")
cd "$HERE/../.."
ROOT_DIR=$(pwd)

TOOL="${1:?需要工具名}"

VER=$(cat "dist/${TOOL}.version" 2>/dev/null || true)

if [ -z "$VER" ]; then
  echo "::notice:: 那 dist/${TOOL}.version 不在（那**复用**命中了、编译逐字被跳过了）"
  echo "::notice::版本号逐字**改从**那钉值表逐字取（它是那件自己的版本、**不**是「本轮编的」那个事实）"
  VER="$(bash "$ROOT_DIR/scripts/toolchain/fetch-pinned.sh" --src-version "$TOOL" 2>/dev/null || true)"
  if [ -n "$VER" ]; then
    printf '%s' "$VER" > "dist/${TOOL}.version"
    echo "::notice::版本号逐字 = $VER（从那钉值表逐字取到、它逐字**写回**了那一份）"
  fi
fi
[ -n "$VER" ] || { echo "::error title=缺版本号::dist/${TOOL}.version 不在或为空 —— 不编值（这一版没有版本号就无法判断哪个包是本轮编的）；先跑产出它的 recipe"; exit 1; }
RAW="work/.component-${TOOL}-raw.zip"
mkdir -p "$(dirname "$RAW")"
ENTRY=$(bash "$ROOT_DIR/scripts/registry/read-component-entry.sh" "$TOOL")

PINNED=$(node -e 'const t=require(process.argv[1]);const s=(t.sources||{})[process.argv[2]]||{};process.stdout.write(String(s.version||""))' "$ROOT_DIR/scripts/component-sources.json" "$TOOL")
[ -z "$PINNED" ] || [ "$PINNED" = "$VER" ] || { echo "::error title=版本号两处不一致::钉值表 component-sources.json 写的是 $PINNED，而本产物那份写的是 $VER"; exit 1; }
[ -f "dist/$ENTRY" ] || { echo "::error title=缺件::dist/$ENTRY 不在（清单入口声明=$ENTRY），先构建"; exit 1; }
STAGE=$(mktemp -d)
cp -a dist/. "$STAGE/"
rm -f "$STAGE"/*.version "$STAGE"/SHA256SUMS $(find "$STAGE" -maxdepth 1 -name '*.zip') 2>/dev/null || true

BUILD_SHA="${GITHUB_SHA:-local}"
BUILD_RUN="${GITHUB_RUN_ID:-local}"
node "$ROOT_DIR/scripts/recipes/gen-component-meta.js" \
  "$TOOL" "$VER" "$STAGE/component-meta.json" --payload "$STAGE" \
  --build-sha="$BUILD_SHA" --build-run="$BUILD_RUN" \
  || { echo "::error title=$TOOL 说明生成失败"; exit 1; }

source "$ROOT_DIR/scripts/toolchain/elf-form.sh"
elf_write_shape "$TOOL" "$ENTRY" "$STAGE" "$STAGE/piece-shape.json" "$VER" bin lib \
  || { echo "::error title=$TOOL 形态算不出来::构建期必须能判形态（readelf 缺失）"; exit 1; }

find "$STAGE" -exec touch -h -t 198001010000.00 {} +
echo "[package] 打包（zip）：component-meta.json + bin + 其它 prefix 目录"
( cd "$STAGE" && zip -q -r -X -y "$ROOT_DIR/$RAW" . )
rm -rf "$STAGE"
SHA=$(sha256sum "$RAW" | cut -c1-12)

TARGET_SEGMENT="${TARGET_SEGMENT:-android-arm64}"

if [ -n "${PC_TARGET_SEGMENT:-}" ]; then
  _decl_seg="$(bash "$ROOT_DIR/scripts/toolchain/pc-target.sh" product-segment 2>/dev/null || true)"
  if [ -n "$_decl_seg" ] && [ "$_decl_seg" != "$TARGET_SEGMENT" ]; then
    echo "::error title=那产物名那目标段与那声明不等::产物名那目标段=$TARGET_SEGMENT、声明=$_decl_seg"
    echo "::error::一档逐字是「承载形态」、按 C-05 报缺、那**不**猜"
    exit 1
  fi
fi
ZIP="dist/component-${TOOL}-${VER}-${SHA}-${TARGET_SEGMENT}.zip"
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
