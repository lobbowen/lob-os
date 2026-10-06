#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

HERE="$(dirname "$0")"
cd "$HERE/.."
ROOT_DIR="$(pwd)"

API="${ANDROID_API:-23}"
JOBS="${JOBS:-4}"
TOOL="python3"
SRC_KEY="python"
OUT="${OUT:-dist}"
case "$OUT" in /*) ;; *) OUT="$ROOT_DIR/$OUT" ;; esac

die() {
  local title="$1"; shift
  echo "::error title=$title::$(printf '%s\n' "$@")"
  exit 1
}
note() { echo "[$TOOL] $*"; }

[ -n "${CC:-}" ] || die "缺 CC" "需要 NDK 的 clang"
TC="$(dirname "$CC")"
LLVM_AR="$TC/llvm-ar"
LLVM_RANLIB="$TC/llvm-ranlib"
LLVM_STRIP="${LLVM_STRIP:-$TC/llvm-strip}"
LLVM_READELF="${LLVM_READELF:-$TC/llvm-readelf}"
for t in "$LLVM_AR" "$LLVM_RANLIB" "$LLVM_READELF"; do
  [ -x "$t" ] || die "缺工具" "$t 不存在"
done
command -v make >/dev/null 2>&1 || die "缺 make" "CPython 的构建靠 make 驱动（缺了请 apt-get install make）"

# 宿主构建 python 必须与被编的 CPython **同 major.minor**。
# CPython 3.14 的 configure 第 161-163 行是硬判（查 cpython/3.14 的 configure.ac）：
#   build_python_ver=$($with_build_python -c "...print(major.minor)")
#   if test "$build_python_ver" != "$PACKAGE_VERSION"; then AC_MSG_ERROR(…)
# 即**必须相等**，不是「≥某下限」。实测报错原文：
#   "/usr/bin/python3" has incompatible version 3.12 (expected: 3.14)
#
# 而早先这里只找「任意 python3」—— runner 的 `python3` 是 3.12，
# 它自带的 3.14 在 Cached Tools 里但**不在 PATH**
# （actions/runner-images Ubuntu2404：Cached Tools 段有 Python 3.10.21…3.14.7）。
# 于是 configure 第一步就红，而报错指向「版本不兼容」看不出是「找错了 python」。
CPY_VER="$(bash "$ROOT_DIR/scripts/fetch-pinned.sh" --src-version python 2>/dev/null || true)"
[ -n "$CPY_VER" ] || die "拿不到 CPython 版本" "钉值表里没有 sources.python.version"
PY_WANT="${CPY_VER%.*}"

HOST_PY="${BUILD_PYTHON:-}"
if [ -n "$HOST_PY" ]; then
  [ -x "$HOST_PY" ] || [ -f "$HOST_PY" ] || HOST_PY=""
fi
if [ -z "$HOST_PY" ]; then
  for c in "python$PY_WANT" python3 python; do
    command -v "$c" >/dev/null 2>&1 || continue
    cand="$(command -v "$c")"
    cand_mm="$("$cand" -c 'import sys;print("%d.%d"%sys.version_info[:2])' 2>/dev/null || true)"
    [ "$cand_mm" = "$PY_WANT" ] && { HOST_PY="$cand"; break; }
  done
fi
if [ -n "$HOST_PY" ]; then
  HOST_PY_VER="$("$HOST_PY" -c 'import sys;print("%d.%d.%d"%sys.version_info[:3])')"
else
  HOST_PY_VER="（没找到 $PY_WANT）"
fi
[ -n "$HOST_PY" ] || die "找不到 $PY_WANT 的宿主 python" \
  "要编 CPython $CPY_VER，它的构建脚本必须由**同 major.minor 的** $PY_WANT 跑" \
  "（CPython 的 configure 要求两者相等，不是「≥下限」）。" \
  "runner 自带 $PY_WANT 但它在 Cached Tools 里、通常不在 PATH。先查它在哪：" \
  "  ls -d /opt/hostedtoolcache/Python/$PY_WANT.*/x64/bin/ 2>/dev/null" \
  "然后 either 把它加进 PATH，或显式传 BUILD_PYTHON=<那个 python>。"
