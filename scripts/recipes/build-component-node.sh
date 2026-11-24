#!/usr/bin/env bash
set -euo pipefail

HERE=$(dirname "$0")
cd "$HERE/../.."
ROOT_DIR=$(pwd)

OUT="${OUT:-dist}"
RAW_DIR="$ROOT_DIR/work"
STAGE="$ROOT_DIR/$OUT"
NODE_VERSION="${NODE_VERSION:-$(bash "$ROOT_DIR/scripts/registry/read-node-versions.sh" default || true)}"
ABI="$(bash "$ROOT_DIR/scripts/registry/read-node-versions.sh" abi 2>/dev/null || true)"
[ -n "$ABI" ] || ABI="arm64-v8a"

if [ -z "$NODE_VERSION" ] || [ "$NODE_VERSION" = "unknown" ]; then
  echo "::error title=取不到 node 版本::scripts/registry/read-node-versions.sh default 失败"
  exit 1
fi

# glibc node（默认）：官方 linux-arm64 tarball，按钉值表逐字节校验。
# 上游按 glibc 分发原生 addon（NARB/koffi/sharp），node 必须同 libc 才能 dlopen .node。
# PT_INTERP=/lib/ld-linux-aarch64.so.1，经 LobOS guest 路由用 SIGSYS loader 启动；
# 依赖库（libc/libstdc++/libgcc_s 等）由 glibc-runtime 组件经 --library-path 供给。
# NODE_GLIBC=0 时回退老的 bionic 自编译 libnode.so（保留给需要 bionic node 的场景）。
NODE_GLIBC="${NODE_GLIBC:-0}"

mkdir -p "$STAGE/bin" "$RAW_DIR"

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

GOT=""
NODE_FLAVOR=""
if [ "$NODE_GLIBC" = "1" ]; then
  # 1. 从钉值表取官方 tarball（fetch-pinned 做 sha256 字节校验 + 多来源回退）
  TAR="$RAW_DIR/node-glibc.tar.xz"
  bash "$ROOT_DIR/scripts/toolchain/fetch-pinned.sh" --pin node-glibc-tarball "$TAR" \
    || { echo "::error title=取官方 glibc node 失败::fetch-pinned --pin node-glibc-tarball 没通过（钉值/来源见 component-sources.json）"; exit 1; }
  echo "[node] 官方 glibc tarball $(stat -c%s "$TAR") 字节"

  # 2. 解出 bin/node（tarball 顶层是 node-v<ver>-linux-arm64/）
  U="$RAW_DIR/node-glibc-unpack"
  rm -rf "$U" && mkdir -p "$U"
  tar xf "$TAR" -C "$U" --wildcards --strip-components=2 '*/bin/node' \
    || { echo "::error title=解 tarball 失败::找不到 */bin/node"; exit 1; }
  SRC="$U/node"
  [ -f "$SRC" ] || { echo "::error title=解出的 node 不在::$SRC"; exit 1; }
  cp -f "$SRC" "$STAGE/bin/node"
  chmod 0755 "$STAGE/bin/node"
  GOT=$(sha256sum "$STAGE/bin/node" | cut -c1-64)
  NODE_FLAVOR="glibc-official"
  echo "[node] flavor=$NODE_FLAVOR（官方 linux-arm64 glibc）"
else
  # fallback：老的 bionic 自编译 libnode.so（Node Runtime workflow 产出）
  SRC=""
  for cand in \
    "$RAW_DIR/libnode.so" \
    "$ROOT_DIR/dist/libnode.so" \
    "$ROOT_DIR/dist/node-runtime/libnode.so" \
    "$ROOT_DIR/dist/node/libnode.so" \
    "$ROOT_DIR/container/app/src/main/jniLibs/arm64-v8a/libnode.so"
  do
    if [ -f "$cand" ]; then SRC="$cand"; break; fi
  done
  if [ -z "$SRC" ]; then
    echo "::error title=没有 libnode.so::NODE_GLIBC=0 回退路径需要 libnode.so（编译要 3~4 小时，走 Node Runtime workflow 一次）"
    exit 1
  fi
  if [ "$SRC" != "$RAW_DIR/libnode.so" ]; then
    mv -f "$SRC" "$RAW_DIR/libnode.so"
    rm -rf "$ROOT_DIR/dist/node-runtime" "$ROOT_DIR/dist/node" 2>/dev/null || true
    SRC="$RAW_DIR/libnode.so"
    echo "[node] 原始件挪出 $OUT/（留在里面会被 package-component.sh 连同 bin/node 一起打进 zip，包体积翻倍）"
  fi
  GOT=$(sha256sum "$SRC" | cut -c1-64)
  cp -f "$SRC" "$STAGE/bin/node"
  chmod 0755 "$STAGE/bin/node"
  NODE_FLAVOR="bionic"
  echo "[node] flavor=$NODE_FLAVOR（自编译 libnode.so）"
