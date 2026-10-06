#!/usr/bin/env bash
# clang / lld / binutils —— 开发环境件（阶段1c），走**商店通道**。
#
# 类目判定（components/README.md 的唯一规则）：
#   「构建物需要被**程序**用裸名调用（clang/ld.lld/llvm-ar…）→ userland」
# 它不是被内核 dlopen 的（那才是 native），所以它在 userland 而不是 native。
# 产出形态一直是商店件形态（dist/<tool>/bin + dist/<tool>.version），
# 只是早先文件名与定位写错了。
#
# ── 形态：交叉编译 LLVM 到 aarch64-linux-android ──
# 为什么不下载官方预编包（实测过，别再问）：
#   LLVM 官方 release **确实**有 aarch64-Linux 构建（LLVM-<ver>-Linux-ARM64.tar.xz，
#   实测 23.1.3 那份 1744 MiB），但它是 **glibc** 构建：
#     · 动态链接器是 /lib/ld-linux-aarch64.so.1，我们的是 /system/bin/linker64
#     · 它引用 GLIBC_2.x 版本化符号，Bionic 没有这些符号名
#     · NEEDED 是 libc.so.6 / libstdc++.so.6，Bionic 只有 libc.so / libc++_shared.so
#   这不是「换链接器重链」能解决的 —— 引用的是符号名，不是路径。
#   要在 Android 上跑它就得装 glibc 兼容层（Termux 的 proot 那条路），
#   而我们要的是一个**原生 Android 系统**，不是在 Android 里跑 Linux 用户空间。
#   所以只能自己编 —— 这与 Termux 的做法一致（它也是在 Android 上自编 clang）。
#
# ── 本脚本的诚实边界 ──
# 交叉编译 LLVM 到 Bionic 是 Termux 级别的工程量（自宿主资源目录、libunwind、
# dlopen 行为差异）。本机无 NDK、无编译器，**编不出来也无法验证**。
# 所以这里交付的是「配方 + 判据」，真实编成与否由 CI 判。
# 第一次跑大概率会失败 —— 那时按取证件里的线索补，不是把判据放松。
#
# ── 为什么不全量编 LLVM ──
# 全量（clang + lld + lldb + flang + MLIR + 全部 runtime）在这个设备上毫无意义，
# 且体积失控。按「有真实消费者才补」：只要 consumer 真正会用的那几个。
set -euo pipefail
export LC_ALL=C

HERE="$(dirname "$0")"
cd "$HERE/.."
ROOT_DIR="$(pwd)"

OUT="${OUT:-dist}"
TOOL="llvmtoolchain"
ABI="${ABI:-arm64-v8a}"
API="${ANDROID_API:-23}"
JOBS="${JOBS:-4}"
# 只编真正会被用到的 target（llvm-project 的子目录）—— 每个都要数十分钟，
# 全量编在 CI 上是几小时的浪费，而其中大半我们永远不跑。
PROJECTS="clang;lld"        # 只编这两个。llvm 本体由它们隐式带上，不写 llvm 是对的