note "宿主 python: $HOST_PY（$HOST_PY_VER，要 $PY_WANT）"

mkdir -p "$OUT/bin"
WORK="$ROOT_DIR/work/$SRC_KEY"
mkdir -p "$WORK"

SRC_VER="$(bash "$ROOT_DIR/scripts/fetch-pinned.sh" --src-version $SRC_KEY)"
SRC="$WORK/$SRC_KEY-src"
if [ ! -d "$SRC" ]; then
  TGZ="$WORK/$SRC_KEY.tgz"
  note "取 CPython $SRC_VER 源码（仓内唯一入口，sha256 逐字节校验）"
  bash "$ROOT_DIR/scripts/fetch-pinned.sh" --pin $SRC_KEY "$TGZ" || die "取源码失败" "钉值见 userland-sources.json"
  rm -rf "$SRC" && mkdir -p "$SRC"
  tar xzf "$TGZ" -C "$SRC" --strip-components=1 || die "解包失败" "$TGZ"
fi
[ -x "$SRC/configure" ] || die "源码树异常" "缺 configure（CPython 的发布包自带）"
GOT_VER="$(awk '
  /#define PY_MAJOR_VERSION/ {a=$3}
  /#define PY_MINOR_VERSION/ {b=$3}
  /#define PY_MICRO_VERSION/ {c=$3}
  END {gsub(/[ \t]/,"",a); gsub(/[ \t]/,"",b); gsub(/[ \t]/,"",c); print a"."b"."c}
' "$SRC/Include/patchlevel.h")"
[ "$GOT_VER" = "$SRC_VER" ] || die "版本不符" \
  "钉的是 $SRC_VER，Include/patchlevel.h 三段拼出 $GOT_VER"
note "源码 $GOT_VER 就位（patchlevel.h 核实）"

BUILD="$WORK/build"
rm -rf "$BUILD" && mkdir -p "$BUILD"

(
  set -e
  cd "$BUILD"
  "$SRC/configure" \
    --host=aarch64-linux-android --build=x86_64-pc-linux-gnu \
    --with-build-python="$HOST_PY" \
    --without-ensurepip \
    ac_cv_file__dev_ptmx=no \
    ac_cv_file__dev_ptc=no \
    ac_cv_file__dev_tty=no \
    CPPFLAGS="-D__ANDROID_API__=$API" \
    CC="$CC" AR="$LLVM_AR" RANLIB="$LLVM_RANLIB" \
    CFLAGS="-O2" \
    > "$WORK/configure.log" 2>&1 \
    || { echo "=== configure 失败取证（末 50 行）==="; tail -50 "$WORK/configure.log"; exit 1; }
)
note "configure 通过"

grep -q 'cross_compiling *= *yes' "$WORK/configure.log" 2>/dev/null \
  || grep -q 'cross_compiling:.*yes' "$WORK/configure.log" 2>/dev/null \
  || note "注意：configure.log 里没直接看到 cross_compiling 的判定（格式随版本变）；下面的产物架构自检会兜住"

make -C "$BUILD" -j"$JOBS" > "$WORK/build.log" 2>&1 \
  || { echo "=== 编译失败取证（error 行 + 末 50 行）==="; \
       grep -nE "error:|Error [0-9]+$|undefined (symbol|reference)" "$WORK/build.log" | head -25 || true; \
       tail -50 "$WORK/build.log"; exit 1; }

BIN=""
for cand in "$BUILD"/python3.*; do
  [ -x "$cand" ] && [ -f "$cand" ] && BIN="$cand" && break
done
[ -n "$BIN" ] || { echo "=== build 下有哪些可执行 ==="; ls "$BUILD" | grep -E '^python' | head -5; die "没产出解释器" "$BUILD/python3.* 不存在"; }
cp -f "$BIN" "$OUT/bin/$TOOL"
chmod 0755 "$OUT/bin/$TOOL"

