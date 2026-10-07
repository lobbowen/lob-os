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
[ -x "$CXX" ] || die "缺 C++ 编译器" "$CXX 不存在 —— bootstrap 编的是 C++ 源码，只有 clang 不够"
command -v make >/dev/null 2>&1 || die "缺 make" "bootstrap 靠 make 驱动编译（runner 自带；缺了请 apt-get install make）"
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
[ -x "$SRC/bootstrap" ] || die "源码树异常" \
  "缺 bootstrap —— CMake 的发布包自带它（2111 行 /bin/sh，直接用编译器编源码）。没有它就得靠宿主 cmake。"
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

BUILD="$WORK/build"
INST="$WORK/_inst"
rm -rf "$BUILD" "$INST" && mkdir -p "$BUILD" "$INST"

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