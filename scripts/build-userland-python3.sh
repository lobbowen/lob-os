#!/usr/bin/env bash
# CPython —— 开发环境件（阶段1d 最后一件，也是最难的一件）。
#
# ── 为什么它比 make/cmake/pkg-config 难 ──
# 那三件都是「自带构建系统、跑一次 configure + make」的独立工具。
# CPython 的 configure 会**编译并运行**一个小程序来探测能力
# （PTY 有没有、.so 能不能 dlopen…），交叉编译时目标程序在 x86_64 上跑不了。
#
# CPython 为此专门支持交叉编译（实测 configure 里有 64 处 cross_compiling 引用）：
#   ① --with-build-python=<path>  指定**宿主** python 跑构建期脚本
#   ② ac_cv_file__dev_ptmx 等 cachevar  手工喂探测结果，跳过运行测试
# ③ 探测「能不能运行目标程序」时按 cross_compiling 分支走（configure 自己判，
#      不额外传参；传错反而会把它引到「假定不能运行」以外的分支）
#
# ── 为什么用 .tgz 而不是官方主推的 .tar.xz ──
# 本会话的开发机**没有 xz**（node 的 zlib 也不含 LZMA），而
# fetch-pinned.sh:147 是逐字节比对 —— 两种压缩的 sha256 不同，
# 混列必然失败。所以只钉 .tgz（同一份源码的另一种压缩，本机可解可核实）。
# CI 上若有 xz 想换更快的那份，需重测 sha256 并改这一格。
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

die() { echo "::error title=$1::${2:-}"; exit 1; }
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

# 宿主 python3：configure 的构建期脚本要它（--with-build-python）
HOST_PY="${BUILD_PYTHON:-}"
if [ -z "$HOST_PY" ]; then
  for c in python3 python; do
    command -v "$c" >/dev/null 2>&1 && HOST_PY="$(command -v "$c")" && break
  done
fi
[ -n "$HOST_PY" ] || die "宿主没有 python3" \
  "CPython 的 configure 要用宿主 python 跑构建期脚本（--with-build-python）。CI runner 自带；" \
  "本机跑请先装一个 python3"
HOST_PY_VER="$("$HOST_PY" -c 'import sys;print("%d.%d.%d"%sys.version_info[:3])')"
note "宿主 python: $HOST_PY（$HOST_PY_VER）"

mkdir -p "$OUT/bin"
WORK="$ROOT_DIR/work/$SRC_KEY"
mkdir -p "$WORK"

# ── 取源码 ──
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
# 版本自检：patchlevel.h 三段分开写
GOT_VER="$(awk '
  /#define PY_MAJOR_VERSION/ {a=$3}
  /#define PY_MINOR_VERSION/ {b=$3}
  /#define PY_MICRO_VERSION/ {c=$3}
  END {gsub(/[ \t]/,"",a); gsub(/[ \t]/,"",b); gsub(/[ \t]/,"",c); print a"."b"."c}
' "$SRC/Include/patchlevel.h")"
[ "$GOT_VER" = "$SRC_VER" ] || die "版本不符" \
  "钉的是 $SRC_VER，Include/patchlevel.h 三段拼出 $GOT_VER"
note "源码 $GOT_VER 就位（patchlevel.h 核实）"

# ── configure（交叉编译）──
BUILD="$WORK/build"
rm -rf "$BUILD" && mkdir -p "$BUILD"

# 交叉编译的三个必答项：
#   --host=aarch64-linux-android  产出目标
#   --build=x86_64-pc-linux-gnu   跑 configure 的机器
#   --with-build-python=<宿主>    构建期脚本用宿主 python
#
# 关于「能不能运行目标程序」：**不传** ac_cv_prog_cc_cross=yes ——
# configure 自己会因 --host != --build 判定 cross_compiling 并走对应分支。
# 显式传反而可能把它引到别的路径。
#
# ensurepip 关掉：它要在目标机器上装 pip（要跑 zipapp 与网络），
# 那是设备上的事，不是编译期该做的。pip 可另作一个件。
# pymalloc 保持默认（它不依赖 glibc 特有行为）。
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

# 核实它真的认了交叉编译（而不是静默按宿主配置）
grep -q 'cross_compiling *= *yes' "$WORK/configure.log" 2>/dev/null \
  || grep -q 'cross_compiling:.*yes' "$WORK/configure.log" 2>/dev/null \
  || note "注意：configure.log 里没直接看到 cross_compiling 的判定（格式随版本变）；下面的产物架构自检会兜住"

# ── 编 ──
make -C "$BUILD" -j"$JOBS" > "$WORK/build.log" 2>&1 \
  || { echo "=== 编译失败取证（error 行 + 末 50 行）==="; \
       grep -nE "error:|Error [0-9]+$|undefined (symbol|reference)" "$WORK/build.log" | head -25 || true; \
       tail -50 "$WORK/build.log"; exit 1; }