fi

echo "$GOT  node" > "$RAW_DIR/node.sha256"
printf '%s\n' "$NODE_VERSION" > "$ROOT_DIR/$OUT/node.version"

check_elf() {
  local f="$1" what="$2"
  if [ -n "$READELF_BIN" ]; then
    "$READELF_BIN" -W -h "$f" >/dev/null 2>&1 && return 0
  fi
  if command -v file >/dev/null 2>&1; then
    file -b "$f" 2>/dev/null | grep -qE 'ELF .*64-bit LSB .*(arm64|ARM aarch64|AArch64)' && return 0
  fi
  echo "::error title=$what 不是 arm64 ELF::$(file -b "$f" 2>/dev/null || echo 无法判定)"
  return 1
}

check_elf "$STAGE/bin/node" "node 产物" || exit 1

MACHINE=""
[ -n "$READELF_BIN" ] && MACHINE="$("$READELF_BIN" -W -h "$STAGE/bin/node" 2>/dev/null | sed -n 's/^ *Machine: *//p' | head -1)"
echo "[node] ELF Machine: ${MACHINE:-未知}"

if [ -n "$READELF_BIN" ]; then
  INTERP="$("$READELF_BIN" -W -l "$STAGE/bin/node" 2>/dev/null | sed -n 's/.*\[Requesting program interpreter: \([^]]*\)\].*/\1/p' | head -1)"
  if [ -n "$INTERP" ]; then
    case "$INTERP" in
      */linker64|*/linker|*/dynlink*)
        [ "$NODE_FLAVOR" = "bionic" ] \
          || { echo "::error title=flavor与解释器不符::$NODE_FLAVOR 却拿 bionic 解释器 $INTERP"; exit 1; }
        echo "[node] 解释器 $INTERP（Android linker，bionic flavor 符合）" ;;
      */ld-linux-aarch64.so.1|*/ld-linux.so.2|*/ld-linux*)
        [ "$NODE_FLAVOR" = "glibc-official" ] \
          || { echo "::error title=flavor与解释器不符::$NODE_FLAVOR 却拿 glibc 解释器 $INTERP"; exit 1; }
        echo "[node] 解释器 $INTERP（glibc，官方 flavor 符合；经 guest 路由启动）" ;;
      *) echo "::error title=解释器不认识::$INTERP —— 不在兑现范围"; exit 1 ;;
    esac
  else
    echo "::note::静态链接（无解释器段）"
  fi
  NEEDED="$("$READELF_BIN" -W -d "$STAGE/bin/node" 2>/dev/null | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p' | tr '\n' ' ')"
  echo "[node] 外部依赖: ${NEEDED:-（无）}"
  if [ "$NODE_FLAVOR" = "glibc-official" ]; then
    case "$NEEDED" in
      *libc.so.6*) echo "       由 glibc-runtime 组件经 guest --library-path 供给（libc/libm/libdl/libstdc++/libgcc_s）。" ;;
      *) echo "::error title=glibc node 缺 libc.so.6 依赖::NEEDED=$NEEDED —— 不是官方 glibc 件"; exit 1 ;;
    esac
  fi
fi

SIZE=$(stat -c%s "$STAGE/bin/node")
EXPECT_MIN=50000000
if [ "$SIZE" -lt "$EXPECT_MIN" ]; then
  echo "::error title=产物太小::$SIZE 字节 < $EXPECT_MIN，node 本体不该这么小（多半截了）"
  exit 1
fi
echo "[node] 产出 $STAGE/bin/node（$SIZE 字节，版本 $NODE_VERSION，ABI $ABI，flavor $NODE_FLAVOR）"
echo "[node] sha256 $GOT"
