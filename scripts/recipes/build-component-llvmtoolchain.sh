#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

HERE="$(dirname "$0")"
cd "$HERE/../.."
ROOT_DIR="$(pwd)"

OUT="${OUT:-dist}"

TOOL="llvm"
ABI="${ABI:-arm64-v8a}"
API="${ANDROID_API:-35}"
JOBS="${JOBS:-4}"
PROJECTS="clang;lld"

case "$OUT" in /*) ;; *) OUT="$ROOT_DIR/$OUT" ;; esac
TOOLDIR="$OUT/bin"
STAGE="$OUT/.$TOOL-stage"

die() {
  local title="$1"; shift
  echo "::error title=$title::$(printf '%s\n' "$@")"
  exit 1
}
note() { echo "[$TOOL] $*"; }

[ -n "${CC:-}" ] || die "缺 CC" "需要 NDK 的 clang —— 它是编译 LLVM 的那个编译器（宿主 x86_64）"
TC="$(dirname "$CC")"
LLVM_AR="$TC/llvm-ar"
LLVM_RANLIB="$TC/llvm-ranlib"
LLVM_STRIP="${LLVM_STRIP:-$TC/llvm-strip}"
LLVM_READELF="${LLVM_READELF:-$TC/llvm-readelf}"
CMAKE="${CMAKE:-cmake}"
NINJA="${NINJA:-ninja}"
for t in "$LLVM_AR" "$LLVM_RANLIB" "$LLVM_READELF"; do
  [ -x "$t" ] || die "缺工具" "$t 不存在"
done

for t in "$CMAKE" "$NINJA"; do

  _found=""
  _base="$(basename "$t")"
  _IFS_save="$IFS"; IFS=":"; for _d in $PATH; do
    [ -n "$_d" ] || continue
    if [ -x "$_d/$_base" ]; then _found="$_d/$_base"; break; fi
  done; IFS="$_IFS_save"
  if [ -z "$_found" ]; then

    die "缺工具" \
      "$t 没有 —— 编 LLVM 要 cmake + ninja（runner 预装；缺了请 sudo apt-get install -y cmake ninja-build）；
      那**它**逐字**要**在**那 PATH **上面**、那 CMake 逐字**只**在**那 PATH **上面**找**它**"
  fi
done

NDK_ROOT="$(cd "$TC/../../../../.." && pwd)"
[ -d "$NDK_ROOT" ] || die "定位 NDK 失败" "从 clang 路径反推得到 '$NDK_ROOT'"
ANDROID_TOOLCHAIN="$NDK_ROOT/build/cmake/android.toolchain.cmake"
[ -f "$ANDROID_TOOLCHAIN" ] || die "NDK 缺 android.toolchain.cmake" \
  "路径 '$ANDROID_TOOLCHAIN' 不存在 —— CMake 交叉编 Android 必需它"

note "核实 NDK 与 LLVM 是否同源"
bash "$ROOT_DIR/scripts/verify/verify-ndk-llvm.sh" || exit 1

WANT_LLVM="$(bash "$ROOT_DIR/scripts/toolchain/fetch-pinned.sh" --llvm)"
PINNED_LLVM="$(bash "$ROOT_DIR/scripts/toolchain/fetch-pinned.sh" --src-version llvm 2>/dev/null || true)"
if [ -z "$WANT_LLVM" ]; then
  die "component-sources.json 没有 llvmVersion" \
    "不知道 NDK 内置的是哪个 LLVM，就无法判断钉的源码是否同源 —— 没验过就不该继续编。" \
    "先跑 scripts/verify/verify-ndk-llvm.sh（需要真 NDK，通常在 CI 上），" \
    "从报错里读到实际版本号，填进 component-sources.json 的 llvmVersion。"
fi
if [ -z "$PINNED_LLVM" ]; then
  die "钉值表里没有 sources.llvm" \
    "拿不到要编的 LLVM 版本。用 scripts/registry/pin-github-release.js 钉一个：" \
    "  node scripts/registry/pin-github-release.js --repo llvm/llvm-project --tag llvmorg-<版本> --key llvm --match '^llvm-project-.*[.]src[.]tar[.]xz$'"
fi
WANT_MAJOR="${WANT_LLVM%%.*}"
PIN_MAJOR="${PINNED_LLVM%%.*}"
if [ "$WANT_MAJOR" = "$PIN_MAJOR" ]; then
  note "版本同源已核对：NDK $WANT_LLVM（$WANT_MAJOR.x 线）←→ 钉的 LLVM $PINNED_LLVM"
else
  die "钉的 LLVM 源码与 NDK 内置的不是同一条 release 线" \
    "钉的是 $PINNED_LLVM（$PIN_MAJOR.x 线），NDK 内置 $WANT_LLVM（$WANT_MAJOR.x 线）。" \
    "编出来的 clang 会与 sysroot 的头文件假设错位，而且**不报错**。" \
    "按 NDK 的那条线重钉（sha256 由工具取，不必下载）：" \
    "  node scripts/registry/pin-github-release.js --repo llvm/llvm-project --tag llvmorg-$WANT_MAJOR.1.0 --key llvm --match '^llvm-project-.*[.]src[.]tar[.]xz$'"
fi

WORK="$ROOT_DIR/work/$TOOL"
mkdir -p "$WORK"
SRC="$WORK/llvm-src"
if [ ! -d "$SRC/llvm" ]; then
  TGZ="$WORK/llvm.src.tar.xz"
  note "取 LLVM 源码（走仓内唯一入口，sha256 逐字节校验）"
  bash "$ROOT_DIR/scripts/toolchain/fetch-pinned.sh" --pin llvm "$TGZ" \
    || die "取 LLVM 源码失败" "钉值/来源见 scripts/component-sources.json 的 llvm 键"
  command -v xz >/dev/null 2>&1 || command -v unxz >/dev/null 2>&1 || die "无 xz" \
    "LLVM 官方只发 .tar.xz。请 sudo apt-get install -y xz-utils"
  rm -rf "$SRC" && mkdir -p "$SRC"
  tar xJf "$TGZ" -C "$SRC" --strip-components=1 \
    || die "解包失败" "$TGZ —— 格式是否与 .tar.xz 相符？"

  rm -f "$TGZ"
  note "源码包已删（解包出来的那一份已经在 $SRC 里）—— 不给它留一份重复的"
fi

rm -f "$WORK/llvm.src.tar.xz"
[ -f "$SRC/llvm/CMakeLists.txt" ] || die "源码树异常" "缺 llvm/CMakeLists.txt —— 拿到的不是 llvm-project"
note "源码就位：$(cat "$SRC/llvm/CMakeLists.txt" 2>/dev/null | grep -m1 -o 'project(LLVM)' || echo 'llvm-project')"

BUILD="$WORK/build"
rm -rf "$BUILD"
mkdir -p "$BUILD"

HOST_TB="$WORK/host-tblgen"
rm -rf "$HOST_TB"
note "先编宿主版 tblgen —— 不做这步，LLVM 的 build_native_tool 会把交叉编出来的 llvm-min-tblgen 放进 NATIVE/bin，然后在 x86_64 构建机上执行 aarch64 产物（Exec format error）。"

note "  注意：不能只靠「不设 toolchain 文件」—— locate-ndk.sh 注入的 CC/CXX 在环境里，CMake 会直接采用它们，于是宿主构建也编成 aarch64（实测 host-tblgen/bin/llvm-min-tblgen: Exec format error）。要显式覆盖。"
command -v cc >/dev/null 2>&1 || die "缺宿主 cc" \
  "编宿主 tblgen 需要宿主编译器；runner 自带 /usr/bin/cc。缺了请 apt-get install build-essential"
command -v c++ >/dev/null 2>&1 || die "缺宿主 c++" "同上"

env -u CC -u CXX -u CMAKE_TOOLCHAIN_FILE -u ANDROID_NDK -u ANDROID_NDK_HOME \
  cmake -S "$SRC/llvm" -B "$HOST_TB" -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_C_COMPILER="$(command -v cc)" \
  -DCMAKE_CXX_COMPILER="$(command -v c++)" \
  -DCMAKE_ASM_COMPILER="$(command -v cc)" \
  -DLLVM_TARGETS_TO_BUILD=AArch64 \
  -DLLVM_ENABLE_PROJECTS=clang \
  -DLLVM_INCLUDE_TESTS=OFF \
  -DLLVM_INCLUDE_BENCHMARKS=OFF \
  -DLLVM_INCLUDE_EXAMPLES=OFF \
  -DLLVM_INCLUDE_DOCS=OFF \
  -DLLVM_ENABLE_TERMINFO=OFF \
  -DLLVM_ENABLE_LIBXML2=OFF \
  -DLLVM_ENABLE_LIBEDIT=OFF \
  -DLLVM_ENABLE_ZSTD=OFF \
  > "$WORK/host-configure.log" 2>&1 \
  || { echo "=== 宿主 tblgen 配置失败（末 40 行）==="; tail -40 "$WORK/host-configure.log"; exit 1; }
HOST_CC_USED="$(awk -F= '/^CMAKE_C_COMPILER:[A-Z]+=/ {print $2; exit}' "$HOST_TB/CMakeCache.txt" 2>/dev/null | tr -d '"' || true)"
case "$HOST_CC_USED" in
  ""|*aarch64*|*android*) die "宿主构建仍用了交叉编译器（或读不出编译器）" \
    "CMakeCache.txt 里没读到可用的 CMAKE_C_COMPILER（读到='$HOST_CC_USED'）。它必须是宿主 cc —— CC/CXX 环境变量会被 CMake 采用（locate-ndk.sh 注入了它们），configure 那一步已用 env -u 清掉。若为空，先看 $WORK/host-configure.log 里 CMake 实际选了哪个编译器。";;
esac
note "宿主编译器确认：$HOST_CC_USED"
env -u CC -u CXX -u CMAKE_TOOLCHAIN_FILE -u ANDROID_NDK -u ANDROID_NDK_HOME \
  cmake --build "$HOST_TB" --target llvm-tblgen llvm-min-tblgen clang-tblgen llvm-config \
  -j"$JOBS" > "$WORK/host-build.log" 2>&1 \
  || { echo "=== 宿主 tblgen 编译失败（error 行 + 末 40 行）==="; \
       grep -nE 'error:|Error [0-9]+$' "$WORK/host-build.log" | head -20 || true; \
       tail -40 "$WORK/host-build.log"; exit 1; }
NATIVE_DIR="$WORK/native-tools"
mkdir -p "$NATIVE_DIR"
for t in llvm-tblgen llvm-min-tblgen clang-tblgen llvm-config; do
  [ -x "$HOST_TB/bin/$t" ] || die "宿主 tblgen 没产出" "$HOST_TB/bin/$t 不存在 —— 交叉构建需要它来生成 .inc 文件"
  cp -f "$HOST_TB/bin/$t" "$NATIVE_DIR/$t"
done
note "宿主 tblgen 就位：$NATIVE_DIR（$(ls "$NATIVE_DIR" | tr '\n' ' ')）"

rm -rf "$HOST_TB"
note "宿主 tblgen 构建树已删（只留 $NATIVE_DIR 那 4 个可执行文件）—— 目标编那一族开始前先把磁盘腾出来"

cmake -S "$SRC/llvm" -B "$BUILD" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_TOOLCHAIN" \
  -DLLVM_NATIVE_TOOL_DIR="$NATIVE_DIR" \
  -DLLVM_TABLEGEN="$NATIVE_DIR/llvm-tblgen" \
  -DLLVM_USE_HOST_TOOLS=ON \
  -DANDROID_ABI="$ABI" -DANDROID_PLATFORM="android-$API" \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_INSTALL_PREFIX="$STAGE/prefix" \
  -DCMAKE_INSTALL_BINDIR=bin \
  -DLLVM_TARGETS_TO_BUILD=AArch64 \
  -DLLVM_ENABLE_PROJECTS="$PROJECTS" \
  -DLLVM_TOOL_BUGPOINT_PASSES_BUILD=OFF \
  -DLLVM_INCLUDE_TESTS=OFF \
  -DLLVM_INCLUDE_BENCHMARKS=OFF \
  -DLLVM_INCLUDE_EXAMPLES=OFF \
  -DLLVM_INCLUDE_DOCS=OFF \
  -DLLVM_ENABLE_TERMINFO=OFF \
  -DLLVM_ENABLE_ZLIB=OFF \
  -DLLVM_ENABLE_LIBXML2=OFF \
  -DLLVM_ENABLE_LIBEDIT=OFF \
  -DLLVM_ENABLE_ZSTD=OFF \
  -DCLANG_ENABLE_STATIC_ANALYZER=OFF \
  -DCOMPILER_RT_BUILD_BUILTINS=ON \
  -DCOMPILER_RT_INCLUDE_BUILTINS=ON \
  -DLLVM_PARALLEL_LINK_JOBS=2 \
  -DLLVM_ENABLE_ASSERTIONS=OFF \
  > "$WORK/cmake.log" 2>&1 \
  || { echo "=== LLVM cmake 配置失败取证（末 40 行）==="; tail -40 "$WORK/cmake.log"; exit 1; }
note "cmake 配置通过"

if [ -d "$BUILD/NATIVE" ]; then
  mkdir -p "$BUILD/NATIVE/bin"
  for t in llvm-tblgen llvm-min-tblgen clang-tblgen llvm-config; do
    if [ -x "$NATIVE_DIR/$t" ]; then
      cp -f "$NATIVE_DIR/$t" "$BUILD/NATIVE/bin/$t"
    fi
  done
  note "宿主工具就位（NATIVE/bin）：$(ls "$BUILD/NATIVE/bin" 2>/dev/null | tr '\n' ' ')"
  for b in "$BUILD/NATIVE/bin/llvm-min-tblgen" "$BUILD/NATIVE/bin/llvm-config"; do
    if [ -f "$b" ]; then note "  $(basename "$b") → $(file -b "$b" 2>/dev/null | head -c 70)"; fi
  done
else
  note "NATIVE 目录没被建（构建期也许不需要自建宿主工具）—— 不动它"
fi

note "CMake 认定的 tblgen（这里若仍是裸名 llvm-min-tblgen，说明开关没生效，它会自建 NATIVE/bin 并执行交叉产物）"
TB="$(grep -E '^(LLVM_TABLEGEN|LLVM_NATIVE_TOOL_DIR|LLVM_USE_HOST_TOOLS|LLVM_TABLEGEN_EXE)' "$BUILD/CMakeCache.txt" 2>/dev/null || true)"
echo "  ${TB:-（CMakeCache.txt 里没有这些键）}"
echo "  native-tools 实际内容："
ls -la "$NATIVE_DIR" 2>/dev/null | sed 's/^/    /' || echo "    （读不到）"
echo "  NATIVE/bin（NATIVE 目录被建了就说明还在自建宿主工具）："
if [ -d "$BUILD/NATIVE/bin" ]; then
  ( ls -la "$BUILD/NATIVE/bin" 2>/dev/null | head -6 || true ) | sed 's/^/    /'
  for b in "$BUILD/NATIVE/bin/llvm-min-tblgen" "$BUILD/NATIVE/bin/llvm-config"; do
    if [ -f "$b" ]; then echo "    $(basename "$b") → $(file -b "$b" 2>/dev/null | head -c 70)"; fi
  done
else
  echo "    （尚未创建 —— 好现象）"
fi

note "开始编（$JOBS 作业）—— 这一步在 CI 上要几十分钟到数小时"
cmake --build "$BUILD" -j"$JOBS" \
  > "$WORK/build.log" 2>&1 \
  || { echo "=== LLVM 编译失败取证 ==="; \
       echo "（error 行）"; grep -nE 'error:|Error [0-9]+$|undefined (symbol|reference)' "$WORK/build.log" | head -30 || true; \
       echo "（末 60 行）"; tail -60 "$WORK/build.log"; \
       echo; echo "== 交叉编译需要的宿主工具（LLVM 文档给的开关是 LLVM_NATIVE_TOOL_DIR / LLVM_TABLEGEN）=="; \
       TB_LIST=$(ls "$NATIVE_DIR" 2>/dev/null | tr '\n' ' ' || true); \
       echo "  我们自己编的宿主 tblgen：${TB_LIST:-（没编成功）}"; \
       echo "  NDK bin 里的 *-tblgen：$(ls "$TC"/*-tblgen 2>/dev/null | tr '\n' ' ' || true)"; \
       echo "  PATH 里的 llvm-tblgen：$(command -v llvm-tblgen 2>/dev/null || echo '（无）')"; \
       echo "  宿主 cc/c++：$(command -v cc 2>/dev/null || echo '（无）') $(command -v c++ 2>/dev/null || echo '（无）')"; \
        echo "  构建里的 NATIVE/bin：$(ls "$BUILD"/NATIVE/bin 2>/dev/null | head -5 | tr '\n' ' ' || true)"; \
       echo "  构建里的 bin（交叉产物）：$(ls "$BUILD"/bin 2>/dev/null | head -5 | tr '\n' ' ' || echo '（不存在）')"; \
       echo "  失败时用到的 tblgen："; \
       grep -oE '[^ ]*llvm[a-z-]*-tblgen[^ ]*' "$WORK/build.log" 2>/dev/null | sort -u | head -4 | sed 's/^/    /' || true; \
       echo; echo "首次失败是**预期内**的：交叉编译 LLVM 到 Bionic 需要打补丁（Termux 级别的量）。"; \
       echo "按上面的 error 行定位缺什么，**不要**放宽这里的判据 —— 放宽换来的是「编出来了但在设备上跑不了」。"; \
       exit 1; }

cmake --install "$BUILD" > "$WORK/install.log" 2>&1 \
  || { echo "=== install 失败（末 30 行）==="; tail -30 "$WORK/install.log"; exit 1; }

    rm -rf "$BUILD"
    note "目标编构建树已删（产物已装进 $STAGE/prefix）—— 别让那构建树一直占着磁盘"

note "检查产物"
NEED_BIN="clang clang++ ld.lld llvm-ar llvm-nm llvm-strip llvm-objdump llvm-readobj"
MISS=""
for b in $NEED_BIN; do
  [ -x "$STAGE/prefix/bin/$b" ] || MISS="$MISS $b"
done
[ -z "$MISS" ] || die "产物不全" "缺:$MISS —— 不能交付一半的工具链（那样会在某个时刻突然找不到某个工具）"

echo "== 形态自检 =="
BAD=""
for b in clang ld.lld llvm-ar llvm-strip; do
  f="$STAGE/prefix/bin/$b"
  s=$(stat -c%s "$f")

  INFO=$(file -bL "$f")
  case "$INFO" in *aarch64*|*arm64*|*ARM64*) : ;; *) BAD="$BAD $b(架构:$INFO)" ;; esac
  al="$("$LLVM_READELF" -W -l "$f" 2>/dev/null | awk '/^[[:space:]]*LOAD/{print $NF}' \
        | while read -r a; do case "$a" in 0x[0-9a-fA-F]*) ;; *) continue ;; esac; \
            d=$(( a )); if [ "$d" -eq 0 ]; then continue; fi; \
            if [ $(( d % 16384 )) -ne 0 ]; then printf ' '; fi; done)"
  if [ -n "$al" ]; then BAD="$BAD $b(16KB对齐不合格)"; fi
  printf '[ok] %-16s %s 字节\n' "$b" "$s"
done
[ -z "$BAD" ] || die "形态不合格" "$BAD"

for b in clang ld.lld llvm-ar; do
  interp="$("$LLVM_READELF" -W -l "$STAGE/prefix/bin/$b" 2>/dev/null \
            | sed -n 's/.*\[Requesting program interpreter: \(.*\)\].*/\1/p')"
  case "$interp" in
    ""|/system/bin/linker64) : ;;
    *) die "解释器不对" "$b 的 PT_INTERP=$interp —— 说明是拿宿主链接器编的，设备上跑不了" ;;
  esac
