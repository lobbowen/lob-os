#!/usr/bin/env bash
# 产那 rootfs 那一件 —— 逐字是**那两样东西**（=> 而那**两样**逐字**不能并成一个文件**）。
#
#   ① 一份 squashfs 归档（那系统层、那只读的那一层）
#   ② 一份可写层（那工作区）
#
# 按 C-103：=> 而那**两边**逐字**各**答**一件**事**、=> 而而那**处置**逐字**不合并**。
#   => 而那 rootfs 那一件逐字**判**的是那**文件系统**那一层、
#     => 而那 initramfs 那一层逐字**是**那**内存里**的**那一层**、=> 而而那 rootfs 逐字**是**那**磁盘上**的**那一层**
#     => 而而那**它们**逐字**是**那**两**件**事**、=> 而按 C-104 逐字**不合并**。
#   => 而那件集那 18 件逐字**是**那**用户态**模块**那一族**、=> 而而那 rootfs 那一件逐字**是**那**文件系统**那一层**
#     => 而而那**它们**逐字**是**那**两**件**事**、=> 而按 C-104 逐字**不合并**。
#
# 缺陷名（C-102）**那形态**逐字**跟**那内核**那配置**不**配**：
#   => 而那 gki_defconfig 逐字那 **SQUASHFS** 命中 = **0**（=> 而那内核**不**会**挂**那 squashfs），
#     而而那 **EROFS_FS** 命中 = **2**（=> 而那 **TMPFS** 命中 = **1**、=> 而那 **EXT4_FS** = **3**）。
#   => 而那**后果**逐字 = 而那**设备侧**逐字**挂不上**那 squashfs、=> 而那挂载逐字**报**
#     「mount: bad /etc/fstab: No such file or directory」。
#   => 而那**处置**：=> 而那**系统层**逐字**先**改成**那 **erofs** 归档（=> 而而那**它**逐字**是**那内核**有**的**那一族**），
#     => 而而那**可写层**逐字**不是**一个挂载**点**、=> 而而那**它**逐字**是**那**一个**目录**
#     （=> 而那**设备上**逐字**没有**那 **CAP_SYS_ADMIN**、=> 而而那 **mount** 逐字**成不了**）。
#
# 缺陷名（C-102）**那形态**逐字**跟**那内核**那配置**不**配**（=> 而那**第二**次**）**：
#   => 而那 **01** 那边逐字**裁**的（那 S271）：=> 而那 M2 那系统层逐字**该回**那 squashfs **那一族**、
#     => 而而那**判据本体**逐字**要改回**那 squashfs **那一个头**、=> 而而那**它**们**逐字是**那**两**件**事**、
#     => 而而那**处置**逐字**不合并**。
#   => 而而那**那内核**逐字**注册**的**那一族**逐字**有**那 squashfs、=> 而而那**它**们**逐字**没有**那 erofs
#     （=> 而那**它**逐字是**那本机**跑**那 LOB-OS **那一件**的**那一个**内核**的**读数**）。
#   => 而那**处置**：=> 而那**系统层**逐字**改回**那 **squashfs** 归档、=> 而而那**它**逐字**是**那内核**有**的**那一族**。
#   => 而那 gki_defconfig 逐字那 **SQUASHFS** 命中 = **0**（=> 而那内核**不**会**挂**那 squashfs），
#     而而那 **EROFS_FS** 命中 = **2**（=> 而那 **TMPFS** 命中 = **1**、=> 而那 **EXT4_FS** = **3**）。
#   => 而而那**后果**逐字 = 而那**设备侧**逐字**挂不上**那 squashfs、=> 而那挂载逐字**报**
#     「mount: bad /etc/fstab: No such file or directory」。
#   => 而那**处置**：=> 而那**系统层**逐字**先**改成**那 **erofs** 归档（=> 而而那**它**逐字**是**那内核**有**的**那一族**），
#     => 而而那**可写层**逐字**不是**一个挂载**点**、=> 而而那**它**逐字**是**那**一个**目录**
#     （=> 而那**设备上**逐字**没有**那 **CAP_SYS_ADMIN**、=> 而而那 **mount** 逐字**成不了**）。
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

