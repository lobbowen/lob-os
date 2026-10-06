#!/usr/bin/env bash
# CMake —— 开发环境件（阶段1d）。
#
# ── 形态：自举（bootstrap），不靠宿主 cmake ──
# cmake 自己就是构建系统，所以「怎么编 cmake」是个真问题。三条路：
#   (a) 用宿主 cmake 交叉编        → 需要宿主有 cmake（runner 有，但那是额外的依赖）
#   (b) autoreconf + configure     → cmake 不是 autotools 包，这条不成立
#   (c) **自带 bootstrap 脚本**     → 2111 行 /bin/sh，直接用编译器编源码
# 走 (c)。核实过 bootstrap 接受 CC= / CXX= / CFLAGS= / CXXFLAGS= 参数
# （bootstrap:1015-1018），且不认 CMAKE_TOOLCHAIN_FILE —— 所以交叉参数
# 走环境/命令行，而不是 toolchain 文件。
#
# 这比 make 还独立一层：make 需要宿主有 make，cmake 连那个都不需要。
#
# ── 为什么不用官方的预编译包 ──
# CMake 官方**有** `cmake-<ver>-linux-aarch64.tar.gz`（实测 4.4.4 那份 49.6 MiB），
# 但它是 **glibc** 构建。实测它的 NEEDED：
#   libdl.so.2 / librt.so.1 / libpthread.so.0 / libc.so.6
#   / ld-linux-aarch64.so.1
# Bionic 上这些**一个都没有**（它是 libc.so / libdl.so，pthread 与 rt 并进 libc，
# 解释器是 /system/bin/linker64），外加 glibc 的 GLIBC_2.x 版本化符号。
# 与 LLVM 那份是同一形态：要在 Android 上跑就得装 glibc 兼容层，
# 而我们要的是原生 Android 系统。所以交叉编译。
set -euo pipefail
export LC_ALL=C

HERE="$(dirname "$0")"
cd "$HERE/.."
ROOT_DIR="$(pwd)"

ABI="${ABI:-arm64-v8a}"
API="${ANDROID_API:-23}"
JOBS="${JOBS:-4}"
TOOL="cmake"
OUT="${OUT:-dist}"
case "$OUT" in /*) ;; *) OUT="$ROOT_DIR/$OUT" ;; esac

die() { echo "::error title=$1::${2:-}"; exit 1; }
note() { echo "[$TOOL] $*"; }

# bootstrap 用 CC/CXX 编 C++，所以要的是 **C++ 编译器**（clang++），
# 且它得在 PATH 里（bootstrap 按名字调，不接受绝对路径之外的形式）。
[ -n "${CC:-}" ] || die "缺 CC" "需要 NDK 的 clang（build-userland.yml 的「定位 NDK」步会注入）"
TC="$(dirname "$CC")"
CXX="${CXX:-$TC/aarch64-linux-android${API}-clang++}"
LLVM_STRIP="${LLVM_STRIP:-$TC/llvm-strip}"
LLVM_READELF="${LLVM_READELF:-$TC/llvm-readelf}"
[ -x "$CXX" ] || die "缺 C++ 编译器" "$CXX 不存在 —— bootstrap 编的是 C++ 源码，只有 clang 不够"
command -v make >/dev/null 2>&1 || die "缺 make" "bootstrap 靠 make 驱动编译（runner 自带；缺了请 apt-get install make）"
for t in "$LLVM_STRIP" "$LLVM_READELF"; do
  [ -x "$t" ] || die "缺工具" "$t 不存在"
done

mkdir -p "$OUT/bin"
WORK="$ROOT_DIR/work/$TOOL"
mkdir -p "$WORK"

# ── 取源码 ──
CMAKE_VER="$(bash "$ROOT_DIR/scripts/fetch-pinned.sh" --src-version $TOOL)"
SRC="$WORK/$TOOL-src"
if [ ! -d "$SRC" ]; then
  TGZ="$WORK/$TOOL.tar.gz"
  note "取 $TOOL $CMAKE_VER 源码（仓内唯一入口，sha256 逐字节校验）"
  bash "$ROOT_DIR/scripts/fetch-pinned.sh" --pin $TOOL "$TGZ" || die "取源码失败" "钉值见 userland-sources.json"
  rm -rf "$SRC" && mkdir -p "$SRC"
  tar xzf "$TGZ" -C "$SRC" --strip-components=1 || die "解包失败" "$TGZ"
fi
[ -x "$SRC/bootstrap" ] || die "源码树异常" \
  "缺 bootstrap —— CMake 的发布包自带它（2111 行 /bin/sh，直接用编译器编源码）。没有它就得靠宿主 cmake。"
# 版本是三段分开写的（MAJOR/MINOR/PATCH，核实过 Source/CMakeVersion.cmake:2-4），
# 不是一行 `set(CMake_VERSION 4.4.4)`。早先写成单行 sed 取到的是 MAJOR 那一个值，
# 于是「版本不符」判死 —— 判据自己写错，不是源码有问题。
GOT_VER="$(awk -F'[ ()]' '
  /^set\(CMake_VERSION_MAJOR/ {maj=$3}
  /^set\(CMake_VERSION_MINOR/ {min=$3}
  /^set\(CMake_VERSION_PATCH/ {pat=$3}
  END {gsub(/[ \t]/,"",maj); gsub(/[ \t]/,"",min); gsub(/[ \t]/,"",pat);
       print maj"."min"."pat}
' "$SRC/Source/CMakeVersion.cmake")"
[ "$GOT_VER" = "$CMAKE_VER" ] || die "版本不符" \
  "钉的是 $CMAKE_VER，Source/CMakeVersion.cmake 三段拼出 $GOT_VER（钉值写错或源站给了别的版本）"
note "源码 $GOT_VER 就位（自带 bootstrap，无需宿主 cmake）"

# ── bootstrap（交叉编译 cmake 本体）──
BUILD="$WORK/build"
INST="$WORK/_inst"
rm -rf "$BUILD" "$INST" && mkdir -p "$BUILD" "$INST"

# bootstrap 按**名字**调编译器，所以要把 NDK 的 bin 放进 PATH。
# CXXFLAGS 里给 -static：工具件不该依赖任何共享库（同 make/busybox 的理由）。
(
  set -e
  cd "$BUILD"
  PATH="$TC:$PATH" \
  "$SRC/bootstrap" \
    --prefix="$INST" \
    --parallel="$(nproc 2>/dev/null || echo 4)" \
    CC="$CC" \
    CXX="$CXX" \
    CFLAGS="-O2 -D__ANDROID_API__=$API" \
    CXXFLAGS="-O2 -D__ANDROID_API__=$API -static" \
    > "$WORK/bootstrap.log" 2>&1 \
    || { echo "=== bootstrap 失败取证（error 行 + 末 50 行）==="; \
         grep -nE "error:|Error [0-9]+$|undefined (symbol|reference)|CMake Error" "$WORK/bootstrap.log" | head -25 || true; \
         tail -50 "$WORK/bootstrap/bootstrap.log" 2>/dev/null | tail -40 || tail -50 "$WORK/bootstrap.log"; \
         exit 1; }
)
note "bootstrap 完成"

# ── 形态自检 ──
BIN="$INST/bin/cmake"
[ -x "$BIN" ] || {
  echo "=== 找 cmake 可执行 ==="
  find "$INST" -maxdepth 3 -name cmake -type f 2>/dev/null | head -5
  die "没产出 cmake" "$BIN 不存在"
}
SIZE=$(stat -c%s "$BIN")
[ "$SIZE" -gt 500000 ] || die "产物可疑" "cmake 只有 $SIZE 字节 —— 静态编不该这么小"
INFO=$(file -b "$BIN")
case "$INFO" in *aarch64*|*arm64*|*ARM64*) : ;; *) die "架构不对" "$INFO" ;; esac
"$LLVM_STRIP" --strip-unneeded "$BIN" 2>/dev/null || true

# 静态编：不该有 PT_DYNAMIC。真机动态链失败是「起不来」的最常见形态。
DYN="$("$LLVM_READELF" -W -l "$BIN" 2>/dev/null | awk '/^[[:space:]]*DYNAMIC/{print "y"}')"
[ -z "$DYN" ] || {
  NEEDED="$("$LLVM_READELF" -W -d "$BIN" 2>/dev/null | sed -n 's/.*NEEDED.*\[\(.*\)\].*/\1/p' | tr '\n' ' ')"
  die "不是静态产物" "有 PT_DYNAMIC（NEEDED: ${NEEDED:-?}）—— 工具件不该依赖任何共享库"
}