done
echo "[ok] 解释器形态正确（/system/bin/linker64）"

rm -rf "$TOOLDIR" "$OUT/lib"
mkdir -p "$TOOLDIR" "$OUT/lib"
cp -a "$STAGE/prefix/bin/." "$TOOLDIR/"

if [ -d "$STAGE/prefix/lib" ]; then
  cp -a "$STAGE/prefix/lib/." "$OUT/lib/"
fi

NSDK_CLANGRES="$(ls -d "$NDK_ROOT"/toolchains/llvm/prebuilt/*/lib/clang/* 2>/dev/null | head -1 || true)"
if [ -n "$NSDK_CLANGRES" ] && [ -d "$NSDK_CLANGRES/lib" ]; then
  _cv="$(basename "$NSDK_CLANGRES")"
  mkdir -p "$OUT/lib/clang/$_cv/lib"
  cp -a "$NSDK_CLANGRES/lib/." "$OUT/lib/clang/$_cv/lib/" 2>/dev/null || true

  _triple="aarch64-unknown-linux-android$API"
  mkdir -p "$OUT/lib/clang/$_cv/lib/$_triple"
  cp -a "$NSDK_CLANGRES/lib/linux/aarch64/." "$OUT/lib/clang/$_cv/lib/$_triple/" 2>/dev/null || true
  cp -a "$NSDK_CLANGRES/lib/linux/libclang_rt."* "$OUT/lib/clang/$_cv/lib/$_triple/" 2>/dev/null || true
  echo "[llvm] 资源目录已并入（libunwind.a 与那 libclang_rt 在那里，三元组 $_triple）"
