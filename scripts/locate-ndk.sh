#!/usr/bin/env bash
# 定位 NDK 并核对它与仓内钉值一致 —— 供所有要编 C/C++ 的 job 共用。
#
# ── 为什么要有这个脚本（而不是各 job 内联几行）──
#
# 早先每个 job 自己写「定位 NDK」，只读 `ANDROID_NDK_LATEST_HOME`、
# **不比对钉值**。后果实测过一次：
#
#   · 我们把 ndkVersion 从 29.0.14206865 改成 30.0.16248370；
#   · `ndk-llvm` job 会红（它跑 verify-ndk-llvm.sh，那个比对钉值）；
#   · 而 11 件商店件**继续用 runner 上的 r29 照编**，日志里 CC 路径明明白白
#     是 `.../ndk/29.0.14206865/...` —— 钉值改了，对它们**一点影响都没有**；
#   · 更糟的是**没有任何门禁会发现**：钉值对 11 件是个没人看的数字。
#
# 也就是说「钉住 NDK 版本」这件事只对 1 个 job 生效，另外 11 个各编各的。
# 所以比对必须放在**每个**编造 job 都会走的位置，而不是只放在某一个 job 里。
set -euo pipefail
export LC_ALL=C

HERE="$(dirname "$0")"
ROOT_DIR="$(cd "$HERE/.." && pwd)"

die() {
  local title="$1"; shift
  echo "::error title=$title::$(printf '%s\n' "$@")"
  exit 1
}

# 读哪条环境变量必须写死，不能靠 fallback 顺序碰运气 ——
# runner 上同时有三条，且其中两条指向另一个版本：
#   ANDROID_NDK_LATEST_HOME = 30.0.16248370
#   ANDROID_NDK_HOME        = 27.3.13750724
#   ANDROID_NDK_ROOT        = 27.3.13750724
NDK="${ANDROID_NDK_LATEST_HOME:-}"
[ -n "$NDK" ] && [ -d "$NDK" ] || die "无 NDK" \
  "要编 C/C++ 就得有 NDK。它是 sysroot 与交叉编译器的来源。" \
  "runner 上应提供 ANDROID_NDK_LATEST_HOME；本机可用环境变量指定。"

TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
# 判「能不能用」用**真的跑一次**而不是 `[ -x ]`。
# 实测这台设备上 `[ -x ]` 对 filesDir 下可执行的脚本会返回假
# （文件确实是 755、也确实能 exec，但 test -x 说不行）——
# 所以「文件在」与「文件能用」得分开判，否则门禁在本地验、在 CI 才通过。
# 跑一次 `--version` 既证明存在，也证明真能执行，还顺带暴露架构不对。
CC="$TC/aarch64-linux-android23-clang"
[ -f "$CC" ] || die "无 clang" \
  "缺 $CC" \
  "宿主标签在脚本里写死了 linux-x86_64；runner 若不是 x86_64 要改这里。"
CLANG_VER="$("$CC" --version 2>/dev/null | head -1 || true)"
[ -n "$CLANG_VER" ] || die "clang 跑不起来" \
  "$CC 存在但 \`--version\` 没输出 —— 它可能是宿主二进制（不该执行）或依赖缺失。"
# 判「目标架构对不对」要**问它目标三元组**，不能看 `--version` 里有没有
# "aarch64" 字样 —— 我第一版就是那么写的，实测立刻被打脸：
#   Android (…) clang version 21.0.0 (https://…)   ← 交叉编译器的 --version 不自报目标
# 于是 `aarch64-linux-android23-clang` 这种驱动被误判成「架构不对」，全员判红。
# `--print-target-triple` 才是它自己认的目标；拿不到再退回真编一个 .o 看 e_machine。
TRIPLE="$("$CC" -print-target-triple 2>/dev/null || true)"
[ -n "$TRIPLE" ] || TRIPLE="$("$CC" -dumpmachine 2>/dev/null || true)"
MACH=""
OK_TARGET=0
case "$TRIPLE" in *aarch64*|*arm64*) OK_TARGET=1 ;; esac
if [ "$OK_TARGET" != 1 ]; then
  TMPD="$(mktemp -d)"
  printf 'int probe(void){return 0;}\n' > "$TMPD/probe.c"
  if "$CC" -c -o "$TMPD/probe.o" "$TMPD/probe.c" >/dev/null 2>&1 && [ -f "$TMPD/probe.o" ]; then
    MACH="$(od -An -tx1 -j18 -N2 "$TMPD/probe.o" 2>/dev/null | tr -d ' \n')"
    [ "$MACH" = "b700" ] && OK_TARGET=1
  fi
  rm -rf "$TMPD"
fi
[ "$OK_TARGET" = 1 ] || die "clang 产不出 aarch64 目标" \
  "目标三元组 = ${TRIPLE:-（读不出）}；试编 .o 的 e_machine = ${MACH:-（编不出）}（0xB7 才是 AArch64）" \
  "驱动是 $CC —— 名字里的 aarch64 只是命名约定，判据要看它自己的目标。"
echo "[ndk] clang: $CLANG_VER"
[ -n "$TRIPLE" ] && echo "[ndk] 目标三元组: $TRIPLE"

# ── 与钉值比对（这一步是本脚本存在的理由）──
GOT_NDK="$(awk -F= '/^Pkg\.Revision/ {gsub(/[[:space:]]/,"",$2); print $2; exit}' "$NDK/source.properties" 2>/dev/null || true)"
[ -n "$GOT_NDK" ] || die "读不出 NDK 版本" "$NDK/source.properties 里没有 Pkg.Revision"

WANT_NDK="$(bash "$ROOT_DIR/scripts/fetch-pinned.sh" --ndk)"
if [ "$GOT_NDK" != "$WANT_NDK" ]; then
  die "NDK 版本与钉值不符" \
    "钉值表要 $WANT_NDK，runner 上是 $GOT_NDK。" \
    "两个选择：(a) 把 userland-sources.json 的 ndkVersion 改成 $GOT_NDK 并按它重取钉值；" \
    "(b) 在 CI 里显式安装 $WANT_NDK，不要用 runner 自带的那版。" \
    "**不要**只改钉值了事 —— 不改这里的话所有编造 job 仍然会用 runner 上那版。"
fi

echo "[ndk] 目录=$NDK 版本=$GOT_NDK（与钉值一致）"
{ echo "CC=$TC/aarch64-linux-android23-clang"
  echo "CXX=$TC/aarch64-linux-android23-clang++"
  echo "LLVM_AR=$TC/llvm-ar"
  echo "LLVM_RANLIB=$TC/llvm-ranlib"
  echo "LLVM_STRIP=$TC/llvm-strip"
  echo "LLVM_READELF=$TC/llvm-readelf"; } >> "$GITHUB_ENV"
echo "CC=$CC" >> "${GITHUB_STEP_SUMMARY:-/dev/null}" 2>/dev/null || true