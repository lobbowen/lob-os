#!/usr/bin/env bash
set -euo pipefail

HERE=$(dirname "$0")
cd "$HERE/.."
ROOT_DIR=$(pwd)

OUT="${OUT:-dist}"
RAW_DIR="$ROOT_DIR/work"
STAGE="$ROOT_DIR/$OUT"
NODE_VERSION="${NODE_VERSION:-$(bash "$ROOT_DIR/scripts/read-node-versions.sh" default || true)}"
ABI="$(bash "$ROOT_DIR/scripts/read-node-versions.sh" abi 2>/dev/null || true)"
[ -n "$ABI" ] || ABI="arm64-v8a"

if [ -z "$NODE_VERSION" ] || [ "$NODE_VERSION" = "unknown" ]; then
  echo "::error title=取不到 node 版本::scripts/read-node-versions.sh default 失败"
  exit 1
fi

mkdir -p "$STAGE/bin" "$RAW_DIR"

SRC=""
for cand in \
  "$RAW_DIR/libnode.so" \
  "$ROOT_DIR/dist/libnode.so" \
  "$ROOT_DIR/dist/node-runtime/libnode.so" \
  "$ROOT_DIR/dist/node/libnode.so"
do
  if [ -f "$cand" ]; then SRC="$cand"; break; fi
done
if [ -z "$SRC" ]; then
  echo "::error title=没有 libnode.so::本脚本不编译 node（编译要 3~4 小时，走 Node Runtime workflow 一次）"
  echo "           期望在 work/ 或 dist/ 下找到 libnode.so，实际内容："
  ls -la "$RAW_DIR" 2>/dev/null | head -n 10 || true
  ls -la "$ROOT_DIR/dist" 2>/dev/null | head -n 10 || true
  exit 1
fi
if [ "$SRC" != "$RAW_DIR/libnode.so" ]; then
  mv -f "$SRC" "$RAW_DIR/libnode.so"
  rm -rf "$ROOT_DIR/dist/node-runtime" "$ROOT_DIR/dist/node" 2>/dev/null || true
  SRC="$RAW_DIR/libnode.so"
  echo "[node] 原始件挪出 dist/（留在里面会被 package-userland.sh 连同 bin/node 一起打进 zip，包体积翻倍）"
fi
echo "[node] 取已编译运行时：$SRC"

GOT=$(sha256sum "$SRC" | cut -c1-64)
echo "$GOT  libnode.so" > "$RAW_DIR/libnode.sha256"
printf '%s\n' "$NODE_VERSION" > "$ROOT_DIR/$OUT/node.version"

cp -f "$SRC" "$STAGE/bin/node"
chmod 0755 "$STAGE/bin/node"

READELF_BIN=""
if [ -n "${CC:-}" ]; then
  CAND="$(dirname "$CC")/llvm-readelf"
  [ -x "$CAND" ] && READELF_BIN="$CAND"
fi
if [ -z "$READELF_BIN" ]; then
  for c in /usr/local/lib/android/sdk/ndk/*/toolchains/llvm/prebuilt/*/bin/llvm-readelf; do
    [ -x "$c" ] && { READELF_BIN="$c"; break; }
  done
fi

check_elf() {
  local f="$1" what="$2"
  if [ -n "$READELF_BIN" ]; then
    "$READELF_BIN" -h "$f" >/dev/null 2>&1 && return 0
  fi
  if command -v file >/dev/null 2>&1; then
    file -b "$f" 2>/dev/null | grep -qE 'ELF .*64-bit LSB .*(arm64|ARM aarch64|AArch64)' && return 0
  fi
  echo "::error title=$what 不是 arm64 ELF::$(file -b "$f" 2>/dev/null || echo 无法判定)"
  return 1
}

check_elf "$STAGE/bin/node" "node 产物" || exit 1

MACHINE=""
if [ -n "$READELF_BIN" ]; then
  MACHINE="$("$READELF_BIN" -h "$STAGE/bin/node" 2>/dev/null | sed -n 's/^ *Machine: *//p' | head -1)"
fi
echo "[node] ELF Machine: ${MACHINE:-未知}"

if [ -n "$READELF_BIN" ]; then
  INTERP="$("$READELF_BIN" -l "$STAGE/bin/node" 2>/dev/null | sed -n 's/.*\[Requesting program interpreter: \([^]]*\)\].*/\1/p' | head -1)"
  if [ -n "$INTERP" ]; then
    case "$INTERP" in
      */linker64|*/linker|*/dynlink*) echo "[node] 解释器 $INTERP（Android linker，符合要求）" ;;
      *) echo "::error title=解释器不是 Android linker::$INTERP —— 这个件在设备上起不来"; exit 1 ;;
    esac
  else
    echo "::note::静态链接（无解释器段），也可在设备上直接跑"
  fi
  NEEDED="$("$READELF_BIN" -d "$STAGE/bin/node" 2>/dev/null | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p' | tr '\n' ' ')"
  echo "[node] 外部依赖: ${NEEDED:-（无）}"
  case "$NEEDED" in
    *libc++_shared.so*)
      echo "[node] 依赖 libc++_shared.so —— 它随 APK 交付在 nativeLibraryDir，"
      echo "       由 LD_LIBRARY_PATH 供给（RuntimeEnvironment），件内无需自带。" ;;
  esac
fi

SIZE=$(stat -c%s "$STAGE/bin/node")
EXPECT_MIN=50000000
if [ "$SIZE" -lt "$EXPECT_MIN" ]; then
  echo "::error title=产物太小::$SIZE 字节 < $EXPECT_MIN，node 本体不该这么小（多半截了）"
  exit 1
fi
echo "[node] 产出 $STAGE/bin/node（$SIZE 字节，版本 $NODE_VERSION，ABI $ABI）"
echo "[node] sha256 $GOT"
