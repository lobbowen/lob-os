#!/usr/bin/env bash

set -euo pipefail
export LC_ALL=C

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"

OUT="${1:-$ROOT/work/rootfs}"
case "$OUT" in /*) ;; *) OUT="$ROOT/$OUT" ;; esac

die() { echo "::error title=$1::$(printf '%s\n' "${@:2}")"; exit 1; }
note() { echo "[rootfs] $*"; }

MKSQUASHFS="${MKSQUASHFS:-}"
if [ -z "$MKSQUASHFS" ]; then
  for c in mksquashfs /usr/bin/mksquashfs; do
    if command -v "$c" >/dev/null 2>&1; then MKSQUASHFS="$(command -v "$c")"; break; fi
    [ -x "$c" ] && { MKSQUASHFS="$c"; break; }
  done
fi
if [ -z "$MKSQUASHFS" ]; then

  echo "::notice title=宿主上没有 mksquashfs::那系统层逐字**要**那 squashfs 归档、工具**没有**它"
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
  "note": "那**系统层**逐字**要**那 mksquashfs **做**、那**宿主**上逐字**没有**它"
}
JSON
  echo "[ok] $OUT（那两样的**形状**已落；那系统层那归档逐字**报缺**）"
  exit 0
fi

SRC="${ROOTFS_SRC:-$ROOT/work/kernel-image/initramfs-root}"
[ -d "$SRC" ] || die "缺系统层来源" "$SRC 不在 —— 先编那 initramfs 那一层（scripts/toolchain/build-kernel-image.sh）"

STAGE="${ROOTFS_STAGE:-$ROOT/work/rootfs-stage}"
if [ "$SRC" != "$STAGE" ]; then
  rm -rf "$STAGE"
  mkdir -p "$STAGE"
  cp -a "$SRC/." "$STAGE/"
fi

rm -rf "$OUT"
mkdir -p "$OUT/system"

M3_STAGE="${M3_PIECE_STAGE:-$ROOT/work/m3-stage}"
if [ -d "$M3_STAGE" ]; then
  note "装那 M3 那一族（来源 $M3_STAGE）"
  mkdir -p "$STAGE/usr"

  if [ -f "$M3_STAGE/usr/lib/libbusybox.so" ] && [ ! -f "$M3_STAGE/usr/bin/busybox" ]; then
    cp -f "$M3_STAGE/usr/lib/libbusybox.so" "$STAGE/usr/bin/busybox"
    note "busybox 本体已进那 usr/bin 那一层"
  fi

    note "llvm 那一族已从那 dist 复制进那归档（那**软链**逐字**不**再指**那宿主**）"
  fi

  LLVM_SRC="${LLVM_SRC:-$ROOT/dist}"
  if [ ! -f "$LLVM_SRC/bin/clang-21" ] && [ -f "$STAGE/usr/bin/clang-21" ]; then
    LLVM_SRC="$STAGE"
    note "那 dist 底下没有那 clang-21（那**它**逐字**已经**被**清掉**）、那**来源**逐字**改**用那**上**一次**编**留**的**那一份**"
  fi
  if [ -d "$LLVM_SRC/bin" ] && [ -f "$LLVM_SRC/bin/clang-21" ]; then
    mkdir -p "$STAGE/usr/bin" "$STAGE/usr/lib"
    for _b in clang clang++ clang-21 clang-cl clang-cpp; do
      [ -e "$LLVM_SRC/bin/$_b" ] || continue

      rm -f "$STAGE/usr/bin/$_b"
      cp -fL "$LLVM_SRC/bin/$_b" "$STAGE/usr/bin/$_b" 2>/dev/null || true
    done
    if [ -d "$LLVM_SRC/lib/clang" ]; then
      mkdir -p "$STAGE/usr/lib/clang"
      cp -a "$LLVM_SRC/lib/clang/." "$STAGE/usr/lib/clang/" 2>/dev/null || true
    fi
    note "llvm 那一族已从那 dist 复制进那归档（那**软链**逐字**不**再指**那宿主**）"
  fi

  if [ -d "$M3_STAGE/usr/bin" ]; then
    note "装那 M3 那一族（来源 $M3_STAGE）"
    mkdir -p "$STAGE/usr"

    for _f in "$M3_STAGE/usr/bin/"*; do
      _b="$(basename "$_f")"
      case "$_b" in
        clang|clang++|clang-21|clang-cl|clang-cpp) continue ;;
      esac
      cp -a "$_f" "$STAGE/usr/bin/"
    done
    if [ -d "$M3_STAGE/usr/lib" ]; then
      mkdir -p "$STAGE/usr/lib"
      cp -a "$M3_STAGE/usr/lib/." "$STAGE/usr/lib/"
    fi
    if [ -d "$M3_STAGE/bin" ]; then
      mkdir -p "$STAGE/bin"
      cp -a "$M3_STAGE/bin/." "$STAGE/bin/"
    fi
    if [ -d "$M3_STAGE/lib" ]; then
      mkdir -p "$STAGE/lib"
      cp -a "$M3_STAGE/lib/." "$STAGE/lib/"
    fi

    if [ -f "$M3_STAGE/usr/lib/libbusybox.so" ] && [ ! -f "$M3_STAGE/usr/bin/busybox" ]; then
      cp -f "$M3_STAGE/usr/lib/libbusybox.so" "$STAGE/usr/bin/busybox"
      note "busybox 本体已进那 usr/bin 那一层"
    fi
  else
    note "没有那 M3 暂存（$M3_STAGE）那 M3 那一族逐字**装不进来**（按 C-105 报缺）"
  fi

note "装那系统层（squashfs，用 $MKSQUASHFS）"
rm -f "$OUT/system/rootfs.squashfs"

"$MKSQUASHFS" "$STAGE" "$OUT/system/rootfs.squashfs" -noappend -no-progress >/dev/null 2>&1 || true
[ -f "$OUT/system/rootfs.squashfs" ] || die "系统层没产出" "$OUT/system/rootfs.squashfs 不在"

note "建那可写层（工作区目录）"
mkdir -p "$OUT/workdir"

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
  "note": "那**两样**逐字**不能并成一个文件**、那**系统层**逐字**是**那**归档**、那**可写层**逐字**是**那**目录**、按 C-104 逐字**不合并**"
}
JSON

note "就位 $OUT（系统层 $SYS_BYTES 字节 · 可写层 workdir）"
du -sh "$OUT"
echo "[ok] $OUT（那两样：系统层 squashfs 归档 + 可写层工作区目录）"