# CPython 编出来的可执行文件叫 python3.x（不是 python3）→ 收集全部
BIN=""
for cand in "$BUILD"/python3.*; do
  [ -x "$cand" ] && [ -f "$cand" ] && BIN="$cand" && break
done
[ -n "$BIN" ] || { echo "=== build 下有哪些可执行 ==="; ls "$BUILD" | grep -E '^python' | head -5; die "没产出解释器" "$BUILD/python3.* 不存在"; }
cp -f "$BIN" "$OUT/bin/$TOOL"
chmod 0755 "$OUT/bin/$TOOL"

# ── 形态自检 ──
SIZE=$(stat -c%s "$OUT/bin/$TOOL")
[ "$SIZE" -gt 1000000 ] || die "产物可疑" "python3 只有 $SIZE 字节 —— 正常应在数 MB 量级"
INFO=$(file -b "$OUT/bin/$TOOL")
case "$INFO" in *aarch64*|*arm64*|*ARM64*) : ;; *) die "架构不对" "$INFO —— configure 可能按宿主 x86_64 配了"; esac

# python3 **必须**动态链 libc + libdl（它大量用 dlopen 载扩展模块）——
# 所以这里不判「静态」，而是判「依赖是 Bionic 认得的那几个」。
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

# 标准库与扩展模块：**调 make install 拿 CPython 自己算好的布局**，不要手写。
#
# 早先我只「判它存在」而不拷 —— 件装到设备上是空壳解释器：
# 起得来、--version 有输出，一 import 就死。而 python3 的价值恰恰在标准库。
# （cmake 的 share/cmake 同理，那边拷了，这边漏了。）
#
# 为什么不手写「把 config-3.x-<triplet> 那层去掉」：
#   build/lib/python3.14/config-3.14-aarch64-linux-android/  ← 构建期中间形态
#   <prefix>/lib/python3.14/                                  ← 安装后的形态
# 这个「去掉」是 CPython 自己的安装规则，我手写 mv 就是猜它。
# 它自己算出来的布局不会错。
PREFIX="$WORK/_inst"
make -C "$BUILD" install > "$WORK/install.log" 2>&1 \
  || { echo "=== install 失败（末 30 行）==="; tail -30 "$WORK/install.log"; exit 1; }

PYDIR="$PREFIX/lib/python${SRC_VER%.*}"
[ -d "$PYDIR" ] || {
  echo "=== $PREFIX/lib 下有什么 ==="; ls -d "$PREFIX"/lib/* 2>/dev/null | head -5
  die "找不到安装后的标准库目录" \
    "按 CPython 的安装规则应在 $PREFIX/lib/python${SRC_VER%.*}/；缺了它解释器起得来但 import 就死"
}
# os.py 在不在 —— 形态对的最终判据（目录名对了但里面空掉，是另一种坏）
[ -f "$PYDIR/os.py" ] || die "标准库目录里没有 os.py" "$PYDIR —— 目录在但内容不对（构建没跑完？）"

rm -rf "$OUT/lib"
mkdir -p "$OUT/lib"
cp -a "$PYDIR" "$OUT/lib/python${SRC_VER%.*}" || die "拷贝标准库失败" "$PYDIR"

N_LIB=$(find "$OUT/lib/python${SRC_VER%.*}" -type f | wc -l)
N_EXT=$(find "$OUT/lib/python${SRC_VER%.*}" -name '*.so' 2>/dev/null | wc -l)
note "标准库随件: lib/python${SRC_VER%.*}/（$N_LIB 个文件，含 $N_EXT 个扩展模块）"
[ "$N_EXT" -gt 0 ] || note "提示：扩展模块 0 个 —— 解释器能用，但 _socket/_ssl/ctypes 等不可用"

# ── lib-dynload：扩展模块的搜索目录，必须与 lib/pythonX.Y 同前缀 ──
# 缺它时扩展模块能 import 但顶层 _socket 之类找不到（它们走 lib-dynload 的搜索路径）
[ -d "$OUT/lib/python${SRC_VER%.*}/lib-dynload" ] \
  && note "lib-dynload 在标准库内（形态正确）" \
  || note "提示：lib-dynload 不在标准库内 —— 扩展模块的顶层入口可能找不到"

printf '%s' "$SRC_VER" > "$OUT/$TOOL.version"
echo "[ok] $OUT/bin/$TOOL $(stat -c%s "$OUT/bin/$TOOL") 字节（aarch64、16KB 对齐合格、无 glibc 依赖）"
echo "[$TOOL] 标准库与扩展模块随件走 → 商店 COMPONENT 通道"
echo "[$TOOL] 判据要真跑一段 Python 并 import 标准库（起得来 ≠ 能用）"