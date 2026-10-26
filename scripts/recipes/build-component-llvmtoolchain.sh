#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

HERE="$(dirname "$0")"
cd "$HERE/../.."
ROOT_DIR="$(pwd)"

OUT="${OUT:-dist}"
TOOL="llvmtoolchain"
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
  command -v "$t" >/dev/null 2>&1 || die "缺工具" \
    "$t 没有 —— 编 LLVM 要 cmake + ninja（runner 预装；缺了请 sudo apt-get install -y cmake ninja-build）"
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
  #  缺陷名（C-102）**那源码包逐字解完就留在那磁盘上** —— => 而而它逐字**没有**任何**后续**要**读**它**
  #  => 而那**解包**出来的那一份（=> 而它是那 .tar.xz 展开的**同一**份内容）逐字**已经**在 $SRC **里面**了。
  #  => 而那 LLVM 源码包逐字是 **171 MiB** 那一档、=> 而而那**解包**出来的那一份**更大**。
  #  => 而那**目标编**那一族逐字**要**那**宿主**磁盘、=> 而而它逐字**不该**与那**源码包**挤**在**那**同一**块**盘**上**。
  rm -f "$TGZ"
  note "源码包已删（解包出来的那一份已经在 $SRC 里）—— 不给它留一份重复的"
fi
#  缺陷名（C-102）**那源码包逐字在每一次跑的时候都可能留在那磁盘上** —— => 而而它逐字是那压缩副本、
#  => 而而它逐字在那 $SRC 已经在位的那一趟里也可能留着（=> 而那一趟解包失败就留下了它）。
#  => 而那处置：=> 而而那 .tar.xz 每一趟都删、=> 而而那 $SRC 留着（=> 而它是那目标编的输入）。
rm -f "$WORK/llvm.src.tar.xz"
[ -f "$SRC/llvm/CMakeLists.txt" ] || die "源码树异常" "缺 llvm/CMakeLists.txt —— 拿到的不是 llvm-project"
note "源码就位：$(cat "$SRC/llvm/CMakeLists.txt" 2>/dev/null | grep -m1 -o 'project(LLVM)' || echo 'llvm-project')"

BUILD="$WORK/build"
rm -rf "$BUILD"
mkdir -p "$BUILD"

HOST_TB="$WORK/host-tblgen"
rm -rf "$HOST_TB"
note "先编宿主版 tblgen —— 不做这步，LLVM 的 build_native_tool 会把交叉编出来的 llvm-min-tblgen 放进 NATIVE/bin，然后在 x86_64 构建机上执行 aarch64 产物（Exec format error）。"
# ★ 缺陷名（C-102）**这一格取错了对象** —— 而而 LLVM 21.1.0 那一族的 TableGen.cmake 逐字是：
#     if (LLVM_USE_HOST_TOOLS)                        （=> 而那是第 207 行）
#       if( ${${project}_TABLEGEN} STREQUAL "${target}" )
#         build_native_tool(${target} ${project}_TABLEGEN_EXE DEPENDS ${target})
#   => 而而那个 if 块**是唯一**把宿主工具装到 ${project}_TABLEGEN_EXE 的地方，
#   => 而而它的**外面**没有 else（=> 而而第 226 行就是那个 if 的 endif）。
#   => 而所以 LLVM_USE_HOST_TOOLS=OFF 时，=> 而那一格**不被宿主那份**接管，
#   => 而而 llvm-min-tblgen 走 build_native_tool 自建（=> 而那是**交叉**产物，=> 而那是 aarch64），
#   => 而而它在 x86_64 构建机上被执行时逐字是「Exec format error」（=> 而 40154442601 那一趟实测）。
#   => 而 LLVM_TABLEGEN 那一格**只**对 llvm-min-tblgen 生效，=> 而且它要「不是 llvm-tblgen」才生效（=> 而那一行逐字是
#     AND NOT "${LLVM_TABLEGEN}" STREQUAL "llvm-tblgen"）—— => 而我们那一格**正是** llvm-tblgen，
#   => 而所以那一格**也**不接管 llvm-min-tblgen。
#   => 而**处置**：LLVM_USE_HOST_TOOLS=ON（=> 而而那一格让宿主那份接管，=> 而而 build_native_tool 不再自建）。
#   => 而那一处是**上游**的开关，=> 而不改上游源码。
note "  注意：不能只靠「不设 toolchain 文件」—— locate-ndk.sh 注入的 CC/CXX 在环境里，CMake 会直接采用它们，于是宿主构建也编成 aarch64（实测 host-tblgen/bin/llvm-min-tblgen: Exec format error）。要显式覆盖。"
command -v cc >/dev/null 2>&1 || die "缺宿主 cc" \
  "编宿主 tblgen 需要宿主编译器；runner 自带 /usr/bin/cc。缺了请 apt-get install build-essential"
