#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

HERE="$(dirname "$0")"
cd "$HERE/../.."
ROOT_DIR="$(pwd)"

OUT="${OUT:-dist}"
#  缺陷名 C-102：同一个 TOOL 字面在两处指两样东西：一边是「这一件叫什么」（件名，服务打包与取件），
#   一边是「件里那些程序叫什么」（件内 bin/ 下的名字，服务调用）。两边各答一件事，按 C-135 不是同一问、不合并。
#  这一处的 TOOL 服务打包与取件（产物文件名、sources 的键、Release tag），件名是 llvm：
#   package-component.sh:8 取位置参数、build-llvmtoolchain.yml:296 传 llvm、package-component.sh:83 的 ZIP 含 llvm 不含 llvmtoolchain。
#  「llvmtoolchain」是 recipe 名 / workflow 名 / 文件名那一族（fetch-component-artifacts.sh:56、cache-key.sh:197），它不是件名。
#  按 S606-01-ALIAS-BIN-KEY-RULING.md §5.1 第 3 条取形式乙（TOOL 改成 llvm）；形式甲（只改 TOOL、delete 照旧）不够：删 bin["llvm"] 仍删不到东西。
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
#  缺陷名 C-102：检查只看 recipe 自己的变量，而 CMake 要 ninja 在 PATH 上面。
#  成因：`command -v "$NINJA"` 对绝对路径也是真（退出码 0），只证明文件在、不证明在 PATH 上面；
#   CMake 要的是 PATH 上面（阳性对照：不在 PATH 上时报
#   「CMake Error: CMake was unable to find a build program corresponding to Ninja.」）。
#  处置：工具要能在 PATH 上面找到（绝对路径也算），缺的那一句要说清它要什么。按 C-104：工具检查只有一条路。
for t in "$CMAKE" "$NINJA"; do
  # 工具要在 PATH 上面（CMake 只在 PATH 上面找它）。
  # command -v 对绝对路径也是真，判不了它在不在 PATH 上面。
  _found=""
  _base="$(basename "$t")"
  _IFS_save="$IFS"; IFS=":"; for _d in $PATH; do
    [ -n "$_d" ] || continue
    if [ -x "$_d/$_base" ]; then _found="$_d/$_base"; break; fi
  done; IFS="$_IFS_save"
  if [ -z "$_found" ]; then
    # 绝对路径那一族也要在 PATH 上面（CMake 要它）。
    die "缺工具" \
      "$t 没有 —— 编 LLVM 要 cmake + ninja（runner 预装；缺了请 sudo apt-get install -y cmake ninja-build）；
      **它**逐字**要**在**那 PATH **上面**、 CMake 逐字**只**在**那 PATH **上面**找**它**"
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
  #  缺陷名 C-102：源码包解完就留在磁盘上，而它没有任何后续要读它的地方——
  #  解包出来的那一份已经在 $SRC 里。LLVM 源码包是 171 MiB 那一档，解包出来更大。
  #  目标编要宿主磁盘，不该与源码包挤在同一块盘上。
  rm -f "$TGZ"
  note "源码包已删（解包出来的那一份已经在 $SRC 里）—— 不给它留一份重复的"
fi
#  缺陷名 C-102：源码包在每一趟都可能留在磁盘上——它是压缩副本，$SRC 已在位的那一趟也可能留着它。
#  处置：.tar.xz 每一趟都删，$SRC 留着（它是目标编的输入）。
rm -f "$WORK/llvm.src.tar.xz"
[ -f "$SRC/llvm/CMakeLists.txt" ] || die "源码树异常" "缺 llvm/CMakeLists.txt —— 拿到的不是 llvm-project"
note "源码就位：$(cat "$SRC/llvm/CMakeLists.txt" 2>/dev/null | grep -m1 -o 'project(LLVM)' || echo 'llvm-project')"

BUILD="$WORK/build"
rm -rf "$BUILD"
mkdir -p "$BUILD"