fi

NSDK_SR="${NSDK_SR:-$(ls -d "$NDK_ROOT"/toolchains/llvm/prebuilt/*/sysroot 2>/dev/null | head -1 || true)}"
if [ -n "$NSDK_SR" ] && [ -d "$NSDK_SR" ]; then
  rm -rf "$OUT/sysroot"
  mkdir -p "$OUT/sysroot/usr/lib/aarch64-linux-android"
  cp -a "$NSDK_SR/usr/include" "$OUT/sysroot/usr/include"

  NSDK_ARCHINC="$(ls -d "$NSDK_SR"/usr/include/aarch64-linux-android 2>/dev/null | head -1)"
  if [ -n "$NSDK_ARCHINC" ] && [ -d "$NSDK_ARCHINC" ]; then
    cp -a "$NSDK_ARCHINC/." "$OUT/sysroot/usr/include/" 2>/dev/null || true
    echo "[sysroot] 架构头已并入（asm/types.h 在那里）"
  fi

  cp -a "$NSDK_SR"/usr/lib/aarch64-linux-android/. "$OUT/sysroot/usr/lib/aarch64-linux-android/" 2>/dev/null || true

  for _d in "$OUT/sysroot/usr/lib/aarch64-linux-android/"*/; do
    _b="$(basename "$_d")"
    case "$_b" in
      [0-9]*) [ "$_b" = "$API" ] || rm -rf "$_d" ;;
    esac
  done

  rm -f "$OUT/sysroot/usr/lib/aarch64-linux-android/libc.a" "$OUT/sysroot/usr/lib/aarch64-linux-android/libc++_static.a"
  NSDK_TOOLLIB="$(ls -d "$NDK_ROOT"/toolchains/llvm/prebuilt/*/lib/clang/*/lib/linux/aarch64 2>/dev/null | head -1 || true)"
  if [ -n "$NSDK_TOOLLIB" ] && [ -d "$NSDK_TOOLLIB" ]; then
    cp -a "$NSDK_TOOLLIB"/. "$OUT/sysroot/usr/lib/" 2>/dev/null || true
    echo "[sysroot] 工具链库已并入（libunwind.a 在那里）"
  else
    echo "::error title=找不到 clang 工具链库::libunwind.a 在那里，编 C++ 会失败（NDK_ROOT=$NDK_ROOT）"
    exit 1
  fi
  echo "[sysroot] 就位 $OUT/sysroot"
