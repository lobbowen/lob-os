#!/usr/bin/env bash

set -euo pipefail
export LC_ALL=C

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"

KERNEL_SRC="${KERNEL_SRC:-$ROOT/work/kernel-src}"

BUSYBOX_BIN="${BUSYBOX_BIN:-$ROOT/work/busybox/out/bin/libbusybox.so}"
BUSYBOX_LINK_DIR="${BUSYBOX_LINK_DIR:-$ROOT/work/busybox/out/bin}"
BUSYBOX_LINK_DIR="${BUSYBOX_LINK_DIR:-$ROOT/work/busybox/out/bin}"
OUT="${1:-$ROOT/work/kernel-image}"
JOBS="${JOBS:-$(nproc)}"

die() { echo "::error title=$1::$(printf '%s\n' "${@:2}")" >&2; exit 1; }
note() { echo "[kernel-image] $*"; }

[ -d "$KERNEL_SRC" ] || die "缺内核源码" "$KERNEL_SRC 不在（内核源码逐字在仓外，按 C-105 报缺）"
[ -f "$BUSYBOX_BIN" ] || die "缺静态 busybox" "$BUSYBOX_BIN 不在（那 initramfs 那一层逐字要那静态的那一个，按 C-105 报缺）"

COMMIT="$(git -C "$KERNEL_SRC" rev-parse HEAD)"
TREE="$(git -C "$KERNEL_SRC" rev-parse "HEAD^{tree}")"
note "钉值：commit=$COMMIT tree=$TREE"

KVER="$(sed -n 's/^VERSION = //p; s/^PATCHLEVEL = //p; s/^SUBLEVEL = //p' "$KERNEL_SRC/Makefile" | paste -sd. -)"
[ -n "$KVER" ] || die "读不到版本" "$KERNEL_SRC/Makefile 里读不到 VERSION/PATCHLEVEL"
note "版本：$KVER"

note "归档那一族不作钉值来源（那 tree 对象与那 commit 才是那一族）"

IRFS_STAGE="$OUT/initramfs-root"
rm -rf "$IRFS_STAGE" && mkdir -p "$IRFS_STAGE/bin"
cp "$BUSYBOX_BIN" "$IRFS_STAGE/bin/busybox"

if [ -d "$BUSYBOX_LINK_DIR" ]; then
  for _l in "$BUSYBOX_LINK_DIR"/*; do
    [ -L "$_l" ] || continue
    ln -sf busybox "$IRFS_STAGE/bin/$(basename "$_l")"
  done
else
  note "initramfs 那一层：bin/busybox（静态）+ init（那 applet 软链**没有**、按 C-105 报缺）"
fi

if [ -d "$BUSYBOX_LINK_DIR" ]; then
  for _l in "$BUSYBOX_LINK_DIR"/*; do
    [ -L "$_l" ] || continue
    ln -sf busybox "$IRFS_STAGE/bin/$(basename "$_l")"
  done
else
  note "initramfs 那一层：bin/busybox（静态）+ init（那 applet 软链**没有**、按 C-105 报缺）"
fi
chmod 0755 "$IRFS_STAGE/bin/busybox"
cp "$ROOT/scripts/initramfs/lobos-init" "$IRFS_STAGE/init"
chmod 0755 "$IRFS_STAGE/init"

ROOTFS_DIR="${ROOTFS_DIR:-$ROOT/work/rootfs/system}"
for A in rootfs.squashfs rootfs.erofs; do
  if [ -f "$ROOTFS_DIR/$A" ]; then
    cp "$ROOTFS_DIR/$A" "$IRFS_STAGE/$A"
    note "initramfs 那一层：$A（$(( $(stat -c %s "$IRFS_STAGE/$A") / 1024 )) KiB）"
  else
    note "initramfs 那一层：没有那 $A（系统层逐字**挂不上**、按 C-105 报缺）"
  fi
done
note "initramfs 那一层：bin/busybox（静态）+ init"

mkdir -p "$OUT"
python3 "$ROOT/scripts/initramfs/mkinitramfs.py" "$IRFS_STAGE" "$OUT/initramfs.cpio"

if command -v readelf >/dev/null 2>&1; then
  if readelf -W -l "$IRFS_STAGE/bin/busybox" 2>/dev/null | grep -q INTERP; then
    die "那静态的判定没过" "$IRFS_STAGE/bin/busybox 有 PT_INTERP它逐字是动态的那一族、那 initramfs 那一层逐字要那静态的那一个"
  fi
  note "静态判定：有那静态的那一个（无 PT_INTERP）"
else
  note "静态判定：跳过（那 readelf 不在，按 C-105 报缺）"
fi

DTS="${DTS:-}"
if [ -n "$DTS" ]; then
  [ -f "$DTS" ] || die "缺设备树" "$DTS 不在"
  cp "$DTS" "$OUT/"
  note "设备树（附带）：$(basename "$DTS")"
else
  note "设备树：逐字**不在**这**一支**（那 **Image** 逐字不等**它**、那**起得来**那一件逐字**要**它**）"
fi

CROSS="${CROSS:-aarch64-linux-android-}"
CROSS_BIN="${CROSS_BIN:-$(dirname "$CC")}"
HOSTCC="${HOSTCC:-gcc}"
note "CROSS_COMPILE 前缀：$CROSS（工具在 $CROSS_BIN）　HOSTCC：$HOSTCC"
KBUILD="$OUT/kbuild"
rm -rf "$KBUILD"
mkdir -p "$KBUILD"
note "编内核（$JOBS 作业）"

DEFCONFIG="${DEFCONFIG:-$ROOT/docs/kernel-baseline/gki_defconfig}"
[ -f "$DEFCONFIG" ] || die "缺 defconfig" "$DEFCONFIG 不在（按 C-105 报缺）"
note "defconfig：$DEFCONFIG（$(stat -c%s "$DEFCONFIG") 字节、sha256 $(sha256sum "$DEFCONFIG" | cut -c1-16)）"
make -C "$KERNEL_SRC" O="$KBUILD" ARCH=arm64 CROSS_COMPILE="$CROSS_BIN/$CROSS" HOSTCC="$HOSTCC" \
  olddefconfig \
  > "$OUT/kbuild-config.log" 2>&1 \
  || { echo "=== 配置失败取证（末 40 行）==="; tail -40 "$OUT/kbuild-config.log"; exit 1; }

note "编内核（$JOBS 作业）—— 这一步在 CI 上要几十分钟到数小时"
make -C "$KERNEL_SRC" O="$KBUILD" ARCH=arm64 CROSS_COMPILE="$CROSS_BIN/$CROSS" HOSTCC="$HOSTCC" \
  -j"$JOBS" Image \
  > "$OUT/kbuild.log" 2>&1 \
  || { echo "=== 内核编译失败取证（末 40 行）==="; tail -40 "$OUT/kbuild.log"; exit 1; }

cp "$KBUILD/arch/arm64/boot/Image" "$OUT/"

cat > "$OUT/kernel-image-meta.json" << METAEOF
{
  "version": "$KVER",
  "commit": "$COMMIT",
  "tree": "$TREE",
  "pinNote": "归档那一族逐字每次换那字节、它不作那钉值来源、钉值逐字用那 tree 对象与那 commit 那两个数"
}
METAEOF
note "钉值已带进产物：kernel-image-meta.json（tree=$TREE commit=$COMMIT version=$KVER）"
note "内核镜像：Image（$KVER tree=$TREE commit=$COMMIT）"
