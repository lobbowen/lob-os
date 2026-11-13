#!/usr/bin/env bash

set -euo pipefail
export LC_ALL=C

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"

IMAGE="${IMAGE:-$ROOT/work/kernel-image/Image}"
CPIO="${CPIO:-$ROOT/work/kernel-image/initramfs.cpio}"
OUT="${1:-$ROOT/work/kernel-image/boot.img}"

die() { echo "::error title=$1::$(printf '%s\n' "${@:2}")" >&2; exit 1; }
note() { echo "[kernel-image] $*"; }

[ -f "$CPIO" ] || die "缺归档" "$CPIO 不在（归档那一族逐字要那 newc 那一族，按 C-105 报缺）"
if ! head -c 6 "$CPIO" | grep -q 070701; then
  die "归档不是 newc" "$CPIO 的头逐字不是 070701内核逐字只收那 newc 那一族"
fi
note "归档：newc 那一族（$CPIO，$(stat -c%s "$CPIO") 字节）"

[ -f "$IMAGE" ] || die "缺内核镜像" "$IMAGE 不在（那 Image 那一件逐字还没产出，按 C-105 报缺）"
note "内核镜像：$IMAGE（$(stat -c%s "$IMAGE") 字节）"

mkdir -p "$(dirname "$OUT")"
cat "$IMAGE" "$CPIO" > "$OUT"

CPIO_MAGIC="$(head -c 6 "$CPIO")"
if [ "$CPIO_MAGIC" = "070701" ]; then
  note "判据过：那归档逐字是那 newc 那一族（070701）"
else
  die "那归档逐字**不**对" "$CPIO 的头逐字是 $CPIO_MAGIC、内核逐字**只**收那 newc 那一族"
fi
OUT_MAGIC="$(od -An -tx1 -N2 "$OUT" | tr -d ' ')"
note "装完：$OUT（$(stat -c%s "$OUT") 字节、头 $OUT_MAGIC、归档 $CPIO_MAGIC）"

if [ "$OUT_MAGIC" != "1f8b08" ] && [ "$OUT_MAGIC" = "" ]; then
  die "那镜像逐字**读**不**出**" "$OUT 的头逐字读不出来"
fi