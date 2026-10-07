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

PATCH_FILE="$ROOT_DIR/patches/cmake-cmlibuv-no-cpumask-on-android.patch"
[ -f "$PATCH_FILE" ] || die "缺 libuv 补丁" "$PATCH_FILE 不存在 —— 没有它 cmake 会编不过（core.c:1683 CPU_SETSIZE）"
if ! patch -p1 -d "$SRC" -i "$PATCH_FILE"; then
  die "libuv 补丁打不上" "为什么需要它：clang 的 Android target 预定义 __linux__（实测 __linux__/__ANDROID__/__BIONIC__ 都是 1），Bionic 的 <sched.h> 不提供 cpu_set_t/CPU_SETSIZE/sched_getaffinity，libuv 只看 __linux__ 于是编不过（core.c:1683）。改法同 Termux 的 libuv 补丁。不选 -U__linux__ 是因为那全局生效，libcurl/zstd/c-ares 也可能依赖它。CMake $GOT_VER 的 Utilities/cmlibuv/src/unix/internal.h 与补丁不匹配 —— 补丁是按 $CMAKE_VER 写的。换 CMake 版本时要一起更新 patches/ 下这个文件。"
fi
note "libuv 补丁已打（Android 上关掉 CPU affinity）"

LF_SRC="$ROOT_DIR/patches/cmake-cmlibarchive-contrib"
[ -f "$LF_SRC/android_lf.h" ] || die "缺 android_lf.h" \
  "$LF_SRC/android_lf.h 不存在 —— 没有它 cmake 会编不过（libarchive/archive.h:121）。该文件取自 libarchive 上游 contrib/android/include/，sha256 见同目录 SHA256"
if command -v sha256sum >/dev/null 2>&1; then
  ( cd "$LF_SRC" && sha256sum -c SHA256 >/dev/null 2>&1 ) \
    || die "android_lf.h 校验失败" "$LF_SRC/SHA256 与实际内容不符 —— 改了补丁目录里的文件就要更新 SHA256"
fi
mkdir -p "$SRC/Utilities/cmlibarchive/contrib/android/include"
cp -f "$LF_SRC/android_lf.h" "$SRC/Utilities/cmlibarchive/contrib/android/include/android_lf.h"
note "android_lf.h 已就位 —— CMake 只搬了 cmlibarchive/libarchive 子目录、没带 contrib/，但它 CMakeLists（原样搬自 libarchive 上游）第 8-10 行写着 include_directories(\${PROJECT_SOURCE_DIR}/contrib/android/include)，而 archive.h:121 在 __ANDROID__ 时要 include 它。补上那行 include 才成立。"

BUILD="$WORK/build"
INST="$WORK/_inst"
rm -rf "$BUILD" "$INST" && mkdir -p "$BUILD" "$INST"

command -v cmake >/dev/null 2>&1 || die "缺宿主 cmake" \
  "CMake 的 bootstrap 编完测试程序必定 ./\$TMPFILE 执行它（cmake_try_run()），交叉编出的是 aarch64，在 x86_64 宿主上必然 Exec format error —— bootstrap 没有交叉模式。所以必须用宿主 cmake 交叉编：build job 需要 apt-get install cmake。"
HOST_CMAKE_VER="$(cmake --version | head -1)"

ANDROID_TOOLCHAIN="$NDK_ROOT/build/cmake/android.toolchain.cmake"
[ -f "$ANDROID_TOOLCHAIN" ] || die "NDK 缺 android.toolchain.cmake" \
  "路径 '$ANDROID_TOOLCHAIN' 不存在 —— CMake 交叉编 Android 必需它（llvmtoolchain 件用的是同一个文件）。不要自造 toolchain 文件：漏掉 CMAKE_SYSROOT 时 configure 会报 'The C++ compiler does not support C++11 (e.g. std::unique_ptr)'，因为空程序只靠 -std 能编过、要 <memory> 的就编不过 —— 那是找不到 Bionic 头文件，不是编译器不支持 C++11。NDK 的 android.toolchain.cmake 末尾设了 CMAKE_SYSROOT，注释写着它让 CMake 自动传 --sysroot。"