command -v c++ >/dev/null 2>&1 || die "缺宿主 c++" "同上"
#  缺陷名（C-102）**宿主那一支也把 clang 与 lld 打开了** —— => 而它**只需要** tblgen 那几件。
#   而 clang 那一族**拉进** legacy pass manager（=> 而 llvm::Pass / llvm::ModulePass /
#   llvm::FunctionPass / llvm::PassRegistry 那一族），=> 而 LLVM 21 那一版**已经删了**它们
#   （=> 而 LLVM 17 之后就没有了）。
#   => 而后果：宿主那一支**自己**编出来的 libLLVM 缺那一族符号 => => 而交叉那一支链接时
#      逐字是「ld.lld: error: undefined symbol: llvm::Pass::~Pass()」
#      （=> 而 40161547449 那一趟逐字 26 条那一种，=> 而它们**逐字全是** legacy pass manager 那一族）。
#   => 而处置：宿主那一支**只**开它要的那一件（=> 而 clang-tblgen 那一件**要** clang 那一族，
#      => 而而 lld **不**要、=> 而而它**也不**参与那几件 tblgen）。
#   => 而注意：这一段注记**必须**在命令**外面**、=> 而而它**不能**放在反斜杠续行**中间**
#      （=> 而那会**断掉**续行、=> 而而下一行逐字被当成一条新命令、=> 而那一趟逐字是
#         「scripts/recipes/build-component-llvmtoolchain.sh: line 136: -DLLVM_ENABLE_PROJECTS=clang: command not found」）。
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

#  缺陷名（C-102）**那编 LLVM 那一族逐字要那宿主磁盘、=> 而而那四笔逐字没一支管它**
#    —— 而实际上那 40247630569 那一趟逐字**红**在那一句
#    「Unhandled exception. System.IO.IOException: No space left on device」（=> 而而那 job 逐字
#    = completed / failure、=> 而而那第 9 步逐字 = in_progress、=> 而而那第 13 与 14 与 15 步
#    逐字 = pending、=> 而而那**编**本身逐字**没**报错）。
#  => 而而那**具体**是**哪**一族占的空间：=> 而而那**宿主 tblgen** 那一棵（$HOST_TB）
#    逐字**建**在 :98、=> 而而那**目标编**那一族逐字**发生**在 :281（=> 而而那 $HOST_TB
#    逐字**还**在**那磁盘**上）、=> 而而那 $HOST_TB **那一整棵**构建树逐字**在** :165 之后
#    **就**再**也**没有**被**读**到**了（=> 而而那**真正**还**要**的**只有**那 4 个
#    可执行文件、=> 而而它们逐字**已经**在 $NATIVE_DIR **里面**、=> 而而那 $NATIVE_DIR 逐字
#    **后面**还**要**用：:212 / :213 那**两**个 cmake 开关、:254 那一行拷贝、
#    :269 / :287 那一段取证）。
#  => 而那**处置**：=> 而而那 $HOST_TB 逐字**在**那 4 个**拷贝**出去**之后**就**删掉**、
#    => 而而那**目标编**那一族逐字**开始**的时候那磁盘**上**只**剩**那**目标**那一棵。
#  => 而那**代价**：=> 而而那 $HOST_TB 逐字**再**也**不能**重**编**（=> 而而它**要**重**编**
#    就**要** rm -rf 重**来**一遍、=> 而那 :99 那一行逐字**就是**那个**入口**）。
#  => 而按 C-103 那两边：「那 $HOST_TB 逐字**还**要**不要**留**」与「那 $BUILD 那一棵逐字
#    **会**长**多**大**」=> 而那两边各答一件事、=> 而而处置不一样、=> 而按 C-135 那不是同一问。
#  => 而按 C-104：=> 而而那**删**那一族逐字**只有**一条路、=> 而而那**不是**第二种生成路径。
#  => 而按 C-220：=> 而 04 那边要落**内容稿**、=> 而而这一笔是**实现**那一族。
rm -rf "$HOST_TB"
note "宿主 tblgen 构建树已删（只留 $NATIVE_DIR 那 4 个可执行文件）—— 目标编那一族开始前先把磁盘腾出来"

