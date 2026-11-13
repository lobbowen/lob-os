#!/usr/bin/env bash
# 一个**只读**静态文件服务（ 它是 M6 正向在设备上那一趟的**本机服务**那一格）。
#
# 为什么要有它（ 一格逐字**归 05**、 Lead 那一裁）：
#   · M6 正向要一份带 `source=ota` 的**底座件清单**（ 它逐字是 native-manifest.json、
# 它逐字来自那 CDN、 CDN 逐字 404）。
#   · 而那**走 CDN 那一路**逐字动不了（ `channel.json` 逐字是用户的锚点、 **不许改**）。
#   · 而那**旁路**逐字存在（ `PieceUpdater.kt:91` 逐字
#     `val url = if (manifestUrl.startsWith("http")) manifestUrl else "$dir/$nativeName"`
# `manifestUrl` 逐字只要以 `http` 开头就**顶掉**那 `baseUrl` 那一格）。
# 旁路要一个**本机**的 http 服务、 设备侧要够得到它
# cleartext 策略（container/app/src/main/res/xml/network_security_config.xml）
#        逐字**只**放行 `127.0.0.1` 与 `localhost`
# **唯一**不留明文局域网口子的形态逐字是「**本机起服务 + adb reverse**」。
#
# 那一族逐字治的是「**设备够不到那份清单**」这一格、 **不是**「那份清单产不出来」那一格
#   （ 产出来那一格归 06 与用户： 把配对私钥逐字不在仓内）。
#
# 它逐字**只读**： 它逐字只**发**那目录里的文件、 它逐字**不改**任何东西、
# 它逐字**不**写那目录、 它逐字**不**改 `channel.json`。
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
PORT="${1:-8099}"
DIR="${2:-$ROOT_DIR/release}"
HOST="127.0.0.1"

say() { printf '%s\n' "$*"; }

say "== 那只读静态服务 =="
say "   目录：$DIR"
say "   地址：http://$HOST:$PORT/"

if [ ! -d "$DIR" ]; then
  say "::error title=目录不存在::$DIR 不在（一格逐字报缺、不猜）"
  exit 1
fi

# **先核**那两份文件逐字在不在（ 设备侧逐字要那**两份**：
#     清单本体与那签名、 `PieceUpdater.kt:105` 逐字 `val sigUrl = url + ".sig"`）。
for f in native-manifest.json native-manifest.json.sig; do
  if [ -f "$DIR/$f" ]; then
    say "   [有] $f（字节数=$(stat -c%s "$DIR/$f")）"
  else
    say "   [缺] $f（设备侧取不到那一份、按 C-05 报缺）"
  fi
done

say ""
say "   起服务（**只读**、 Ctrl-C 停）："
say "   设备侧怎么够到它（ adb reverse 那一条由 07 执行、设备写入只归 07）："
say "     adb reverse tcp:$PORT tcp:$PORT"
say "     然后在设备上把「更新清单 URL」填成 http://127.0.0.1:$PORT/native-manifest.json"
say ""

# **只读**服务： python 那一档逐字是**静态**的、 它逐字**不**执行任何东西。
exec python3 -m http.server "$PORT" --bind "$HOST" --directory "$DIR"