SYSROOT_DIR="$(ls -d "$NDK_ROOT"/toolchains/llvm/prebuilt/*/sysroot 2>/dev/null | head -1)"
note "宿主 cmake：$HOST_CMAKE_VER；toolchain=$ANDROID_TOOLCHAIN；sysroot=${SYSROOT_DIR:-（未找到）}"
note "宿主 cmake：$HOST_CMAKE_VER；交叉编（NDK toolchain）"

(
  set -e
  cd "$BUILD"
  cmake -S "$SRC" -B "$BUILD" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_TOOLCHAIN" \
    -DANDROID_NDK="$NDK_ROOT" \
    -DANDROID_ABI="arm64-v8a" \
    -DANDROID_PLATFORM="android-$API" \
    -DCMAKE_INSTALL_PREFIX="$INST" \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_TESTING=OFF \
    > "$WORK/configure.log" 2>&1 \
    || { echo "=== cmake configure 失败取证 ==="
         echo "--- toolchain=$ANDROID_TOOLCHAIN sysroot=${SYSROOT_DIR:-（无）} ---"
         echo "--- configure.log 末 50 行 ---"; tail -50 "$WORK/configure.log"
         echo "--- CMake 认定的编译器与 sysroot（从 CMakeCache.txt 取，不看日志措辞）---"
         grep -E '^CMAKE_(C|CXX)_COMPILER:|^CMAKE_SYSROOT:|^CMAKE_CXX_FLAGS' "$BUILD/CMakeCache.txt" 2>/dev/null | head -8 || echo "  （CMakeCache.txt 还不存在，说明失败在 cache 生成之前）"
         exit 1; }
  cmake --build "$BUILD" -j"$JOBS" > "$WORK/build.log" 2>&1 \
    || { echo "=== cmake 编译失败取证 ==="
         echo "--- error 行 ---"
         grep -nE "error:|Error [0-9]+$|undefined (symbol|reference)" "$WORK/build.log" | head -25 || true
         echo "--- build.log 末 40 行 ---"; tail -40 "$WORK/build.log"
         echo "--- CMake 检出的关键变量（决定条件编译分支的依据）---"
         for f in "$WORK/configure.log" "$BUILD/CMakeCache.txt"; do
           if [ -f "$f" ]; then
             HIT="$(grep -iE 'CPU_AFFINITY|HAVE_SCHED|UV__|_GNU_SOURCE' "$f" 2>/dev/null | head -12 || true)"
             if [ -n "$HIT" ]; then
               echo "  [$f]"; echo "$HIT" | sed 's/^/    /'
             else
               echo "  [$f] 里没有这些 —— 它们可能由 CMakeLists 直接写入 cache，或由检测结果推导"
             fi
           else
             echo "  [$f] 不存在"
           fi
         done
         echo "--- 实际用的编译命令（取 core.c 那一条）---"
         CMD="$(grep -m1 -oE '[^ ]*clang[^ ]* .*core\.c[^ ]*' "$WORK/build.log" 2>/dev/null | head -c 500 || true)"
         if [ -n "$CMD" ]; then echo "$CMD"; else echo "  build.log 里没有 core.c 的命令行（gmake 默认不打完整命令，加 VERBOSE=1 再看）"; fi
         echo "--- 编译器预定义宏里有没有 __linux__（libuv 的 UV__CPU_AFFINITY_SUPPORTED 靠它，Bionic 不提供 cpu_set_t）---"
         printf '#include <stdio.h>\nint main(void){return 0;}\n' > "$WORK/macro.c"
         if "$CC" -dM -E "$WORK/macro.c" 2>/dev/null > "$WORK/macros.txt"; then
           HIT="$(grep -E '^#define (__linux__|__ANDROID__|__BIONIC__|ANDROID|__ANDROID_API__|__ANDROID_MIN_SDK_VERSION__)' "$WORK/macros.txt" || true)"
           if [ -n "$HIT" ]; then echo "$HIT" | sed 's/^/    /'; else echo "    没有 __linux__ / __ANDROID__ / ANDROID 这几个"; fi
           echo "    总宏数：$(wc -l < "$WORK/macros.txt")"
         else
           echo "    取预定义宏失败"
         fi
         exit 1; }
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