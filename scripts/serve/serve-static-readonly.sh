#!/usr/bin/env bash

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

for f in native-manifest.json native-manifest.json.sig; do
  if [ -f "$DIR/$f" ]; then
    say "   [有] $f（字节数=$(stat -c%s "$DIR/$f")）"
  else
    say "   [缺] $f（设备侧取不到那一份、按 C-05 报缺）"
  fi
done

say ""
say "   起服务（那**只读**、那 Ctrl-C 停）："
say "   设备侧怎么够到它（那 adb reverse 那一条由 07 执行、设备写入只归 07）："
say "     adb reverse tcp:$PORT tcp:$PORT"
say "     然后在设备上把「更新清单 URL」填成 http://127.0.0.1:$PORT/native-manifest.json"
say ""

exec python3 -m http.server "$PORT" --bind "$HOST" --directory "$DIR"
