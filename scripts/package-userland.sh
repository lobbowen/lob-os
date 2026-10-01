#!/usr/bin/env bash
set -euo pipefail

HERE=$(dirname "$0")
cd "$HERE/.."
ROOT_DIR=$(pwd)

TOOL="${1:?需要工具名}"
VER=$(cat "dist/${TOOL}.version" 2>/dev/null || echo unknown)
RAW="work/.userland-${TOOL}-raw.zip"
ENTRY=$(bash "$ROOT_DIR/scripts/read-userland-entry.sh" "$TOOL")
[ -f "dist/$ENTRY" ] || { echo "::error title=缺件::dist/$ENTRY 不在（清单入口声明=$ENTRY），先构建"; exit 1; }
STAGE=$(mktemp -d)
cp -a dist/. "$STAGE/"
rm -f "$STAGE"/*.version "$STAGE"/SHA256SUMS $(find "$STAGE" -maxdepth 1 -name '*.zip') 2>/dev/null || true
find "$STAGE" -exec touch -h -t 198001010000.00 {} +
echo "[package] 打包（zip）：bin + 其它 prefix 目录"
( cd "$STAGE" && zip -q -r -X -y "$ROOT_DIR/$RAW" . )
rm -rf "$STAGE"
SHA=$(sha256sum "$RAW" | cut -c1-12)
ZIP="dist/userland-${TOOL}-${VER}-${SHA}-android-arm64.zip"
mv "$RAW" "$ZIP"
NAME=$(basename "$ZIP")
cd dist
sha256sum "$NAME" >> SHA256SUMS
cd ..
echo "[ok] $ZIP"
sha256sum "$ZIP"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  SUM=$(sha256sum "$ZIP")
  { echo '### userland 产物 sha256（C 的清单就钉这个值）'; echo '```'; echo "$SUM"; echo '```'; } >> "$GITHUB_STEP_SUMMARY"
fi
