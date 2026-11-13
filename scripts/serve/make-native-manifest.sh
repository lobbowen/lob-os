#!/usr/bin/env bash

set -uo pipefail
ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"

OUT="${1:-}"
KEY="${2:-}"
shift 2 2>/dev/null || true
ZIPS=("$@")

say() { printf '%s\n' "$*"; }

if [ -z "$OUT" ]; then
  say "用法：bash scripts/serve/make-native-manifest.sh <输出目录> <私钥路径> [件包zip...]"
  exit 2
fi

say "== 那底座件清单 =="
say "   输出目录：$OUT"
say "   私钥：${KEY:-（没给）}"

if [ -z "$KEY" ]; then
  say "::error title=缺私钥::没给私钥路径。"
  say "::notice::一格逐字卡在**外部条件**上：清单逐字要由与 APK 内那枚公钥**配对**的私钥签，"
  say "::notice::把私钥逐字不在仓内（那 keys/ 目录逐字不存在）。"
  say "::notice::一格逐字**归用户与 06**、本脚本逐字**不**编一把、本脚本逐字**不**换锚。"
  exit 1
fi
if [ ! -f "$KEY" ]; then
  say "::error title=私钥读不到::$KEY 不在（一格逐字报缺、不猜）"
  exit 1
fi

ANCHOR="$ROOT_DIR/container/app/src/main/assets/supply/component-public.pem"
if [ ! -f "$ANCHOR" ]; then
  say "::error title=锚点读不到::$ANCHOR 不在（一格逐字报缺）"
  exit 1
fi
say "   信任根：$ANCHOR"
say "   配对核验（那**配不上**就逐字报缺、不换锚）："
if ! bash "$ROOT_DIR/scripts/verify/verify-ota-anchor.sh" --anchor "$ANCHOR" --private "$KEY" >/tmp/m6-anchor-check.$$ 2>&1; then
  sed 's/^/   /' /tmp/m6-anchor-check.$$
  rm -f /tmp/m6-anchor-check.$$
  say "::error title=私钥与锚点不配对::这一格逐字**卡在外部条件上**、本脚本逐字**不**换锚、不生成。"
  exit 1
fi
rm -f /tmp/m6-anchor-check.$$
say "   [配对通过]"

PORT="${M6_LOCAL_PORT:-8099}"
export NATIVE_BASE_URL="http://127.0.0.1:${PORT}"

mkdir -p "$OUT"

PUB="$ROOT_DIR/scripts/publish/publish-native-manifest.js"
if [ "${#ZIPS[@]}" -eq 0 ]; then
  say "::notice:: 没给件包 zip、发布脚本逐字**只**吃那 pin 记录里的那几件、"
  say "::notice::它逐字**不需要**件包 zip 也逐字能出那一份（那 zip 是逐件核那 source 的）。"
  say "::notice::那**逐件**要 extra 件包时、把 zip 逐字当第一个实参传进来。"
  exec node "$PUB" "" "$OUT" "$KEY" canary
fi

exec node "$PUB" "${ZIPS[0]}" "$OUT" "$KEY" canary