case "$OUT" in /*) ;; *) OUT="$ROOT_DIR/$OUT" ;; esac
TOOLDIR="$OUT/$TOOL/bin"
STAGE="$OUT/.$TOOL-stage"

die() { echo "::error title=$1::${2:-}"; exit 1; }
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

# ── 版本同源闸门（先于任何下载）──
# sysroot 来自 NDK，clang 是 LLVM —— 版本不配会编出与头文件假设错位的东西，
# 而且不报错。所以先核，不配就别浪费几小时去编。
note "核实 NDK 与 LLVM 是否同源"
bash "$ROOT_DIR/scripts/verify-ndk-llvm.sh" || exit 1

WANT_LLVM="$(bash "$ROOT_DIR/scripts/fetch-pinned.sh" --llvm)"
# 读钉的 LLVM 版本用 --src-version（纯查表）。早先这里跑的是
# `--pin llvm /dev/null` —— 那会**下载整个 171 MiB 源码包**，只为了从一行输出里
# sed 出版本号。查表 0.1 秒，下载要几分钟；而这一步只是「该不该编」的判断。
PINNED_LLVM="$(bash "$ROOT_DIR/scripts/fetch-pinned.sh" --src-version llvm 2>/dev/null || true)"
# 对齐检查**不可跳过**。
#
# 早先写成 `if [ -n "$WANT_LLVM" ] && [ -n "$PINNED_LLVM" ]`，
# 于是 llvmVersion 空（还没填）时整段检查被跳过 —— 而「没验」被当成了「通过」。
# 那是典型的静默降级：编出来的东西与 sysroot 错位，却没有任何提示。
#
# 正确形态：**不知道就不许编**。llvmVersion 空时直接判红，并说清怎么填。
if [ -z "$WANT_LLVM" ]; then
  die "userland-sources.json 没有 llvmVersion" \
    "不知道 NDK 内置的是哪个 LLVM，就无法判断钉的源码是否同源 —— 没验过就不该继续编。" \
    "先跑 scripts/verify-ndk-llvm.sh（需要真 NDK，通常在 CI 上），" \
    "从报错里读到实际版本号，填进 userland-sources.json 的 llvmVersion。"
fi
if [ -z "$PINNED_LLVM" ]; then
  die "钉值表里没有 sources.llvm" \
    "拿不到要编的 LLVM 版本。用 scripts/pin-github-release.js 钉一个：" \
    "  node scripts/pin-github-release.js --repo llvm/llvm-project --tag llvmorg-<版本> --key llvm --match '^llvm-project-.*[.]src[.]tar[.]xz$'"
fi
case "$PINNED_LLVM" in
  "$WANT_LLVM"|"$WANT_LLVM".*) : ;;
  *)
    die "钉的 LLVM 源码与 NDK 内置的不是同一大版本" \
      "钉的是 $PINNED_LLVM，NDK 内置 $WANT_LLVM。编出来的 clang 会与 sysroot 的头文件假设错位，" \
      "而且**不报错**。按 NDK 的版本重钉（sha256 由工具取，不必下载）：" \
      "  node scripts/pin-github-release.js --repo llvm/llvm-project --tag llvmorg-$WANT_LLVM --key llvm --match '^llvm-project-.*[.]src[.]tar[.]xz$'"
    ;;
esac
note "版本同源已核对：NDK $WANT_LLVM ←→ 钉的 LLVM $PINNED_LLVM"

# ── 取源码 ──
WORK="$ROOT_DIR/work/$TOOL"
mkdir -p "$WORK"
SRC="$WORK/llvm-src"
if [ ! -d "$SRC/llvm" ]; then
  TGZ="$WORK/llvm.src.tar.xz"
  note "取 LLVM 源码（走仓内唯一入口，sha256 逐字节校验）"
  bash "$ROOT_DIR/scripts/fetch-pinned.sh" --pin llvm "$TGZ" \
    || die "取 LLVM 源码失败" "钉值/来源见 scripts/userland-sources.json 的 llvm 键"
  # .tar.xz —— CI runner 预装 xz（build-userland-git.sh 也依赖它）。显式检查，
  # 缺了就在这里说清怎么修，而不是让 tar 报一句看不懂的 "exec xz: No such file"。
  command -v xz >/dev/null 2>&1 || command -v unxz >/dev/null 2>&1 || die "无 xz" \
    "LLVM 官方只发 .tar.xz。请 sudo apt-get install -y xz-utils"
  rm -rf "$SRC" && mkdir -p "$SRC"
  tar xJf "$TGZ" -C "$SRC" --strip-components=1 \
    || die "解包失败" "$TGZ —— 格式是否与 .tar.xz 相符？"
fi
[ -f "$SRC/llvm/CMakeLists.txt" ] || die "源码树异常" "缺 llvm/CMakeLists.txt —— 拿到的不是 llvm-project"
note "源码就位：$(cat "$SRC/llvm/CMakeLists.txt" 2>/dev/null | grep -m1 -o 'project(LLVM)' || echo 'llvm-project')"

# ── 配置 ──
BUILD="$WORK/build"
rm -rf "$BUILD"
mkdir -p "$BUILD"

# 关键开关，逐条写明理由（不是抄 cmake 的默认值）：
#   CMAKE_BUILD_TYPE=Release           —— 设备上要的是能用的编译器，不是调试版
#   LLVM_TARGETS_TO_BUILD=Aarch64      —— 我们只要编 aarch64；带上 X86 就多几十分钟
#   LLVM_ENABLE_PROJECTS              —— 只要 clang/lld，flang/lldb/MLIR 一概不要
#   LLVM_INCLUDE_TESTS=OFF            —— 测试用 gtest/unittest，编它们没有意义
#   LLVM_INCLUDE_BENCHMARKS=OFF        —— 同上
#   LLVM_ENABLE_TERMINFO=OFF           —— terminfo 是 curses 的，设备上用不到；
#                                        但 LLVM 里 terminfo 也用于诊断输出，
#                                        关掉能省掉对 ncurses 的依赖（Bionic 没有）
#   CLANG_ENABLE_STATIC_ANALYZER=OFF  —— analyzer 依赖额外的 runtime，且占体积
#   LLVM_ENABLE_ZLIB=OFF               —— 关键：不让它去找宿主 zlib。
#                                        链接到宿主 .so 会产出在设备上跑不了的二进制
#                                        （Bionic 找不到 libz.so.1 那份）。
#                                        要压缩就静态带 LZMA，或干脆不用。
#   LLVM_ENABLE_LIBXML2=OFF            —— 同理，宿主 libxml2 是 glibc 构建
#   LLVM_ENABLE_LIBEDIT=OFF            —— 同上
#   LLVM_ENABLE_ZSTD=OFF               —— 同上
#   LLVM_PARALLEL_LINK_JOBS           —— 链接 LLVM 极吃内存，并行会 OOM；
#                                        限制并行度比让它炸掉好
cmake -S "$SRC/llvm" -B "$BUILD" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_TOOLCHAIN" \
  -DANDROID_ABI="$ABI" -DANDROID_PLATFORM="android-$API" \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_INSTALL_PREFIX="$STAGE/prefix" \
  -DCMAKE_INSTALL_BINDIR=bin \
  -DLLVM_TARGETS_TO_BUILD=Aarch64 \
  -DLLVM_ENABLE_PROJECTS="$PROJECTS" \
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

# ── 编 ──
note "开始编（$JOBS 作业）—— 这一步在 CI 上要几十分钟到数小时"
cmake --build "$BUILD" -j"$JOBS" \
  > "$WORK/build.log" 2>&1 \
  || { echo "=== LLVM 编译失败取证 ==="; \
       echo "（error 行）"; grep -nE "error:|Error [0-9]+$|undefined (symbol|reference)" "$WORK/build.log" | head -30 || true; \
       echo "（末 60 行）"; tail -60 "$WORK/build.log"; \
       echo; echo "首次失败是**预期内**的：交叉编译 LLVM 到 Bionic 需要打补丁（Termux 级别的量）。"; \
       echo "按上面的 error 行定位缺什么，**不要**放宽这里的判据 —— 放宽换来的是「编出来了但在设备上跑不了」。"; \
       exit 1; }

# ── 安装与形态自检 ──
cmake --install "$BUILD" > "$WORK/install.log" 2>&1 \
  || { echo "=== install 失败（末 30 行）==="; tail -30 "$WORK/install.log"; exit 1; }

# binutils 的七个工具：as/ld/arf/nm/strip/objdump/readelf
# LLVM 里对应的是 llvm-as / ld.lld / llvm-ar / llvm-nm / llvm-strip /
# llvm-objdump / llvm-readobj。取前者的现代等价物，名字对齐 Debian 那套。
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
  INFO=$(file -b "$f")
  case "$INFO" in *aarch64*|*arm64*|*ARM64*) : ;; *) BAD="$BAD $b(架构:$INFO)" ;; esac
  # 16KB 对齐：Android 15+ 硬要求
  al="$("$LLVM_READELF" -W -l "$f" 2>/dev/null | awk '/^[[:space:]]*LOAD/{print $NF}' \
        | while read -r a; do case "$a" in 0x[0-9a-fA-F]*) ;; *) continue ;; esac; \
            d=$(( a )); [ "$d" -eq 0 ] && continue; [ $(( d % 16384 )) -ne 0 ] && printf ' '; done)"
  [ -n "$al" ] && BAD="$BAD $b(16KB对齐不合格)"
  printf '[ok] %-16s %s 字节\n' "$b" "$s"
done
[ -z "$BAD" ] || die "形态不合格" "$BAD"

# 判据3：不能带 PT_INTERP 之外的解释器。带 /system/bin/linker64 是对的，
# 带 /lib/ld-linux-* 就是拿宿主 glibc 编的 —— 装到设备上 exec format error。
for b in clang ld.lld llvm-ar; do
  interp="$("$LLVM_READELF" -W -l "$STAGE/prefix/bin/$b" 2>/dev/null \
            | sed -n 's/.*\[Requesting program interpreter: \(.*\)\].*/\1/p')"
  case "$interp" in
    ""|/system/bin/linker64) : ;;
    *) die "解释器不对" "$b 的 PT_INTERP=$interp —— 说明是拿宿主链接器编的，设备上跑不了" ;;
  esac
done
echo "[ok] 解释器形态正确（/system/bin/linker64）"

# ── 落位 ──
rm -rf "$OUT/$TOOL"
mkdir -p "$TOOLDIR"
cp -a "$STAGE/prefix/bin/." "$TOOLDIR/"
rm -rf "$STAGE"
printf '%s' "$PINNED_LLVM" > "$OUT/$TOOL.version"
echo
echo "[ok] $OUT/$TOOL/bin （$(ls "$TOOLDIR" | wc -l) 个工具）"
echo "[$TOOL] 判据：clang --version 能报版本、ld.lld --version 能报、能在真机编出一个 .so"