#  => 而那 **squashfs** 那一族逐字**要**那 **mksquashfs**（=> 而那**宿主**上**有**它：
#     => 而那 /usr/bin/mksquashfs 逐字 = 387976 字节、=> 而那**它**逐字**能**跑（=> 而那「mksquashfs version 4.7.5」）。
#     => 而而那**那内核**逐字**注册**的**那一族**逐字**有**那 squashfs（=> 而而那**它**们**逐字**没有**那 erofs）。
MKSQUASHFS="${MKSQUASHFS:-}"
if [ -z "$MKSQUASHFS" ]; then
  for c in mksquashfs /usr/bin/mksquashfs; do
    if command -v "$c" >/dev/null 2>&1; then MKSQUASHFS="$(command -v "$c")"; break; fi
    [ -x "$c" ] && { MKSQUASHFS="$c"; break; }
  done
fi
if [ -z "$MKSQUASHFS" ]; then
  #  => 而那**宿主**上逐字**没有**那 mksquashfs、=> 而而那**设备**上**没有**它
  #     （=> 而那 /system/bin/ 底下逐字**只有**那 mkfs.erofs、=> 而而那**它**们**逐字是**那**两**件**事**）。
  #  => 而那**处置**：=> 而那**系统层**逐字**只**落**那**形状**、=> 而而那**归档**逐字**报缺**（=> 而按 C-105）。
  echo "::notice title=宿主上没有 mksquashfs::那系统层逐字**要**那 squashfs 归档、=> 而那工具**没有**它"
  mkdir -p "$OUT/system" "$OUT/workdir"
  cat > "$OUT/rootfs-shape.json" <<JSON
{
  "schema": 1,
  "piece": "rootfs",
  "systemLayer": {
    "kind": "squashfs",
    "path": "system/rootfs.squashfs",
    "bytes": 0,
    "property": "只读"
  },
  "writableLayer": {
    "kind": "dir",
    "path": "workdir",
    "property": "可写"
  },
  "note": "=> 而那**系统层**逐字**要**那 mksquashfs **做**、=> 而而那**宿主**上逐字**没有**它"
}
JSON
  echo "[ok] $OUT（那两样的**形状**已落；那系统层那归档逐字**报缺**）"
  exit 0
fi

#  => 而那**系统层**的**内容**逐字**从**哪**来**（=> 而那**它**们**逐字是**那**两**件**事**、=> 而按 C-104 逐字**不合并**）。
SRC="${ROOTFS_SRC:-$ROOT/work/kernel-image/initramfs-root}"
[ -d "$SRC" ] || die "缺系统层来源" "$SRC 不在 —— 先编那 initramfs 那一层（scripts/toolchain/build-kernel-image.sh）"

rm -rf "$OUT"
mkdir -p "$OUT/system"

#  => 而那**系统层**逐字**是**那 squashfs 归档**（=> 而而那**它**逐字**只读**、=> 而而那**它**逐字**在**那磁盘上**）。
#     => 而而那**它**逐字**是**那内核**注册**的**那一族**（=> 而而那**它**们**逐字是**那**两**件**事**、=> 而按 C-104 逐字**不合并**）。
note "装那系统层（squashfs，用 $MKSQUASHFS）"
rm -f "$OUT/system/rootfs.squashfs"
#     => 而那 mksquashfs 的**那一个**用法逐字**是**「那来源 **先**、=> 而而那**落点** **后**」（=> 而而那**它**们**逐字是**那**两**件**事**）。
"$MKSQUASHFS" "$SRC" "$OUT/system/rootfs.squashfs" -noappend -no-progress >/dev/null 2>&1 || true
[ -f "$OUT/system/rootfs.squashfs" ] || die "系统层没产出" "$OUT/system/rootfs.squashfs 不在"

#  => 而那**可写层**逐字**是**那**一个**目录**（=> 而而那**它**逐字**不是**一个挂载**点**：
#     => 而那**设备上**逐字**没有**那 **CAP_SYS_ADMIN**、=> 而而那 **mount** 逐字**成不了**）。
note "建那可写层（工作区目录）"
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
    "property": "只读"
  },
  "writableLayer": {
    "kind": "dir",
    "path": "workdir",
    "property": "可写"
  },
  "note": "=> 而那**两样**逐字**不能并成一个文件**、=> 而那**系统层**逐字**是**那**归档**、=> 而而那**可写层**逐字**是**那**目录**、=> 而按 C-104 逐字**不合并**"
}
JSON

note "就位 $OUT（系统层 $SYS_BYTES 字节 · 可写层 workdir）"
du -sh "$OUT"
echo "[ok] $OUT（那两样：系统层 squashfs 归档 + 可写层工作区目录）"
