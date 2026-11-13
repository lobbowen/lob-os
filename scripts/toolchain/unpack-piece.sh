#!/usr/bin/env bash

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$HERE/../.." && pwd)"
FMT_SH="$ROOT_DIR/scripts/toolchain/fmt.sh"
[ -f "$FMT_SH" ] || { echo "::error title=没有判别源::$FMT_SH 不在 —— 容器判别源必须唯一"; exit 1; }

. "$FMT_SH"

PKG="${1:?需要件包路径}"
DEST="${2:?需要解到哪儿}"
[ -f "$PKG" ] || { echo "::error title=件包不在::$PKG"; exit 1; }

FORM="$(fmt_of "$PKG")"
MAGIC="$(fmt_magic "$PKG")"
mkdir -p "$DEST"
case "$FORM" in
  gzip)
    tar xzf "$PKG" -C "$DEST"
    ;;
  zip|zip64)
    unzip -q -o "$PKG" -d "$DEST"
    ;;
  *)
    echo "::error title=容器形态不认::$PKG 逐字是那 $FORM（头四字节 $MAGIC）"
    echo "::error:: 处置：核那一份的容器形态（scripts/verify/probe-container-form.sh），那**不是**按名字猜能解决的。"
    exit 1
    ;;
esac
echo "[ok] $PKG（形态 $FORM，头四字节 $MAGIC）解到 $DEST"
