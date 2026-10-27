#!/usr/bin/env bash
# 编那内核镜像那一件 —— 那一件逐字要带那 tree 与那 commit 那两个数，
# 而而它逐字要含那 initramfs 那一层与那 /init，
# 而而那归档那一族逐字不能当那钉值来源。
#
# 按 C-103：那一族逐字有两边。
#   => 那一边 = 「那内核镜像**那一件**的**内容**」（=> 而那它逐字**要**那 tree 与那 commit、
#   => 而而那另一边 = 「那**钉值**从**哪**来**」（=> 而那归档**那一族**逐字**每次**换**那字节**、
#   => 而而那**处置**逐字**不合并**。
#
# 按 C-104：那**内容**那一族逐字**只有**一条路（=> 而这一支**就是**它**）。
# 按 C-105：那**缺**的逐字**报缺**，不猜填。
set -euo pipefail
export LC_ALL=C

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"

#  => 而那**落点**逐字**要**能**指**到**那**仓外**那**内核源码**（=> 而那它逐字**不**在**那仓内**）。
KERNEL_SRC="${KERNEL_SRC:-$ROOT/work/kernel-src}"
#  => 而那**静态**的**那一个** busybox 逐字**要**在**那仓外**（=> 而那它逐字**是**那 initramfs **那一层**的**内容**）。
BUSYBOX_BIN="${BUSYBOX_BIN:-$ROOT/work/busybox/out/bin/libbusybox.so}"
OUT="${1:-$ROOT/work/kernel-image}"
JOBS="${JOBS:-$(nproc)}"

die() { echo "::error title=$1::$(printf '%s\n' "${@:2}")" >&2; exit 1; }
note() { echo "[kernel-image] $*"; }

#  => 而那 **tree** 与那 **commit** **那两**个**数** 逐字**是**那**钉值**（=> 而那**它**们**不**是**那**归档**的**字节**）。
[ -d "$KERNEL_SRC" ] || die "缺内核源码" "$KERNEL_SRC 不在（=> 而那内核源码逐字在仓外，=> 而按 C-105 报缺）"
[ -f "$BUSYBOX_BIN" ] || die "缺静态 busybox" "$BUSYBOX_BIN 不在（=> 而那 initramfs 那一层逐字要那静态的那一个，=> 而按 C-105 报缺）"

COMMIT="$(git -C "$KERNEL_SRC" rev-parse HEAD)"
TREE="$(git -C "$KERNEL_SRC" rev-parse "HEAD^{tree}")"
note "钉值：commit=$COMMIT tree=$TREE"

#  => 而那**版本**逐字**从**那**内核**自己**读**（=> 而那**它**们**逐字是**那**两**件**事**）。
KVER="$(sed -n 's/^VERSION = //p; s/^PATCHLEVEL = //p; s/^SUBLEVEL = //p' "$KERNEL_SRC/Makefile" | paste -sd. -)"
[ -n "$KVER" ] || die "读不到版本" "$KERNEL_SRC/Makefile 里读不到 VERSION/PATCHLEVEL"
note "版本：$KVER"

#  => 而那**归档**那一族逐字**不能**当**那钉值来源**（=> 而那**它**逐字**每次**换**那字节**、
#     => 而而那**钉值**逐字**用**那 tree 对象**与**那 commit）。
note "归档那一族不作钉值来源（=> 而那 tree 对象与那 commit 才是那一族）"

#  => 而那 **initramfs** **那一层** 逐字**要**那**静态**的**那一个** busybox **与**那 /init。
IRFS_STAGE="$OUT/initramfs-root"
rm -rf "$IRFS_STAGE" && mkdir -p "$IRFS_STAGE/bin"
cp "$BUSYBOX_BIN" "$IRFS_STAGE/bin/busybox"
chmod 0755 "$IRFS_STAGE/bin/busybox"
cp "$ROOT/scripts/initramfs/lobos-init" "$IRFS_STAGE/init"
chmod 0755 "$IRFS_STAGE/init"
note "initramfs 那一层：bin/busybox（静态）+ init"

mkdir -p "$OUT"
python3 "$ROOT/scripts/initramfs/mkinitramfs.py" "$IRFS_STAGE" "$OUT/initramfs.cpio"

#  => 而那**判据**：=> 而那**静态**的**那一个**逐字**不**能**有**那 PT_INTERP**（=> 而那 CMake 逐字**要**它**不**要**那 linker64**）。
if command -v readelf >/dev/null 2>&1; then
  if readelf -W -l "$IRFS_STAGE/bin/busybox" 2>/dev/null | grep -q INTERP; then
    die "那静态的判定没过" "$IRFS_STAGE/bin/busybox 有 PT_INTERP => 而那它逐字是动态的那一族、=> 而那 initramfs 那一层逐字要那静态的那一个"
  fi
  note "静态判定：有那静态的那一个（无 PT_INTERP）"
else
  note "静态判定：跳过（=> 而那 readelf 不在，=> 而按 C-105 报缺）"
fi

#  => 而那**设备树** 逐字**要**那**机型**自己的**那一份**（=> 而而那**它**逐字**不**在**那 GKI **那一族**里面**）。
DTS="${DTS:-}"
if [ -z "$DTS" ]; then
  die "缺设备树" "那设备树逐字要那机型自己的那一份、=> 而那它逐字不在那 GKI 那一族里面、=> 而按 C-105 报缺（=> 而那 DTB 逐字要那外部给的）"
fi
[ -f "$DTS" ] || die "缺设备树" "$DTS 不在"
cp "$DTS" "$OUT/"
note "设备树：$(basename "$DTS")"

#  => 而那**内核镜像** 逐字**要**那 **Image** **那一件**（=> 而而它**要**那 aarch64 **交叉编译器**）。
CC="${CC:-}"
if [ -z "$CC" ]; then
  die "缺交叉编译器" "那内核镜像逐字要那 aarch64 交叉编译器（=> 而那 NDK 逐字在仓外、=> 而按 C-105 报缺）"
fi
[ -x "$CC" ] || die "缺交叉编译器" "$CC 不可执行"

KBUILD="$OUT/kbuild"
rm -rf "$KBUILD"
mkdir -p "$KBUILD"
note "编内核（$JOBS 作业）"
make -C "$KERNEL_SRC" O="$KBUILD" ARCH=arm64 CROSS_COMPILE="$(dirname "$CC")/aarch64-linux-android-" \
  gki_defconfig \
  > "$OUT/kbuild.log" 2>&1 \
  || { echo "=== 内核编译失败取证（末 40 行）==="; tail -40 "$OUT/kbuild.log"; exit 1; }

cp "$KBUILD/arch/arm64/boot/Image" "$OUT/"
note "内核镜像：Image（$KVER tree=$TREE commit=$COMMIT）"
