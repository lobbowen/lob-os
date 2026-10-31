#!/usr/bin/env bash
# 按**字节**解包那一份件（=> 而不按文件名后缀）。
# 缺陷名（C-102）**按名硬取**：=> 而那旧版逐字是 `tar xzf "dist/component-<件>.zip" -C dist`
#   => 而那**文件名后缀**逐字是 .zip 而那命令逐字是 tar xzf、=> 而那**内容**逐字是 tar.gz
#   （=> 而那那一条 tar xzf 在 zip 内容上逐字解不开）。
# => 而按 C-994「判别源必须唯一」：=> 而那判别逐字**调** scripts/toolchain/fmt.sh。
# 用法：bash scripts/toolchain/unpack-piece.sh <件包路径> <解到哪儿>
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$HERE/../.." && pwd)"
FMT_SH="$ROOT_DIR/scripts/toolchain/fmt.sh"
[ -f "$FMT_SH" ] || { echo "::error title=没有判别源::$FMT_SH 不在 —— 容器判别源必须唯一"; exit 1; }
# shellcheck source=fmt.sh
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
    echo "::error:: 处置：核那一份的容器形态（scripts/verify/probe-container-form.sh），=> 而那**不是**按名字猜能解决的。"
    exit 1
    ;;
esac
echo "[ok] $PKG（形态 $FORM，头四字节 $MAGIC）解到 $DEST"
