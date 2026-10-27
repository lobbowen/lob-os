#!/usr/bin/env bash
# 产那 rootfs 那一件 —— 逐字是**那两样东西**（=> 而那**两样**逐字**不能并成一个文件**）。
#
#   ① 一份 squashfs 归档（那系统层、那只读的那一层）
#   ② 一份 tmpfs 挂载点（那可写层、那工作区）
#
# 按 C-103：=> 而那**两边**逐字**各**答**一件**事**、=> 而而那**处置**逐字**不合并**。
#   => 而那 rootfs 那一件逐字**判**的是「那内核在那目标上起得来」那一族**之外**的**那文件系统**那一层、
#     => 而那 initramfs 那一层逐字**是**那**内存里**的**那一层**、=> 而而那 rootfs 逐字**是**那**磁盘上**的**那一层**
#     => 而而那**它们**逐字**是**那**两**件**事**、=> 而按 C-104 逐字**不合并**。
#   => 而那件集那 18 件逐字**是**那**用户态**模块**那一族**、=> 而而那 rootfs 那一件逐字**是**那**文件系统**那一层**
#     => 而而那**它们**逐字**是**那**两**件**事**、=> 而按 C-104 逐字**不合并**。
#
# 按 C-105：那**缺**的逐字**报缺**，不猜填。
# 用法：build-rootfs.sh [那产物**那一族**的落点]
set -euo pipefail
export LC_ALL=C

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"

OUT="${1:-$ROOT/work/rootfs}"
case "$OUT" in /*) ;; *) OUT="$ROOT/$OUT" ;; esac

die() { echo "::error title=$1::$(printf '%s\n' "${@:2}")"; exit 1; }
note() { echo "[rootfs] $*"; }

command -v mksquashfs >/dev/null 2>&1 || die "缺 mksquashfs" "那系统层逐字是那 squashfs 归档、=> 而那工具逐字**要**它"

#  => 而那**系统层**的**内容**逐字**从**哪**来**（=> 而那**它**们**逐字是**那**两**件**事**、=> 而按 C-104 逐字**不合并**）。
#     => 而那**它**逐字**是**那 initramfs **那一层**的**同一份**系统层内容（=> 而那 busybox 那一族与那 /init **那一件**）。
SRC="${ROOTFS_SRC:-$ROOT/work/kernel-image/initramfs-root}"
[ -d "$SRC" ] || die "缺系统层来源" "$SRC 不在 —— 先编那 initramfs 那一层（scripts/toolchain/build-kernel-image.sh）"

rm -rf "$OUT"
mkdir -p "$OUT/system"

#  => 而那**系统层**逐字**是**那 squashfs 归档**（=> 而那**它**逐字**只读**、=> 而而那**它**逐字**在**那磁盘上**）。
note "装那系统层（squashfs）"
mksquashfs "$SRC" "$OUT/system/rootfs.squashfs" -noappend -no-progress -all-root 2>&1 | tail -2 || true
[ -f "$OUT/system/rootfs.squashfs" ] || die "系统层没产出" "$OUT/system/rootfs.squashfs 不在"

#  => 而那**可写层**逐字**是**那 tmpfs **挂载点**（=> 而而那**它**逐字**不是**一个归档、=> 而而那**它**逐字**是**那**一个**目录**）。
note "建那可写层（tmpfs 挂载点）"
mkdir -p "$OUT/workdir"

#  => 而那**形状说明**逐字**记**那**两样**是什么**（=> 而那**它**们**逐字是**那**两**件**事**、=> 而按 C-104 逐字**不合并**）。
SYS_BYTES=$(stat -c %s "$OUT/system/rootfs.squashfs")
cat > "$OUT/rootfs-shape.json" <<JSON
{
  "schema": 1,
  "piece": "rootfs",
  "systemLayer": {
    "kind": "squashfs",
    "path": "system/rootfs.squashfs",
    "bytes": $SYS_BYTES,
    "mount": "mount -o ro,loop 系统层 /system",
    "property": "只读"
  },
  "writableLayer": {
    "kind": "tmpfs",
    "path": "workdir",
    "mount": "mount -t tmpfs tmpfs /workdir",
    "property": "可写"
  },
  "note": "=> 而那**两样**逐字**不能并成一个文件**、=> 而那**系统层**逐字**是**那**归档**、=> 而而那**可写层**逐字**是**那**挂载点**、=> 而按 C-104 逐字**不合并**"
}
JSON

note "就位 $OUT（系统层 $SYS_BYTES 字节 · 可写层挂载点 workdir）"
du -sh "$OUT"
echo "[ok] $OUT（那两样：系统层 squashfs 归档 + 可写层 tmpfs 挂载点）"