# 16KB 对齐（Android 15+ 硬要求）
BAD="$("$LLVM_READELF" -W -l "$BIN" 2>/dev/null | awk '/^[[:space:]]*LOAD/{print $NF}' \
      | while read -r a; do
          case "$a" in 0x[0-9a-fA-F]*) ;; *) continue ;; esac
          d=$(( a )); [ "$d" -eq 0 ] && continue
          [ $(( d % 16384 )) -ne 0 ] && printf ' %s' "$a"
        done)"
[ -z "$BAD" ] || die "16KB 对齐不合格" "这些 LOAD 段：$BAD"

# cmake 还需要 share/ 下的模块与模板 —— 只拷 bin/ 会得到一个「起得来但什么都干不了」的 cmake。
# 形态判据不是「文件在不在」，而是「模块目录在不在」。
MODDIR=""
for cand in "$INST/share/cmake" "$INST/share/cmake-4.4"; do
  [ -d "$cand/Modules" ] && MODDIR="$cand" && break
done
[ -n "$MODDIR" ] || {
  echo "=== $INST/share 下有什么 ==="; ls "$INST/share" 2>/dev/null | head -5
  die "缺 cmake 模块目录" "只带 bin/cmake 的 cmake 是空壳（起得来但什么都干不了）"
}

cp -f "$BIN" "$OUT/bin/$TOOL"
chmod 0755 "$OUT/bin/$TOOL"
# 模块随件走：拷到 dist/ 下与 bin/ 平级，落位后由安装器铺到同一前缀
rm -rf "$OUT/share" && mkdir -p "$OUT/share"
cp -a "$MODDIR" "$OUT/share/cmake"

printf '%s' "$CMAKE_VER" > "$OUT/$TOOL.version"
echo "[ok] $OUT/bin/$TOOL $(stat -c%s "$OUT/bin/$TOOL") 字节（静态、无 PT_DYNAMIC、16KB 对齐、aarch64）"
echo "[ok] cmake 模块目录 → $OUT/share/cmake（缺它 cmake 就是空壳）"
echo "[$TOOL] 落位：商店 COMPONENT 通道 → files/programs/$TOOL/<版本>/"
echo "[$TOOL] 判据要真跑一次 cmake（起得来不等于能 configure 东西）"