else
  echo "::error title=找不到 NDK sysroot::编任何 C/C++ 都要它（NDK_ROOT=$NDK_ROOT）"
  exit 1
fi
rm -rf "$STAGE"
printf '%s' "$PINNED_LLVM" > "$OUT/llvm.version"

note "写件内 package.json（别名映射）"
node -e '
const fs = require("node:fs"), path = require("node:path");
const dir = process.argv[1], entry = process.argv[2], tool = process.argv[3];
const names = fs.readdirSync(dir).filter((n) => {
  try { return fs.statSync(path.join(dir, n)).isFile(); } catch (e) { return false; }
}).sort();
const bin = {};
for (const n of names) bin[n] = "bin/" + n;
//  缺陷名（C-102）**那一行 delete 删的是一个不存在的键、它那行注释所假定的形状也不成立** ——
//    而旧版那一行逐字是 `delete bin[entry];`、那 entry 逐字传的是件名那一格（llvm）、
// 那 bin 映射的键逐字是**件内那些程序名**（clang / clang++ / ld.lld / llvm-ar …）、
// 里面**没有**件名那一键、那 delete 逐字**什么也没删**（01 §1.3 逐字核过这一点）。
// 旧版那一行注释逐字说「本名（键 == 件名）不重复声明」——个「键 == 件名」的形状
//      逐字**不成立**（件名是 llvm、键是程序名、它们逐字不是同一个东西）。
delete bin[entry];
//**这一行必须逐字是 no-op、且必须继续是 no-op**：它要防的是「把**件名**当成别名重复声明」，
// 件名 llvm 逐字**不在**那 bin 映射里（那 bin 映射的键逐字是程序名）、
// 所以 no-op 恰好是对的（一句 delete 逐字防的东西压根没发生过）。
//**不许**把它改成「删那个值逐字等于仓内声明的入口的那一键」（是 01 §5.1 第 3 条形式乙那一半）：
//01 §4.1 逐字裁「bin 是对象时、必须存在一个键、其值逐字等于 declaredEntry」，
//llvm 那一件逐字就是 bin["clang"] = "bin/clang"、那 declaredEntry 逐字是 bin/clang；
// 两段**互斥**：删了 bin["clang"]、发侧逐字算出来的 entryKeys 逐字**为空**
// 一格**必判红**（Lead 本机实跑两支：保持现状逐字 entryKeys=["clang"] 通过；
// 删 bin["clang"] 逐字 entryKeys=[] 判红）。
// 那**入口那一键**为什么**不进别名集合**：=> 那由**两侧各自**排除
//    （=> 发侧 publish-component-manifest.js:89 按键名跳 entryBase、=> 装侧 ProgramInstallPipeline.kt:1212 同口径），
//**不在生成侧删键**（按 C-103 那两边各答一件事、按 C-135 那不是同一问）。
// 件名那一格（name）逐字从 $TOOL 派生、不再另写一个字面（01 §4.3 逐字：
//    「两处都要落在同一个字面上、否则 :539 的 name 与 :10 的 TOOL 还会再分叉一次」）。
fs.writeFileSync(path.join(dir, "..", "package.json"),
  JSON.stringify({ name: tool, bin }, null, 2) + "\n");
console.log("[ok] package.json 声明别名 " + Object.keys(bin).length + " 个");
' "$TOOLDIR" "$TOOL" "$TOOL"
[ -f "$OUT/package.json" ] || die "没产出 package.json" \
  "别名映射是装侧建链与发侧写清单的唯一真相 —— 没有它这一件只有入口能用"

echo
echo "[ok] $OUT/bin （$(ls "$TOOLDIR" | wc -l) 个工具）"
echo "[$TOOL] 判据：clang --version 能报版本、ld.lld --version 能报、能在真机编出一个 .so"