HOST_TB="$WORK/host-tblgen"
rm -rf "$HOST_TB"
note "先编宿主版 tblgen —— 不做这步，LLVM 的 build_native_tool 会把交叉编出来的 llvm-min-tblgen 放进 NATIVE/bin，然后在 x86_64 构建机上执行 aarch64 产物（Exec format error）。"
#  缺陷名 C-102：这一格取错了对象。LLVM 21.1.0 的 TableGen.cmake 里 `if (LLVM_USE_HOST_TOOLS)`（第 207 行）包住
#   build_native_tool(${target} ${project}_TABLEGEN_EXE DEPENDS ${target})，这是唯一把宿主工具装到 ${project}_TABLEGEN_EXE 的地方，
#   外面没有 else（第 226 行是它的 endif）。LLVM_USE_HOST_TOOLS=OFF 时不被宿主那份接管：llvm-min-tblgen 走 build_native_tool 自建
#   （aarch64 交叉产物），在 x86_64 构建机上执行报「Exec format error」（40154442601 那一趟实测）。
#  LLVM_TABLEGEN 只对 llvm-min-tblgen 生效、且要求不是 llvm-tblgen，我们那格正是 llvm-tblgen，接管不了它。
#  处置：LLVM_USE_HOST_TOOLS=ON（让宿主那份接管，build_native_tool 不再自建）。这是上游开关，不改上游源码。
note "  注意：不能只靠「不设 toolchain 文件」—— locate-ndk.sh 注入的 CC/CXX 在环境里，CMake 会直接采用它们，于是宿主构建也编成 aarch64（实测 host-tblgen/bin/llvm-min-tblgen: Exec format error）。要显式覆盖。"
command -v cc >/dev/null 2>&1 || die "缺宿主 cc" \
  "编宿主 tblgen 需要宿主编译器；runner 自带 /usr/bin/cc。缺了请 apt-get install build-essential"
command -v c++ >/dev/null 2>&1 || die "缺宿主 c++" "同上"
#  缺陷名 C-102：宿主那一支也把 clang 与 lld 打开了，而它只需要 tblgen 那几件。
#  clang 那一族拉进 legacy pass manager（llvm::Pass / llvm::ModulePass / llvm::FunctionPass / llvm::PassRegistry），LLVM 21 已删（LLVM 17 之后就没有）。
#  后果：宿主那一支编出来的 libLLVM 缺那一族符号，交叉那一支链接时报
#   「ld.lld: error: undefined symbol: llvm::Pass::~Pass()」（40161547449 那一趟 26 条，全是 legacy pass manager）。
#  处置：宿主那一支只开它要的那一件（clang-tblgen 要 clang 那一族，lld 不要、也不参与那几件 tblgen）。
#  注意：这段注记必须在命令外面，不能放在反斜杠续行中间（会断掉续行，报 line 136: -DLLVM_ENABLE_PROJECTS=clang: command not found）。
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

#  缺陷名 C-102：编 LLVM 要宿主磁盘，四笔里没一支管它。40247630569 那一趟红在
#  「No space left on device」（job = failure、第 9 步 in_progress、第 13/14/15 步 pending，编本身没报错）。
#  占空间的是宿主 tblgen 那一棵（$HOST_TB）：建在 :98，目标编在 :281 时它还在盘上，而它在 :165 之后再没被读到过——
#  真正还要的只有那 4 个可执行文件，已在 $NATIVE_DIR 里（:212 / :213 两个 cmake 开关、:254 拷贝、:269 / :287 取证）。
#  处置：$HOST_TB 在那 4 个拷贝出去之后就删掉，目标编开始时盘上只剩目标那一棵；代价是它再也不能重编（:99 是重来入口）。
#  按 C-103 两边：「$HOST_TB 还要不要留」与「$BUILD 会长多大」各答一件事（C-135）；按 C-104 删只有一条路；按 C-220 04 那边落内容稿。
rm -rf "$HOST_TB"
note "宿主 tblgen 构建树已删（只留 $NATIVE_DIR 那 4 个可执行文件）—— 目标编那一族开始前先把磁盘腾出来"