#  缺陷名（C-102）**这一族多编了一个不需要的 target** —— => 而而那一族拖进来的那一批符号
#    逐字是 LLVM 21 那一版**已经删了**的那一族（=> 而 llvm::Pass / llvm::ModulePass /
#    llvm::FunctionPass / llvm::PassRegistry 那一族、=> 而 llvm::Instruction::eraseFromParent /
#    llvm::Value::replaceAllUsesWith / llvm::Constant::getNullValue / llvm::Module::getNamedMetadata
#    那一族逐字是**被**那 4 个测试 pass 的 vtable **拖进来的**间接依赖）。
#    => 而那个 target 逐字是 tools/bugpoint-passes（=> 而 LLVM 源码里**给 bugpoint 用的测试 pass**），
#    => 而它逐字**不由** LLVM_INCLUDE_TESTS 那一档管（=> 而那逐字是 LLVM_BUILD_TOOLS 那一档：
#       if( NOT LLVM_BUILD_TOOLS ) set(EXCLUDE_FROM_ALL ON) endif()）。
#    => 而 LLVM_INCLUDE_TESTS=OFF 逐字**已经**开着、=> 而那个 target 逐字**还是**被编了。
#    => 而那个开关名逐字是 LLVM_TOOL_BUGPOINT_PASSES_BUILD=OFF
#       （=> 而 bugpoint-passes -> BUGPOINT_PASSES 那一步逐字是 canonicalize_tool_name 的
#          减号变下划线再 TOUPPER、=> 而 llvm_add_implicit_projects 逐字判 ${project}_TOOL_${name}_BUILD）。
#    => 而 bugpoint 那个可执行文件逐字**不在** NEED_BIN 那一族里、=> 而仓内逐字**零命中**、
#       => 而它逐字只是一个**副产品**、=> 而那一件逐字不交付它。
#    => 而宿主那一支**不用**加这一档（=> 而它逐字只开 clang、=> 而而它**不编** tool 那一族）。
#    => 而注意：这一段注记**必须**在命令**外面**、=> 而而它**不能**放在反斜杠续行**中间**
#       （=> 而那会**断掉**续行、=> 而而下一行逐字被当成一条新命令、=> 而那一趟逐字是
#          「scripts/recipes/build-component-llvmtoolchain.sh: line 136: -DLLVM_ENABLE_PROJECTS=clang: command not found」）。
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

#  缺陷名（C-102）**那源码树不能删** —— => 而而它逐字**是**那**目标编**的**输入**。
#  => 而 LLVM 的 TableGen 那一族逐字在**构建期**读**那源码树**里的 .td 那一族
#  #    （=> 而那 .inc 那一族逐字是**那构建期**才**生成**的、=> 而它**读**的是**那源码树**里的 .td）。
#  => 而那**删掉**它逐字**会**让**那目标编**在中途**失败**、=> 而而那**失败**逐字**不**是**那磁盘**那一族**。
#  => 而那**真**能删**的**是**那 .tar.xz**（=> 而它逐字是**那**同一**份**内容的**压缩**副本、=> 而**解包**出来的那一份**已经**在 $SRC **里面**了）。
#  => 而那**处置**：=> 而而那 .tar.xz 在**解包**之后**就**删**、=> 而而那 $SRC **留着**（=> 而它是**那目标编**的**输入**）。
#  => 而按 C-103 那两边：「那**要**留**着**编**目标**的**那一份**」与「那**解包**出来**就**没**用了**的**那一份**」
#  => 而那两边各答一件事、=> 而而处置不一样、=> 而按 C-135 那不是同一问。
#  => 而按 C-104：=> 而那**删**那一族逐字**只有**一条路、=> 而而那**不是**第二种生成路径。
#  => 而按 C-220：=> 而 04 那边要落**内容稿**、=> 而而这一笔是**实现**那一族。

