#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

HERE="$(dirname "$0")"
cd "$HERE/.."
ROOT_DIR="$(pwd)"

ABI="${ABI:-arm64-v8a}"
API="${ANDROID_API:-35}"
JOBS="${JOBS:-4}"
TOOL="cmake"
OUT="${OUT:-dist}"
case "$OUT" in /*) ;; *) OUT="$ROOT_DIR/$OUT" ;; esac

die() {
  local title="$1"; shift
  echo "::error title=$title::$(printf '%s\n' "$@")"
  exit 1
}
note() { echo "[$TOOL] $*"; }

[ -n "${CC:-}" ] || die "缺 CC" "需要 NDK 的 clang（build-userland.yml 的「定位 NDK」步会注入）"
TC="$(dirname "$CC")"
CXX="${CXX:-$TC/aarch64-linux-android${API}-clang++}"
LLVM_STRIP="${LLVM_STRIP:-$TC/llvm-strip}"
LLVM_READELF="${LLVM_READELF:-$TC/llvm-readelf}"
NDK_ROOT="$(cd "$TC/../../../../.." && pwd)"
[ -d "$NDK_ROOT" ] || die "定位 NDK 失败" "从 clang 路径反推得到 '$NDK_ROOT'，它不是目录（CC=$CC）"
[ -x "$CXX" ] || die "缺 C++ 编译器" "$CXX 不存在 —— cmake 是 C++ 程序，只有 clang 不够"
for t in "$LLVM_STRIP" "$LLVM_READELF"; do
  [ -x "$t" ] || die "缺工具" "$t 不存在"
done

mkdir -p "$OUT/bin"
WORK="$ROOT_DIR/work/$TOOL"
mkdir -p "$WORK"

CMAKE_VER="$(bash "$ROOT_DIR/scripts/fetch-pinned.sh" --src-version $TOOL)"
SRC="$WORK/$TOOL-src"
if [ ! -d "$SRC" ]; then
  TGZ="$WORK/$TOOL.tar.gz"
  note "取 $TOOL $CMAKE_VER 源码（仓内唯一入口，sha256 逐字节校验）"
  bash "$ROOT_DIR/scripts/fetch-pinned.sh" --pin $TOOL "$TGZ" || die "取源码失败" "钉值见 userland-sources.json"
  rm -rf "$SRC" && mkdir -p "$SRC"
  tar xzf "$TGZ" -C "$SRC" --strip-components=1 || die "解包失败" "$TGZ"
fi
[ -f "$SRC/Source/CMakeVersion.cmake" ] || die "源码树异常" \
  "缺 Source/CMakeVersion.cmake —— 版本核对与交叉编都要用它，缺了说明解包不对。"
GOT_VER="$(awk -F'[ ()]' '
  /^set\(CMake_VERSION_MAJOR/ {maj=$3}
  /^set\(CMake_VERSION_MINOR/ {min=$3}
  /^set\(CMake_VERSION_PATCH/ {pat=$3}
  END {gsub(/[ \t]/,"",maj); gsub(/[ \t]/,"",min); gsub(/[ \t]/,"",pat);
       print maj"."min"."pat}
' "$SRC/Source/CMakeVersion.cmake")"
[ "$GOT_VER" = "$CMAKE_VER" ] || die "版本不符" \
  "钉的是 $CMAKE_VER，Source/CMakeVersion.cmake 三段拼出 $GOT_VER（钉值写错或源站给了别的版本）"
note "源码 $GOT_VER 就位"

BUILD="$WORK/build"
INST="$WORK/_inst"
rm -rf "$BUILD" "$INST" && mkdir -p "$BUILD" "$INST"

command -v cmake >/dev/null 2>&1 || die "缺宿主 cmake" \
  "CMake 的 bootstrap 编完测试程序必定 ./\$TMPFILE 执行它（cmake_try_run()），交叉编出的是 aarch64，在 x86_64 宿主上必然 Exec format error —— bootstrap 没有交叉模式。所以必须用宿主 cmake 交叉编：build job 需要 apt-get install cmake。"
HOST_CMAKE_VER="$(cmake --version | head -1)"

cat > "$WORK/toolchain.cmake" <<EOF
set(CMAKE_SYSTEM_NAME Android)
set(CMAKE_SYSTEM_VERSION 21)
set(CMAKE_ANDROID_ARCH_ABI arm64-v8a)
set(CMAKE_ANDROID_NDK $NDK_ROOT)
set(CMAKE_C_COMPILER   $CC)
set(CMAKE_CXX_COMPILER $CXX)
set(CMAKE_C_FLAGS   "-O2 -D__ANDROID_API__=$API")
set(CMAKE_CXX_FLAGS "-O2 -D__ANDROID_API__=$API")
EOF
note "宿主 cmake：$HOST_CMAKE_VER；交叉编（toolchain 文件 → $WORK/toolchain.cmake）"

(
  set -e
  cd "$BUILD"
  cmake -S "$SRC" -B "$BUILD" \
    -DCMAKE_TOOLCHAIN_FILE="$WORK/toolchain.cmake" \
    -DCMAKE_INSTALL_PREFIX="$INST" \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_TESTING=OFF \
    > "$WORK/configure.log" 2>&1 \
    || { echo "=== cmake configure 失败取证（末 50 行）==="; tail -50 "$WORK/configure.log"; exit 1; }
  cmake --build "$BUILD" -j"$JOBS" > "$WORK/build.log" 2>&1 \
    || { echo "=== cmake 编译失败取证（error 行 + 末 50 行）==="; \
         grep -nE "error:|Error [0-9]+$|undefined (symbol|reference)" "$WORK/build.log" | head -25 || true; \
         tail -50 "$WORK/build.log"; exit 1; }
  cmake --install "$BUILD" > "$WORK/install.log" 2>&1 \
    || { echo "=== cmake install 失败取证（末 40 行）==="; tail -40 "$WORK/install.log"; exit 1; }
)
note "cmake 交叉编完成"

BIN="$INST/bin/cmake"
[ -x "$BIN" ] || {
  echo "=== 找 cmake 可执行 ==="
  find "$INST" -maxdepth 3 -name cmake -type f 2>/dev/null | head -5
  die "没产出 cmake" "$BIN 不存在"
}
INFO=$(file -b "$BIN")
case "$INFO" in *aarch64*|*arm64*|*ARM64*) : ;; *) die "架构不对" "$INFO" ;; esac
"$LLVM_STRIP" --strip-unneeded "$BIN" 2>/dev/null || true

bash "$ROOT_DIR/scripts/check-elf-deps.sh" "$BIN" "$TOOL"

BAD="$("$LLVM_READELF" -W -l "$BIN" 2>/dev/null | awk '/^[[:space:]]*LOAD/{print $NF}' \
      | while read -r a; do
          case "$a" in 0x[0-9a-fA-F]*) ;; *) continue ;; esac
          d=$((a))
          if [ "$d" -eq 0 ]; then continue; fi
          if [ $(( d % 16384 )) -ne 0 ]; then printf ' %s' "$a"; fi
        done)"
[ -z "$BAD" ] || die "16KB 对齐不合格" "这些 LOAD 段：$BAD"

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
rm -rf "$OUT/share" && mkdir -p "$OUT/share"
cp -a "$MODDIR" "$OUT/share/cmake"

printf '%s' "$CMAKE_VER" > "$OUT/$TOOL.version"
echo "[ok] $OUT/bin/$TOOL $(stat -c%s "$OUT/bin/$TOOL") 字节（动态、依赖闭环、16KB 对齐、aarch64）"
echo "[ok] cmake 模块目录 → $OUT/share/cmake（缺它 cmake 就是空壳）"
echo "[$TOOL] 落位：商店 COMPONENT 通道 → files/programs/$TOOL/<版本>/"
echo "[$TOOL] 判据要真跑一次 cmake（起得来不等于能 configure 东西）"