SIZE=$(stat -c%s "$OUT/bin/$TOOL")
[ "$SIZE" -gt 1000000 ] || die "产物可疑" "python3 只有 $SIZE 字节 —— 正常应在数 MB 量级"
INFO=$(file -b "$OUT/bin/$TOOL")
case "$INFO" in *aarch64*|*arm64*|*ARM64*) : ;; *) die "架构不对" "$INFO —— configure 可能按宿主 x86_64 配了"; esac

NEEDED="$("$LLVM_READELF" -W -d "$OUT/bin/$TOOL" 2>/dev/null | sed -n 's/.*NEEDED.*\[\(.*\)\].*/\1/p' | tr '\n' ' ')"
case "$NEEDED" in
  *libc.so.6*|*libstdc++.so.6*|*libpthread.so.0*)
    die "依赖 glibc 的库" "NEEDED: $NEEDED —— 这是 glibc 构建，Bionic 上跑不了（libc.so.6 / libstdc++.so.6 在 Bionic 上不存在）" ;;
esac
note "NEEDED: ${NEEDED:-（无）}"

BAD="$("$LLVM_READELF" -W -l "$OUT/bin/$TOOL" 2>/dev/null | awk '/^[[:space:]]*LOAD/{print $NF}' \
      | while read -r a; do
          case "$a" in 0x[0-9a-fA-F]*) ;; *) continue ;; esac
          d=$(( a )); [ "$d" -eq 0 ] && continue
          [ $(( d % 16384 )) -ne 0 ] && printf ' %s' "$a"
        done)"
[ -z "$BAD" ] || die "16KB 对齐不合格" "这些 LOAD 段：$BAD"

PREFIX="$WORK/_inst"
make -C "$BUILD" install > "$WORK/install.log" 2>&1 \
  || { echo "=== install 失败（末 30 行）==="; tail -30 "$WORK/install.log"; exit 1; }

PYDIR="$PREFIX/lib/python${SRC_VER%.*}"
[ -d "$PYDIR" ] || {
  echo "=== $PREFIX/lib 下有什么 ==="; ls -d "$PREFIX"/lib/* 2>/dev/null | head -5
  die "找不到安装后的标准库目录" \
    "按 CPython 的安装规则应在 $PREFIX/lib/python${SRC_VER%.*}/；缺了它解释器起得来但 import 就死"
}
[ -f "$PYDIR/os.py" ] || die "标准库目录里没有 os.py" "$PYDIR —— 目录在但内容不对（构建没跑完？）"

rm -rf "$OUT/lib"
mkdir -p "$OUT/lib"
cp -a "$PYDIR" "$OUT/lib/python${SRC_VER%.*}" || die "拷贝标准库失败" "$PYDIR"

N_LIB=$(find "$OUT/lib/python${SRC_VER%.*}" -type f | wc -l)
N_EXT=$(find "$OUT/lib/python${SRC_VER%.*}" -name '*.so' 2>/dev/null | wc -l)
note "标准库随件: lib/python${SRC_VER%.*}/（$N_LIB 个文件，含 $N_EXT 个扩展模块）"
[ "$N_EXT" -gt 0 ] || note "提示：扩展模块 0 个 —— 解释器能用，但 _socket/_ssl/ctypes 等不可用"

[ -d "$OUT/lib/python${SRC_VER%.*}/lib-dynload" ] \
  && note "lib-dynload 在标准库内（形态正确）" \
  || note "提示：lib-dynload 不在标准库内 —— 扩展模块的顶层入口可能找不到"

printf '%s' "$SRC_VER" > "$OUT/$TOOL.version"
echo "[ok] $OUT/bin/$TOOL $(stat -c%s "$OUT/bin/$TOOL") 字节（aarch64、16KB 对齐合格、无 glibc 依赖）"
echo "[$TOOL] 标准库与扩展模块随件走 → 商店 COMPONENT 通道"
echo "[$TOOL] 判据要真跑一段 Python 并 import 标准库（起得来 ≠ 能用）"