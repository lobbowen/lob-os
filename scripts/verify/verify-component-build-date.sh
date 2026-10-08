#!/usr/bin/env bash
set -euo pipefail

HERE=$(dirname "$0")
ROOT_DIR=$(cd "$HERE/../.." && pwd)
SRC="${1:?用法: $0 <openssl 源码目录>}"

EPOCH=$(bash "$ROOT_DIR/scripts/toolchain/fetch-pinned.sh" --time-base)
EXPECT="built on: $(LC_ALL=C date -u -d "@$EPOCH" '+%a %b %e %H:%M:%S %Y') UTC"

HEAD=$(find "$SRC" -name buildinf.h 2>/dev/null | head -n1 || true)
[ -n "$HEAD" ] || { echo "::error title=找不到构建信息头::$SRC 下没有生成出的 buildinf.h —— 上游换了机制，本判据成了空尺子，必须重新定位而不是放过"; exit 1; }

LINE=$(grep -m1 'built on:' "$HEAD" || true)
[ -n "$LINE" ] || { echo "::error title=构建信息头里没有日期行::$HEAD 抓到了却 grep 不到 built on:，夹具假设失效，不许放过"; exit 1; }
echo "[build-date] 头文件=$HEAD"
echo "[build-date] 实际=$LINE"
echo "[build-date] 期望=$EXPECT"
printf '%s' "$LINE" | grep -qF "$EXPECT" || {
  echo "::error title=件字节随墙钟动::构建时间没钉住（buildTimeEpoch=$EPOCH）。这一轮的 curl/git 会与上一轮同名不同 sha。"
  exit 1
}
echo "[ok] 构建时间钉在 $EXPECT"
