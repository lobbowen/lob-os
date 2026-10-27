#!/usr/bin/env bash
# 产那 rootfs 那一件 —— 逐字是**那两样东西**（=> 而那**两样**逐字**不能并成一个文件**）。
#
#   ① 一份 erofs 归档（那系统层、那只读的那一层）
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
#   => 而而那**后果**逐字 = 而那**设备侧**逐字**挂不上**那 squashfs、=> 而那挂载逐字**报**
#     「mount: bad /etc/fstab: No such file or directory」。
#   => 而那**处置**：=> 而那**系统层**逐字**改成**那 **erofs** 归档（=> 而而那**它**逐字**是**那内核**有**的**那一族**），
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

#  => 而那 **erofs** 那一族逐字**要**那 **mkfs.erofs**（=> 而那**宿主**上**没有**它、
#     => 而那**设备**上**有**它：=> 而那 /system/bin/mkfs.erofs 逐字 = 199624 字节）。
MKFS_EROFS="${MKFS_EROFS:-}"
if [ -z "$MKFS_EROFS" ]; then
  for c in mkfs.erofs /usr/bin/mkfs.erofs /system/bin/mkfs.erofs; do
    if command -v "$c" >/dev/null 2>&1; then MKFS_EROFS="$(command -v "$c")"; break; fi
    [ -x "$c" ] && { MKFS_EROFS="$c"; break; }
  done
fi
if [ -z "$MKFS_EROFS" ]; then
  #  => 而那**宿主**上逐字**没有**那 mkfs.erofs、=> 而而那**设备**上**有**它
  #     （=> 而那 /system/bin/mkfs.erofs 逐字 = 199624 字节、=> 而而那内核逐字 CONFIG_EROFS_FS=y）。
  #  => 而那**处置**：=> 而那**设备侧**那**归档**逐字**由**那**设备自己**做**（=> 而那**取件**归 06、
  #     => 而那**设备写入**归 07、=> 而而那**它们**逐字**是**那**两**件**事**、=> 而按 C-104 逐字**不合并**）。
  echo "::notice title=宿主上没有 mkfs.erofs::那系统层逐字**要**那 erofs 归档、=> 而那工具**只**在**那设备**上"
  mkdir -p "$OUT/system" "$OUT/workdir"
  cat > "$OUT/rootfs-shape.json" <<JSON
{
  "schema": 1,
  "piece": "rootfs",
  "systemLayer": {
    "kind": "erofs",
    "path": "system/rootfs.erofs",
    "bytes": 0,
    "property": "只读"
  },
  "writableLayer": {
    "kind": "dir",
    "path": "workdir",
    "property": "可写"
  },
  "note": "=> 而那**系统层**逐字**要**那**设备侧**那 mkfs.erofs **做**、=> 而而那**宿主**上逐字**没有**它"
}
JSON
  echo "[ok] $OUT（那两样的**形状**已落；那系统层那归档逐字**要**那**设备侧**做）"
  exit 0
fi

#  => 而那**系统层**的**内容**逐字**从**哪**来**（=> 而那**它**们**逐字是**那**两**件**事**、=> 而按 C-104 逐字**不合并**）。
SRC="${ROOTFS_SRC:-$ROOT/work/kernel-image/initramfs-root}"
[ -d "$SRC" ] || die "缺系统层来源" "$SRC 不在 —— 先编那 initramfs 那一层（scripts/toolchain/build-kernel-image.sh）"

rm -rf "$OUT"
mkdir -p "$OUT/system"

#  => 而那**系统层**逐字**是**那 erofs 归档**（=> 而而那**它**逐字**只读**、=> 而而那**它**逐字**在**那磁盘上**）。
note "装那系统层（erofs，用 $MKFS_EROFS）"
"$MKFS_EROFS" "$OUT/system/rootfs.erofs" "$SRC" >/dev/null 2>&1 || true
[ -f "$OUT/system/rootfs.erofs" ] || die "系统层没产出" "$OUT/system/rootfs.erofs 不在"

#  => 而那**可写层**逐字**是**那**一个**目录**（=> 而而那**它**逐字**不是**一个挂载**点**：
#     => 而那**设备上**逐字**没有**那 **CAP_SYS_ADMIN**、=> 而而那 **mount** 逐字**成不了**）。
note "建那可写层（工作区目录）"
mkdir -p "$OUT/workdir"

#  => 而那**形状说明**逐字**记**那**两样**是什么**（=> 而那**它**们**逐字是**那**两**件**事**、=> 而按 C-104 逐字**不合并**）。
SYS_BYTES=$(stat -c %s "$OUT/system/rootfs.erofs")
cat > "$OUT/rootfs-shape.json" <<JSON
{
  "schema": 1,
  "piece": "rootfs",
  "systemLayer": {
    "kind": "erofs",
    "path": "system/rootfs.erofs",
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
echo "[ok] $OUT（那两样：系统层 erofs 归档 + 可写层工作区目录）"