#  缺陷名 C-102：这一族多编了一个不需要的 target。它拖进来的符号是 LLVM 21 已删的那一族
#  （llvm::Pass / llvm::ModulePass / llvm::FunctionPass / llvm::PassRegistry / llvm::Instruction::eraseFromParent /
#   llvm::Value::replaceAllUsesWith / llvm::Constant::getNullValue / llvm::Module::getNamedMetadata），是那 4 个测试 pass 的 vtable 拖进来的。
#  那个 target 是 tools/bugpoint-passes（给 bugpoint 用的测试 pass），不受 LLVM_INCLUDE_TESTS 管、受 LLVM_BUILD_TOOLS 管
#  （`if( NOT LLVM_BUILD_TOOLS ) set(EXCLUDE_FROM_ALL ON) endif()`），LLVM_INCLUDE_TESTS=OFF 已开它还是被编；开关名 LLVM_TOOL_BUGPOINT_PASSES_BUILD=OFF。
#  bugpoint 不在 NEED_BIN 一族里、仓内零命中，只是副产品不交付；宿主那一支不用加这档。注记必须放在命令外面（原因同 :136 那一类报错）。
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

#  缺陷名 C-102：源码树不能删——它是目标编的输入。LLVM 的 TableGen 一族在构建期读源码树里的 .td
#  （.inc 是构建期才生成的，读的是源码树里的 .td）。删掉它会让目标编在中途失败，而这种失败不是磁盘一族。
#  真能删的是 .tar.xz（同一份内容的压缩副本，解包出来的那份已在 $SRC 里）。
#  处置：.tar.xz 解包之后就删，$SRC 留着。按 C-103 两边：「要留着编目标的那一份」与「解包出来就没用了的那一份」
#  各答一件事（C-135）。按 C-104：删只有一条路。按 C-220：04 那边落内容稿，这一笔是实现那一族。

#  缺陷名 C-102：宿主工具被放错了目录。LLVM 21.1.0 的 build_native_tool 在构建期把自建的宿主工具放进
#  $BUILD/NATIVE/bin，那是用交叉工具链编的（aarch64），随后在 x86_64 构建机上执行报「Exec format error」。
#  配置期它没被建（07 那一趟读数「NATIVE/bin（尚未创建 —— 好现象）」），是构建期才建的，配置期看不出来。
#  LLVM_NATIVE_TOOL_DIR 只管配置期的 TABLEGEN 默认值（TableGen.cmake:181-185），管不到构建期自建的那一份。
#  处置：把宿主编的那几份放进 $BUILD/NATIVE/bin（构建期找它们的地方），自建的那份被覆盖成宿主那份。
#  该由「构建期找哪个路径」决定（上游那一族的路径形状），不改上游源码。
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
    #  缺陷名 C-102：目标编那棵构建树从不被删。40292279139 那一趟红在「No space left on device」，
    #  runner 自己的诊断日志都写不下了。
    #  成因：「第 9 步清出磁盘空间」只清到 95G 可用，编的峰值要构建树的空间；$BUILD 长到最后、从不被删，编完仍占着。
    #  处置：install 之后立刻删 $BUILD（产物已在 $STAGE/prefix，交付的是 $OUT 底下那一份）。
    #  按 C-103 两边：「$BUILD 还要不要留」与「产物在哪一层」各答一件事（C-135）。
    #  按 C-104：删只有一条路。按 C-220：04 那边落内容稿，这一笔是实现那一族。
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
  #  缺陷名 C-102：那两把尺子都不跟软链，这一族没有可跟的那把尺子。
  #  「量链本身」（stat -c%s）与「量目标」（file -b）是两件不同的事，旧版把它们混在一个判据里：
  #  stat -c%s 量链本身的长度、file -b（没有 -L）报链本身是软链，判不了目标架构，:294 那一族在软链上必然不匹配。
  #  04 裁的判据是「跟到目标再判架构」。三把尺子是三条路径，各答一件事：
  #   stat -c%s 答「链本身多长」，不跟软链、答不了架构；readlink 答「链指向谁」，不跟、答不了架构；
  #   file -bL 答「目标是什么」，跟软链、才是能跟的那把。stat -c%L 在断链上也是空，答不了架构。
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

