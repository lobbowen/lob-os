#!/usr/bin/env bash
# 编译单件的公共前置：定位 NDK、准备输出目录、给出校验函数。
#
# 此前这些散在 build-native-capabilities.sh 里，那个脚本一次编 7 个件 ——
# 于是「加一件」要改一个 300 行脚本的中心段，且 APK 链只能整体调用它
# （现场编译）。现在每件一个脚本，各自调这里拿前置。
#
# 用法：source scripts/recipes/piece-env.sh <件名>
set -uo pipefail

PIECE_NAME="${1:?用法: source piece-env.sh <件名>}"

# 仓根（本文件在 scripts/recipes/）
PIECE_HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$PIECE_HERE/../.." && pwd)"
cd "$ROOT_DIR"

ABI="${ABI:-arm64-v8a}"
OUT="${OUT:-dist}"
JNI="container/app/src/main/jniLibs/$ABI"
mkdir -p "$ROOT_DIR/$OUT/bin" "$ROOT_DIR/$JNI" "$ROOT_DIR/work"

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

# 静态编译用的工具（同 NDK 那套 llvm-*）
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