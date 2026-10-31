#!/usr/bin/env bash
# 造一份**底座件清单**（native-manifest.json + .sig）到某目录（M6 正向在设备上那一趟的「那份清单」那一格）。
#
# 为什么要有它（=> 而 Lead 那一裁：「逐字一个只读静态服务 + 逐字一个『造一份 native-manifest.json 到某目录』那一档」）：
#   · M6 正向要一份带 `source=ota` 的底座件清单、=> 而那它逐字来自那 CDN、=> 而那 CDN 逐字 404。
#   · 而那**清单本体**逐字**只有一条**生成路径（=> 而按 C-104 判别源要唯一）：
#     **scripts/publish/publish-native-manifest.js**（=> 而那它逐字是那发布链那一格）。
#     => 而而那本脚本逐字**不**另写一份生成逻辑、=> 而它逐字**调**那一个、
#       => 而它逐字**只**负责「把输出目录准备好 + 把 baseUrl 指到本机 + 把要签的私钥显式说出来」。
#
# 那一格逐字**卡在一个外部条件上**（=> 而那一格逐字归用户与 06）：
#   那清单逐字要由**与 APK 内那枚公钥配对的私钥**签（=> 而那发布脚本逐字自检、=> 而不配对就 exit 1）。
#   => 而而那把私钥逐字不在仓内（=> 而那 `keys/` 逐字不存在、=> 而那 `.gitignore` 逐字忽略它）
#     => 而本脚本逐字**据实报缺**（依 C-05）、=> 而而它逐字**不**编一把、=> 而而它逐字**不**换锚。
#
# 用法：
#   bash scripts/serve/make-native-manifest.sh <输出目录> <私钥路径> [件包zip...]
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

# --- 那私钥那一格（=> 而那一格逐字**据实报缺**、=> 而依 C-05） ---
if [ -z "$KEY" ]; then
  say "::error title=缺私钥::没给私钥路径。"
  say "::notice:: => 而那一格逐字卡在**外部条件**上：=> 而那清单逐字要由与 APK 内那枚公钥**配对**的私钥签，"
  say "::notice:: => 而而那把私钥逐字不在仓内（=> 而那那 keys/ 目录逐字不存在）。"
  say "::notice:: => 而那一格逐字**归用户与 06**、=> 而而本脚本逐字**不**编一把、=> 而而本脚本逐字**不**换锚。"
  exit 1
fi
if [ ! -f "$KEY" ]; then
  say "::error title=私钥读不到::$KEY 不在（=> 而那一格逐字报缺、=> 而不猜）"
  exit 1
fi

# --- 那锚点那一格（=> 而那它是**信任根**、=> 而它逐字决定「这份清单算不算数」） ---
ANCHOR="$ROOT_DIR/container/app/src/main/assets/supply/component-public.pem"
if [ ! -f "$ANCHOR" ]; then
  say "::error title=锚点读不到::$ANCHOR 不在（=> 而那一格逐字报缺）"
  exit 1
fi
say "   信任根：$ANCHOR"
say "   配对核验（=> 而那**配不上**就逐字报缺、=> 而而那不换锚）："
if ! bash "$ROOT_DIR/scripts/verify/verify-ota-anchor.sh" --anchor "$ANCHOR" --private "$KEY" >/tmp/m6-anchor-check.$$ 2>&1; then
  sed 's/^/   /' /tmp/m6-anchor-check.$$
  rm -f /tmp/m6-anchor-check.$$
  say "::error title=私钥与锚点不配对::这一格逐字**卡在外部条件上**、=> 而本脚本逐字**不**换锚、=> 而而那不生成。"
  exit 1
fi
rm -f /tmp/m6-anchor-check.$$
say "   [配对通过]"

# --- 那 baseUrl 那一格（=> 而那它逐字指向**本机**、=> 而而那**不改** channel.json） ---
PORT="${M6_LOCAL_PORT:-8099}"
export NATIVE_BASE_URL="http://127.0.0.1:${PORT}"

mkdir -p "$OUT"

# --- 那生成那一格（=> 而那它逐字**调**那唯一那一条路径、=> 而本脚本逐字不自写生成逻辑） ---
PUB="$ROOT_DIR/scripts/publish/publish-native-manifest.js"
if [ "${#ZIPS[@]}" -eq 0 ]; then
  say "::notice:: 没给件包 zip、=> 而而那发布脚本逐字**只**吃那 pin 记录里的那几件、"
  say "::notice:: => 而而那它逐字**不需要**件包 zip 也逐字能出那一份（=> 而那 zip 是逐件核那 source 的）。"
  say "::notice:: => 而而那**逐件**要 extra 件包时、=> 而那把 zip 逐字当第一个实参传进来。"
  exec node "$PUB" "" "$OUT" "$KEY" canary
fi

exec node "$PUB" "${ZIPS[0]}" "$OUT" "$KEY" canary