#  缺陷名 C-102：落位只搬 bin/ 那一族，lib/ 那一族没搬过来。
#  实际上 cmake --install 把 libclang.so 与 libclang-cpp.so 落在 $STAGE/prefix/lib，而 recipe 只 `cp -a "$STAGE/prefix/bin/."`；
#  check-elf-deps.sh:93 找的是「件自己的 lib/」（<件目录>/lib）。c-index-test 链着 libclang.so，红的原话是
#  「依赖这些库，但它们既不在 bionic 白名单、也不是 APK 基础库、也不在件自己的 lib/ 下：libclang.so」（40281519231 那一趟）。
#  处置：lib/ 那一族要跟 bin/ 一起搬（别的件也是平的 $OUT/lib），判据不用改（它找的已经是对的层）。
#  按 C-135 两边「产物在哪一层」与「判据找件自己的库在哪一层」各答一件事；按 C-104 落位只有一条路；按 C-220 04 那边落内容稿。
rm -rf "$TOOLDIR" "$OUT/lib"
mkdir -p "$TOOLDIR" "$OUT/lib"
cp -a "$STAGE/prefix/bin/." "$TOOLDIR/"
#  lib/ 那一族是件自己的库（check-elf-deps 找的就是它）。
if [ -d "$STAGE/prefix/lib" ]; then
  cp -a "$STAGE/prefix/lib/." "$OUT/lib/"
fi
#  缺陷名 C-102：clang 那一族没有资源目录。
#  成因：cmake --install 只装 lib/clang/21/include/ 那一族，资源目录（libunwind.a 与 libclang_rt 两族）
#  在 lib/clang/21/lib/ 那一族、没装。
#  后果：编一个 .so 不过，错是「ld.lld: error: unable to find library -l:libunwind.a」
#  与「ld.lld: error: cannot open .../libclang_rt.builtins-aarch64-android.a」。
#  处置：NDK 的资源目录也并进 lib/clang/ 那一族（「clang 要什么」与「资源从哪来」是两件事）。
NSDK_CLANGRES="$(ls -d "$NDK_ROOT"/toolchains/llvm/prebuilt/*/lib/clang/* 2>/dev/null | head -1 || true)"
if [ -n "$NSDK_CLANGRES" ] && [ -d "$NSDK_CLANGRES/lib" ]; then
  _cv="$(basename "$NSDK_CLANGRES")"
  mkdir -p "$OUT/lib/clang/$_cv/lib"
  cp -a "$NSDK_CLANGRES/lib/." "$OUT/lib/clang/$_cv/lib/" 2>/dev/null || true
  # clang 按目标三元组找资源（lib/clang/<版本>/lib/<三元组>/），NDK 那边是 linux/<架构>/，
  # 处置是两个层次都给（「clang 要哪一层」与「NDK 有哪一层」是两件事）。
  _triple="aarch64-unknown-linux-android$API"
  mkdir -p "$OUT/lib/clang/$_cv/lib/$_triple"
  cp -a "$NSDK_CLANGRES/lib/linux/aarch64/." "$OUT/lib/clang/$_cv/lib/$_triple/" 2>/dev/null || true
  cp -a "$NSDK_CLANGRES/lib/linux/libclang_rt."* "$OUT/lib/clang/$_cv/lib/$_triple/" 2>/dev/null || true
  echo "[llvm] 资源目录已并入（libunwind.a 与那 libclang_rt 在那里，三元组 $_triple）"