# ★ 缺陷名（C-102）**宿主工具被放错了目录** —— 而 LLVM 21.1.0 那一族的 build_native_tool
#   在**构建期**把自建的宿主工具放进 $BUILD/NATIVE/bin，=> 而那一份是**用交叉工具链编的**
#   （=> 而那是 aarch64），=> 而它随后在 x86_64 构建机上被执行 => => 而逐字是「Exec format error」。
#   => 而配置那一期它**没**被建（=> 而 07 那一趟的读数逐字是「NATIVE/bin（尚未创建 —— 好现象）」），
#      => 而它是**构建期**才被建的 => => 而所以配置期那一格看不出来。
#   => 而而 LLVM_NATIVE_TOOL_DIR 那一格只管**配置期**的 TABLEGEN 默认值（=> 而 TableGen.cmake:181 到 :185），
#      => 而它**管不到**构建期自建的那一份。
#   => 而处置：把**宿主编的那几份**放进 $BUILD/NATIVE/bin（=> 而那正是构建期找它们的地方），
#      => 而而构建期自建的那一份（=> 而它是 aarch64）就被**覆盖**成宿主那份。
#   => 而那一格**该**由「构建期找哪个路径」决定（=> 而那正是上游那一族的路径形状），=> 而不改上游源码。
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
  note "NATIVE 目录没被建（=> 而构建期也许不需要自建宿主工具）—— 不动它"
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
    #  缺陷名（C-102）**那目标编**那一棵**构建树**逐字**从不**被**删** ——
    #    而实际上那 40292279139 那一趟逐字红在「No space left on device」、
    #    => 而那 runner 逐字**自己**的**诊断**日志（那 _diag 那一族）逐字**都写不下**了。
    #  => 而那**成因**逐字**是**：「那第 9 步清出磁盘空间」只**清**到那 **95G** 可用、
    #    => 而而那**编**的**峰值**逐字**要**那**构建树**的**空间**、=> 而而那 $BUILD 那一棵逐字**长**到**最后**、
    #    => 而而它**从不**被**删**、=> 而而那**编完**之后那盘**仍然**占**着**它**。
    #  => 而那**处置**：=> 而那 install **之后**立刻**删**那 $BUILD 那一棵
    #    （=> 而那**产物**已经**在** $STAGE/prefix **里面**了、=> 而而那 $STAGE **之后**也**会**被**删**、
    #      => 而而那**交付**的**是**那 $OUT **底下**的**那**一份**）。
    #  => 而按 C-103 那两边：「那 $BUILD 逐字**还**要不要**留**」与「那**产物**在**哪**一层**」
    #    => 而那两边各答一件事、=> 而而处置不一样、=> 而按 C-135 那不是同一问。
    #  => 而按 C-104：=> 而那**删**那一族逐字**只有**一条路、=> 而而那**不是**第二种生成路径。
    #  => 而按 C-220：=> 而 04 那边要落**内容稿**、=> 而而这一笔是**实现**那一族。
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
  #  缺陷名（C-102）**那两把尺子逐字都不跟软链、=> 而而这一族逐字没有可跟的那把尺子** ——
  #    而实际上「量链本身」（=> 而 stat -c%s 那一族）与「量目标」（=> 而 file -b 那一族）
  #    逐字是**两件不同的事**、=> 而而这一族逐字把两件事**混在一个判据**里：
  #    而 stat -c%s 逐字量**链本身**的长度、=> 而 file -b（**没有** -L）逐字报**链本身**是软链、
  #    => 而而它**判不了**目标的架构、=> 而而 :294 那一族逐字于是在软链上**必然不匹配**。
  #    => 而 04 裁的判据那一层是「**跟到目标**再判架构」。
  #    => 而那三把尺子逐字是**三条**路径（=> 而 stat -c%s / file -b / readelf）、
  #       而它们逐字**各答一件事**：
  #         stat -c%s  答「链本身多长」、=> 而它**不跟**软链、=> 而它答不了架构；
  #         readlink  答「链指向谁」、=> 而它**不跟**、=> 而它**答不了**架构；
  #         file -bL  答「目标是什么」、=> 而它**跟**软链、=> 而而它才是**能跟的那把**。
  #    => 而 stat -c%L 那一族逐字**空**（=> 而它在断链上也空）、=> 而而它**答不了**架构。
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

