#!/usr/bin/env bash
# 编译单件的公共前置：定位 NDK、落位产物、**并把这一件自己的说明一起打进 jniLibs**。
#
# 为什么说明要打进 APK：
#   照抄 deb-control(5)——「Each Debian binary package contains a control file in its
#   control member」。每个包自带说明，安装器与内核只读落位，不预置任何一件的清单。
#   内核侧 PrefixProvisioner 扫 jniLibs 里的 *.meta.json 决定铺什么、铺成什么形状。
#
# 用法：source scripts/recipes/piece-env.sh <件名>，然后调用 land_piece
set -uo pipefail

PIECE_NAME="${1:?用法: source piece-env.sh <件名>}"

# 仓根（本文件在 scripts/recipes/）
PIECE_HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$PIECE_HERE/../.." && pwd)"
cd "$ROOT_DIR"

ABI="${ABI:-arm64-v8a}"
OUT="${OUT:-dist}"
case "$OUT" in /*) ;; *) OUT="$ROOT_DIR/$OUT" ;; esac
JNI="container/app/src/main/jniLibs/$ABI"
WORK="$ROOT_DIR/work/$PIECE_NAME"
mkdir -p "$OUT/bin" "$ROOT_DIR/$JNI" "$WORK"

# 说明文件名 —— jniLibs 里与件同名，PrefixProvisioner 按 *.meta.json 扫
META_SUFFIX=".meta.json"

die() {
  local title="$1"; shift
  echo "::error title=$title::$(printf '%s\n' "$@")" >&2
  exit 1
}

# 定位 NDK：CC 由 CI 注入，本地没有就找 locate-ndk.sh
if [ -z "${CC:-}" ]; then
  [ -f "$ROOT_DIR/scripts/toolchain/locate-ndk.sh" ] \
    || die "缺 CC" "环境里没有 CC，也没有 scripts/toolchain/locate-ndk.sh"
  # shellcheck disable=SC1091
  source "$ROOT_DIR/scripts/toolchain/locate-ndk.sh"
fi
[ -n "${CC:-}" ] || die "定位不到 NDK 的 clang" "CC 为空"

TC_DIR="$(dirname "$CC")"
LLVM_AR="$TC_DIR/llvm-ar"
LLVM_RANLIB="$TC_DIR/llvm-ranlib"
LLVM_STRIP="$TC_DIR/llvm-strip"
LLVM_READELF="$TC_DIR/llvm-readelf"

# 校验产出的 .so：必须是 aarch64 的 ELF，且不太小（太小说明编坏了）
check_so() {
  local f="$1" min="${2:-1000}"
  [ -f "$f" ] || return 1
  local sz
  sz="$(wc -c < "$f")"
  [ "$sz" -ge "$min" ] || return 1
  if command -v file >/dev/null 2>&1; then
    file -b "$f" 2>/dev/null | grep -q "ELF 64-bit.*ARM aarch64" || return 1
  fi
  return 0
}

# 校验它是可执行文件（比 .so 多一步：要真能跑起来）
check_exe() {
  local f="$1" min="${2:-1000}"
  check_so "$f" "$min" || return 1
  [ -x "$f" ]
}

# 生成这一件的说明 —— 数据全来自构建期表（component-sources.json / component-verify.json），
# 这里不硬编码任何一件的信息；数据不齐就报错，不产出半截的说明。
gen_meta() {
  node "$ROOT_DIR/scripts/recipes/gen-component-meta.js" "$1" "$WORK/component-meta.json"
}

# 落位一件：拷进 jniLibs + 说明同落。
#   land_piece <件名> <.so 文件名> [最低字节数]
# 产物与说明同名成对 —— 内核扫到 *.meta.json 就知道该铺什么。
land_piece() {
  local id="$1" so="$2" min="${3:-1000}"
  local built="$WORK/$so"
  [ -f "$built" ] || die "产物不存在" "$built"
  check_so "$built" "$min" || die "产物不可用" "$built（不是 aarch64 ELF，或小于 $min 字节）"
  cp -f "$built" "$JNI/$so"
  gen_meta "$id" > /dev/null
  cp -f "$WORK/component-meta.json" "$JNI/$so$META_SUFFIX" \
    || die "说明没落位" "gen-component-meta.js 没产出 $WORK/component-meta.json"
  echo "[ok] $id → $JNI/$so（+ 说明）"
}