fi
#  缺陷名 C-102：clang 找不到 sysroot 与 libunwind.a。
#  成因：clang 从它自己的安装前缀找 C++ 头文件（bin/../sysroot/usr/include/c++/v1，不看 --sysroot）；
#   libunwind.a 不在 sysroot 里，在 clang 那工具链里（lib/clang/*/lib/linux/aarch64）。
#  后果：编一个 .so 不过，错是「ld.lld: error: unable to find library -l:libunwind.a」与「cannot open crtend_so.o」。
#  处置：sysroot 进 $OUT/sysroot/（跟 bin/ 相对，clang 按自己的规则找得到），工具链那一族也并进 sysroot 的 lib/ 里。
#  按 C-104：两者不是同一件内容的两条取法、处置不合并。
NSDK_SR="${NSDK_SR:-$(ls -d "$NDK_ROOT"/toolchains/llvm/prebuilt/*/sysroot 2>/dev/null | head -1 || true)}"
if [ -n "$NSDK_SR" ] && [ -d "$NSDK_SR" ]; then
  rm -rf "$OUT/sysroot"
  mkdir -p "$OUT/sysroot/usr/lib/aarch64-linux-android"
  cp -a "$NSDK_SR/usr/include" "$OUT/sysroot/usr/include"
  #  缺陷名 C-102：sysroot 缺架构那一族的头。
#  成因：asm/types.h 在 usr/include/aarch64-linux-android/asm/，顶层 usr/include/ 里没有那个目录；
#  后果是「fatal error: 'asm/types.h' file not found」。
#  处置：架构那一族的头也并进 sysroot 的 include/ 里（「sysroot 是什么」与「头从哪来」是两件事）。
  NSDK_ARCHINC="$(ls -d "$NSDK_SR"/usr/include/aarch64-linux-android 2>/dev/null | head -1)"
  if [ -n "$NSDK_ARCHINC" ] && [ -d "$NSDK_ARCHINC" ]; then
    cp -a "$NSDK_ARCHINC/." "$OUT/sysroot/usr/include/" 2>/dev/null || true
    echo "[sysroot] 架构头已并入（asm/types.h 在那里）"
  fi
  #  缺陷名 C-102：sysroot 把库放错了一层。
#  成因：crtbegin_so.o 与 crtend_so.o 在 usr/lib/aarch64-linux-android/<API>/，顶层的 usr/lib/aarch64-linux-android/ 里没有；
#  后果是「ld.lld: error: cannot open crtbegin_so.o: No such file or directory」。
#  处置：sysroot 照 NDK 那一族的层次摆（「sysroot 是什么」与「层次是什么」是两件事）。
  cp -a "$NSDK_SR"/usr/lib/aarch64-linux-android/. "$OUT/sysroot/usr/lib/aarch64-linux-android/" 2>/dev/null || true
  #  缺陷名 C-102：sysroot 把 NDK 那一族全部搬进去了。
  #  后果：libc.a 26672138 字节、libc++_static.a 24518858 字节，整份 125 M。
  #  处置：<API>/ 那些子目录只留目标 API 那一个（「层次是什么」与「大小是什么」是两件事）。
  for _d in "$OUT/sysroot/usr/lib/aarch64-linux-android/"*/; do
    _b="$(basename "$_d")"
    case "$_b" in
      [0-9]*) [ "$_b" = "$API" ] || rm -rf "$_d" ;;
    esac
  done
  # libc.a 与 libc++_static.a 不要（libunwind.a 要在 usr/lib/ 那一层、不在 <API>/ 子目录里）。
  rm -f "$OUT/sysroot/usr/lib/aarch64-linux-android/libc.a" "$OUT/sysroot/usr/lib/aarch64-linux-android/libc++_static.a"  #   libunwind.a 逐字**要**在**那 usr/lib/ **那一层**（**它**逐字**不**在**那 <API>/ **那些**子目录**里面**）。
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

  #  缺陷名 C-102：这一件落位在别处，判据读的是根那一层。产物在 $OUT/$TOOL/bin、package.json 落在 $OUT/$TOOL，
  #  而判据（verify-component-artifact.sh:99）读的是 $ROOT/dist/package.json，两个路径不是同一个。
  #  真源是落位那一个（别的件全是平的 $OUT/bin 与 $OUT/lib；gen-component-meta.js 只扫 $PAYLOAD/bin 与 $PAYLOAD/lib，
  #   装侧 ProgramInstallPipeline.kt 与发侧 publish-component-manifest.js 都读根那一层的 package.json）。
  #  处置：这一件改成平的落位（$OUT/bin 与 $OUT/package.json），判据不用改（它读的已经是对的层）。
  #  按 C-104：落位只有一条路。按 C-220：04 那边落内容稿，这一笔是实现那一族。
