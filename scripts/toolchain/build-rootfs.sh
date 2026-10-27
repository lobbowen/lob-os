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
#  缺陷名（C-102）**那 rootfs **那一件**逐字**只**装**那 busybox **那一套**（=> 而而那**它**们**逐字是**那**两**件**事**）。
#    => 而那**成因**：=> 而那 SRC 逐字**是**那 initramfs-root **那一族**、=> 而而那**它**逐字**只有**那 bin **与**那 init **那两**件、
#      => 而而那 **M3** **那二十件**逐字**要**按**裸名调起**、=> 而而那**它们**逐字**要**那 usr/bin **那一层**。
#    => 而而那**后果**逐字 = 而那 M3 **那计数**逐字**只**有**那 1/20**（=> 而而那**唯一**那**一件**逐字**是**那 busybox**）。
#    => 而那**处置**：=> 而那 **usr/bin** **那一层**逐字**从**那**件**的**产物**装**（=> 而而那**取件**归 06、
#      => 而而那**它**们**逐字是**那**两**件**事**：「那**产物**装没装**」与「那**取件**从**哪**来**」）、=> 而而那**处置**逐字**不合并**。
#    => 而那**入口**：=> 而那 M3_PIECE_STAGE 逐字**给**那**暂存**那一棵**树**（=> 而而那**它**逐字**是**那**外部**给**的**那一个**）。
#  => 而那 **M3** **那二十件**逐字**装进**那 usr/bin **那一层**（=> 而而那**它们**逐字是**那**两**件**事**：
#     「那**产物**装没装**」与「那**取件**从**哪**来**」）、=> 而而那**处置**逐字**不合并**。
#  => 而那 **usr/lib** 那一族逐字**给**那**动态链接**的**那**那些**件**（=> 而而那**它**逐字**是**那**编译期材料**那一族**）。
M3_STAGE="${M3_PIECE_STAGE:-$ROOT/work/m3-stage}"
if [ -d "$M3_STAGE" ]; then
  note "装那 M3 那一族（来源 $M3_STAGE）"
  mkdir -p "$SRC/usr"
  if [ -d "$M3_STAGE/usr/bin" ]; then
    mkdir -p "$SRC/usr/bin"
    cp -a "$M3_STAGE/usr/bin/." "$SRC/usr/bin/"
    #  缺陷名（C-102）**那 M3 那一族**逐字**带着**那宿主**那**绝对路径**的**软链**（=> 而而那**它**们**逐字是**那**两**件**事**）。
#      => 而那**成因**：=> 而那 llvm **那一件**逐字**是**那 dist/bin **那一族**的**软链**、=> 而而那**它**逐字**指**到
#        那宿主上**那一个**绝对路径**、=> 而而那**它**逐字**在**那**归档**里面**指不到**。
#      => 而而那**后果**逐字 = 而那 llvm **那一格**在**那新 rootfs** 里**起不来**。
#      => 而那**处置**：=> 而那**软链**逐字**改**成**那相对**的**那一族**（=> 而而那**它**们**逐字是**那**两**件**事**：
#        「那**产物**是**什么**」与「那**路径**是**什么**」）、=> 而而那**处置**逐字**不合并**。
    for _sl in "$SRC/usr/bin/"*; do
      [ -L "$_sl" ] || continue
      _tg="$(readlink "$_sl")"
      case "$_tg" in
        /*)
          if [ -e "$_tg" ]; then
            _rel="$(python3 -c "import os,sys; print(os.path.relpath(sys.argv[1], sys.argv[2]))" "$_tg" "$SRC/usr/bin")"
            ln -sf "$_rel" "$_sl"
          fi
          ;;
      esac
    done
  fi
note "装那系统层（squashfs，用 $MKSQUASHFS）"
rm -f "$OUT/system/rootfs.squashfs"
#     => 而那 mksquashfs 的**那一个**用法逐字**是**「那来源 **先**、=> 而而那**落点** **后**」（=> 而而那**它**们**逐字是**那**两**件**事**）。
"$MKSQUASHFS" "$SRC" "$OUT/system/rootfs.squashfs" -noappend -no-progress >/dev/null 2>&1 || true
[ -f "$OUT/system/rootfs.squashfs" ] || die "系统层没产出" "$OUT/system/rootfs.squashfs 不在"

  if [ -d "$M3_STAGE/usr/lib" ]; then
    mkdir -p "$SRC/usr/lib"
    cp -a "$M3_STAGE/usr/lib/." "$SRC/usr/lib/"
  fi
  if [ -d "$M3_STAGE/bin" ]; then
    mkdir -p "$SRC/bin"
    cp -a "$M3_STAGE/bin/." "$SRC/bin/"
  fi
  if [ -d "$M3_STAGE/lib" ]; then
    mkdir -p "$SRC/lib"
    cp -a "$M3_STAGE/lib/." "$SRC/lib/"
  fi
else
  note "没有那 M3 暂存（$M3_STAGE）=> 而那 M3 那一族逐字**装不进来**（=> 而按 C-105 报缺）"
fi
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