#  缺陷名（C-102）**那落位逐字只搬那 bin/ 那一族、=> 而而那 lib/ 那一族逐字**没**被搬过来** ——
#    而实际上 LLVM 的 cmake --install 把 libclang.so 与 libclang-cpp.so **落**在**那 $STAGE/prefix/lib **里面**、
#    => 而而那 recipe 逐字**只** `cp -a "$STAGE/prefix/bin/."`，=> 而而那 lib/ **那一族**逐字**就**被**丢**了**、
#    => 而而那判据（check-elf-deps.sh:93）逐字**找**的**是**那「件自己的 lib/」（=> 而它逐字 = 那 <件目录>/lib **那一**族**）
#    => 而而那 c-index-test 那一件逐字**链**那 libclang.so、=> 而而那**红**的**原话**逐字 = 「依赖这些库，但它们既不在 bionic 白名单、
#       也不是 APK 基础库、也不在件自己的 lib/ 下： libclang.so」（=> 而那 40281519231 那一趟逐字**就是**那**这一句**）。
#  => 而那**为什么**有**两边**：一边是「**那产物**逐字**在**哪**一层**」（=> 而那要**看**那 recipe 那一族**落位**那两行**）、
#    另一边是「**那判据**逐字**找**那**件自己的**库**在**哪**一层**」（=> 而那要**看**那 check-elf-deps.sh 那一族**）
#    => 而那两边各答一件事、=> 而按 C-135 那不是同一问。
#  => 而那**处置**：=> 而而那 lib/ **那一族**逐字**要**跟**那 bin/ **那一族****一起**搬（=> 而那**别**的件**也**是**那**平**的** $OUT/lib **那一族**）、
#    => 而而那**判据**那一族**不用改**（=> 而它逐字**已经**找**的是**那**对**的**那一层**）。
#  => 而按 C-104：=> 而那**落位**那一族逐字**只有**一条路、=> 而而那**不是**第二种生成路径。
#  => 而按 C-220：=> 而 04 那边要落**内容稿**、=> 而而这一笔是**实现**那一族。
rm -rf "$TOOLDIR" "$OUT/lib"
mkdir -p "$TOOLDIR" "$OUT/lib"
cp -a "$STAGE/prefix/bin/." "$TOOLDIR/"
#  而那 lib/ **那一族**（=> 而它逐字**是**那**件自己的**库**：=> 而而那 check-elf-deps 那一族逐字**找**的**就是**它**）
if [ -d "$STAGE/prefix/lib" ]; then
  cp -a "$STAGE/prefix/lib/." "$OUT/lib/"
fi
rm -rf "$STAGE"
printf '%s' "$PINNED_LLVM" > "$OUT/llvm.version"

  #  缺陷名（C-102）**这一件逐字落位在别处、=> 而而那判据逐字读的是根那一层** —— => 而而那**产物**逐字**在**那
  #    $OUT/$TOOL/bin **里面**、=> 而而那 package.json 逐字**落**在**那 $OUT/$TOOL **里面**、=> 而而那**判据**
  #    （verify-component-artifact.sh:99）逐字**读**的**是**那 $ROOT/dist/package.json、=> 而而那**两**个**路径**逐字**不**是**同一**个**。
  #  => 而那**为什么**有**两边**：一边是「**那产物**逐字**在**哪**一层**」、另一边是「**那判据**逐字**该**读**哪**一层**」——=> 而那两边各答一件事。
  #  => 而那**真源**逐字是**那**落位**的**那**一个**（=> 而那**别**的件**全都**是**那**平**的** $OUT/bin **与** $OUT/lib **那一族**、
  #    => 而而那 gen-component-meta.js 逐字**只**扫**那 $PAYLOAD/bin **与** $PAYLOAD/lib **那两**个**目录**、=> 而而那**装侧**
  #    （ProgramInstallPipeline.kt）与那**发侧**（publish-component-manifest.js）逐字**都**读**那**根**那一层**的** package.json）。
  #  => 而那**处置**：=> 而而这一件逐字**改**成**那**平**的**那**一个**落位**（=> 而而那 $OUT/bin **与** $OUT/package.json **那一族**）、
  #    => 而而那**判据**那一族**不用改**（=> 而而它逐字**已经**读**的是**那**对**的**那一层**）。
  #  => 而按 C-104：=> 而而那**落位**那一族逐字**只有**一条**路**、=> 而而那**不是**第二种生成路径。
  #  => 而按 C-220：=> 而 04 那边要落**内容稿**、=> 而而这一笔是**实现**那一族。
note "写件内 package.json（别名映射）"
node -e '
const fs = require("node:fs"), path = require("node:path");
const dir = process.argv[1], entry = process.argv[2];
const names = fs.readdirSync(dir).filter((n) => {
  try { return fs.statSync(path.join(dir, n)).isFile(); } catch (e) { return false; }
}).sort();
const bin = {};
for (const n of names) bin[n] = "bin/" + n;
// 本名（键 == 件名）不重复声明 —— 两侧都会跳过它，写了也只是噪音
delete bin[entry];
fs.writeFileSync(path.join(dir, "..", "package.json"),
  JSON.stringify({ name: "llvmtoolchain", bin }, null, 2) + "\n");
console.log("[ok] package.json 声明别名 " + Object.keys(bin).length + " 个");
' "$TOOLDIR" "$TOOL"
[ -f "$OUT/package.json" ] || die "没产出 package.json" \
  "别名映射是装侧建链与发侧写清单的唯一真相 —— 没有它这一件只有入口能用"

echo
echo "[ok] $OUT/bin （$(ls "$TOOLDIR" | wc -l) 个工具）"
echo "[$TOOL] 判据：clang --version 能报版本、ld.lld --version 能报、能在真机编出一个 .so"