note "写件内 package.json（别名映射）"
node -e '
const fs = require("node:fs"), path = require("node:path");
const dir = process.argv[1], entry = process.argv[2], tool = process.argv[3];
const names = fs.readdirSync(dir).filter((n) {
  try { return fs.statSync(path.join(dir, n)).isFile(); } catch (e) { return false; }
}).sort();
const bin = {};
for (const n of names) bin[n] = "bin/" + n;
//  缺陷名 C-102：那一行 delete 删的是不存在的键，它那行注释假定的形状也不成立。
//    旧版 `delete bin[entry];` 传的是件名（llvm），而 bin 映射的键是件内程序名
//    （clang / clang++ / ld.lld / llvm-ar …），没有件名那一键，delete 什么也没删（01 §1.3 核过）；
//    旧版注释说「本名（键 == 件名）不重复声明」，而「键 == 件名」不成立（件名 llvm、键是程序名）。
delete bin[entry];
//  这一行必须继续是 no-op：它防的是「把件名当别名重复声明」，而件名 llvm 不在 bin 映射里，no-op 恰好对。
//  不许改成「删那个值等于声明入口的那一键」（01 §5.1 第 3 条形式乙那一半）：01 §4.1 裁「bin 是对象时
//    必须存在一个键、其值等于 declaredEntry」，llvm 那件就是 bin["clang"] = "bin/clang"。两段互斥：
//    删了 bin["clang"]，发侧算出来的 entryKeys 为空、必判红（Lead 实跑：保持现状 entryKeys=["clang"] 通过，
//    删 bin["clang"] 得 entryKeys=[] 判红）。
//  入口那一键不进别名集合，由两侧各自排除（发侧 publish-component-manifest.js:89 按键名跳 entryBase、
//    装侧 ProgramInstallPipeline.kt:1212 同口径），不在生成侧删键（按 C-103 / C-135 不是同一问）。
//  件名那一格（name）从 $TOOL 派生、不再另写一个字面（01 §4.3：两处落在同一个字面上，否则 :539 的 name
//    与 :10 的 TOOL 还会再分叉一次）。
fs.writeFileSync(path.join(dir, "..", "package.json"),
  JSON.stringify({ name: tool, bin }, null, 2) + "\n");
console.log("[ok] package.json 声明别名 " + Object.keys(bin).length + " 个");
' "$TOOLDIR" "$TOOL" "$TOOL"
[ -f "$OUT/package.json" ] || die "没产出 package.json" \
  "别名映射是装侧建链与发侧写清单的唯一真相 —— 没有它这一件只有入口能用"

echo
echo "[ok] $OUT/bin （$(ls "$TOOLDIR" | wc -l) 个工具）"
echo "[$TOOL] 判据：clang --version 能报版本、ld.lld --version 能报、能在真机编出一个 .so"
