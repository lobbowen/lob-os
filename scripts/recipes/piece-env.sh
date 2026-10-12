#!/usr/bin/env bash
#
# 为什么说明要打进 APK：
#   照抄 deb-control(5)——「Each Debian binary package contains a control file in its
#   control member」。每个包自带说明，安装器与内核只读落位，不预置任何一件的清单。
#
# 用法：source scripts/recipes/piece-env.sh <件名>，然后调用 emit_piece
set -uo pipefail

PIECE_NAME="${1:?用法: source piece-env.sh <件名>}"

# 仓根（本文件在 scripts/recipes/）
PIECE_HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$PIECE_HERE/../.." && pwd)"
cd "$ROOT_DIR"

ABI="${ABI:-arm64-v8a}"
OUT="${OUT:-dist}"
case "$OUT" in /*) ;; *) OUT="$ROOT_DIR/$OUT" ;; esac
# 件说明随 assets 走 —— **这是运行时唯一读得到的那一份**。
# 只有 excludes/pickFirsts/keepDebugSymbols，没有「非 .so 也打进去」的开关），
WORK="$ROOT_DIR/work/$PIECE_NAME"
OUT_DIR="$WORK/out"
mkdir -p "$OUT/bin" "$WORK"

die() {
  local title="$1"; shift
  echo "::error title=$title::$(printf '%s\n' "$@")" >&2
  exit 1
}

# 定位 NDK：CC 由 CI 注入，本地没有就找 locate-ndk.sh
if [ -z "${CC:-}" ]; then
  [ -f "$ROOT_DIR/scripts/toolchain/locate-ndk.sh" ] \
    || die "缺 CC" "环境里没有 CC，也没有 scripts/toolchain/locate-ndk.sh"
  # ★ 用子进程跑，不 source ——
  #   locate-ndk.sh 里有 exit 1 与 set -euo pipefail；
  #   source 它会终止父进程并污染 set 选项（实测过）。
  #
  #   它把 CC/NDK 等写进 $GITHUB_ENV（不是 stdout），所以跑完从那个文件读回来。
  #   GitHub Actions 会把该文件的内容注入后续步骤的环境 —— 我们手动做同一件事。
  _GHE="$(mktemp)"
  GITHUB_ENV="$_GHE" bash "$ROOT_DIR/scripts/toolchain/locate-ndk.sh" \
    || die "定位 NDK 失败" "locate-ndk.sh 没跑通（见上面的输出）"
  # 只取我们需要的几个键
  while IFS="=" read -r _k _v; do
    case "$_k" in
      CC|CXX|NDK|LLVM_AR|LLVM_RANLIB|LLVM_STRIP|LLVM_READELF|TRIPLE)
        printf -v "$_k" "%s" "$_v"
        export "$_k"
        ;;
    esac
  done < "$_GHE"
  rm -f "$_GHE"
fi
[ -n "${CC:-}" ] || die "定位不到 NDK 的 clang" "CC 为空"

TC_DIR="$(dirname "$CC")"
LLVM_AR="$TC_DIR/llvm-ar"
LLVM_RANLIB="$TC_DIR/llvm-ranlib"
LLVM_STRIP="$TC_DIR/llvm-strip"
LLVM_READELF="$TC_DIR/llvm-readelf"
# autotools 要的 AR/RANLIB（名字与 autotools 的约定一致）
AR_BIN="$LLVM_AR"
RANLIB_BIN="$LLVM_RANLIB"

# NDK 根 —— openssl 的 Configure 要它认android-arm64
NDK_ROOT="$(cd "$(dirname "$CC")/../../../../.." && pwd)"

# Android API 级别（NDK 的 clang 三元组里带着它）
API="${ANDROID_API:-35}"

# 并行度
JOBS="${JOBS:-2}"

# 校验产出的 .so：必须是 aarch64 的 ELF，且不太小（太小说明编坏了）
check_so() {
  local f="$1" min="${2:-1000}"
  [ -f "$f" ] || return 1
  local sz
  sz="$(wc -c < "$f")"
  [ "$sz" -ge "$min" ] || return 1
  # ★ file 要 -L（跟随软链）——
  #   共享库产物是一整条链：libz.so → libz.so.1 → libz.so.1.3.2，
  #   libz.so 与 libz.so.1 都是软链。不跟随时 file 报的是
  #   「symbolic link to libz.so.1.3.2」而不是 ELF 形态，
  #   于是这三层里唯二的两个软链都被判成「不是 aarch64 ELF」而报错。
  #   （wc -c 本来就跟随软链，所以只有 file 这步需要显式 -L。）
  if command -v file >/dev/null 2>&1; then
    file -bL "$f" 2>/dev/null | grep -q "ELF 64-bit.*ARM aarch64" || return 1
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
# 件的版本 —— 取自钉值表（构建期唯一真相源）
version_of() {
  node -e '
    const s = require(process.argv[1]).sources || {};
    const v = (s[process.argv[2]] || {}).version;
    if (v) process.stdout.write(String(v));
  ' "$ROOT_DIR/scripts/component-sources.json" "$1"
}

# 打标准件包 —— 校验产物 + 产出装配器只读的那个 tar.gz。
#
# assets/supply/meta —— 那是预装件的私有通道，装配器读不了，也与 OTA 装件
#（走 ProgramInstallPipeline，读包里的 component-meta.json）不同构。
# 现在统一产出标准包，两条路同形。
#
# 用法：emit_piece <件名> <OUT_DIR> <入口相对路径> [最小字节]
emit_piece() {
  local id="$1" out="$2" entry="$3" min="${4:-1000}"
  local so base dir f b ver
  ver="$(version_of "$id")"
  [ -n "$ver" ] || die "${id} 没有版本" "scripts/component-sources.json 里查不到"
  so="$out/$entry"
  [ -f "$so" ] || die "${id} 没产出" "$so 不存在"
  base="$(basename "$so")"
  dir="$(dirname "$so")"
  check_so "$so" "$min" || die "${id} 产物不可用" "$so 不是 aarch64 ELF 或小于 $min 字节"
  # 共享库整条链（libz.so → .so.1 → .so.1.3.2）一起进包 ——
  # 它们是同一份字节的三个名字，linker 按 DT_NEEDED 找的是带版本的那一层
  if ls "$dir/$base".* >/dev/null 2>&1; then
    for f in "$dir/$base" "$dir/$base".*; do
      [ -e "$f" ] || continue
      b="$(basename "$f")"
      case "$b" in *.so|*.so.*) cp -Pf "$f" "$out/" 2>/dev/null || cp -f "$f" "$out/" ;; esac
    done
    echo "  （共享库整条链一起进包）"
  fi
  bash "$ROOT_DIR/scripts/recipes/package-piece-zip.sh" "$id" "$out" "$ver" "$entry"
}