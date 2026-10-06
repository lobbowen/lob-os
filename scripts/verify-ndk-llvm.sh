#!/usr/bin/env bash
# 核实 NDK 与它的 LLVM 版本，并核对仓内钉值。
#
# ── 为什么需要它 ──
# sysroot 来自 NDK，而 clang 件要编的是 LLVM。两者版本不配的话，
# 编出来的 clang 读 sysroot 会有说不清的怪问题（头文件是 API 26 的、
# clang 却按 API 24 的假设去编）。所以要有「两者配得上」这道判据。
#
# ── 为什么不是「查表填个数」──
# 记了数字却不核对 = 装饰。NDK 自己知道它的 LLVM 版本：
#   $NDK/toolchains/llvm/prebuilt/<host>/bin/clang --version
#   → "… clang version 20.0.0 …"
# 本脚本**问它**，再与钉值表比。不一致就判死。
#
# ── 钉值为空时怎么办 ──
# 判红而不是放过。理由：`llvmVersion` 是阶段1c（编 clang）的**前提**，
# 没有它那一阶段根本不能开始；静默放过会让「编 clang」在半途才发现版本不对。
# 但如果将来决定不编 clang 了，把这一格填上即可解除 —— 那是明确的选择，
# 与「忘了填」不同。
set -euo pipefail
export LC_ALL=C

HERE="$(dirname "$0")"
cd "$HERE/.."
ROOT_DIR="$(pwd)"

die() { echo "::error title=$1::${2:-}"; exit 1; }

NDK="${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}}"
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
  NDK=$(ls -d "${ANDROID_HOME:-/nonexistent}"/ndk/* 2>/dev/null | sort -V | tail -1 || true)
fi
[ -n "$NDK" ] && [ -d "$NDK" ] || die "无 NDK" \
  "要核实版本就得有 NDK。设 ANDROID_NDK_LATEST_HOME 或 ANDROID_HOME。"

# ── 1. NDK 版本 ──
GOT_NDK="$(awk -F= '/^Pkg\.Revision/ {gsub(/[[:space:]]/,"",$2); print $2; exit}' "$NDK/source.properties" 2>/dev/null || true)"
[ -n "$GOT_NDK" ] || die "读不出 NDK 版本" "$NDK/source.properties 里没有 Pkg.Revision"
WANT_NDK="$(bash "$ROOT_DIR/scripts/fetch-pinned.sh" --ndk)"
if [ "$GOT_NDK" != "$WANT_NDK" ]; then
  die "NDK 版本与钉值不符" \
    "钉值表要 $WANT_NDK，runner 上是 $GOT_NDK —— 换 NDK 要同时改 userland-sources.json 的 ndkVersion"
fi

# ── 2. LLVM 版本（问 NDK 自己）──
CLANG=""
for c in "$NDK"/toolchains/llvm/prebuilt/*/bin/clang; do
  # 用 -f 而不是 -x：某些容器/沙箱的文件系统对「可执行位」的可见性与真实执行能力
  # 不一致（文件能跑但 -x 报假）。这里要的是「找得到那个 clang」，
  # 跑不跑得动交给下面 --version 那一步判 —— 那才是真的判据。
  [ -f "$c" ] && CLANG="$c" && break
done
[ -n "$CLANG" ] || die "NDK 里找不到 clang" "找 $NDK/toolchains/llvm/prebuilt/*/bin/clang 没找到"
# 找到了还要确认真跑得起来 —— LLVM 版本判据的输入就来自它
if ! "$CLANG" --version >/dev/null 2>&1; then
  die "NDK 的 clang 跑不起来" "$CLANG --version 失败 —— 拿不到 LLVM 版本，判据无从进行"
fi

# 输出形如：Android (…) clang version 20.0.0 (…)；也有形如 clang version 15.0.7 的。
VER_OUT="$("$CLANG" --version 2>&1 | head -3)"
echo "[ndk-llvm] $VER_OUT"
GOT_LLVM="$(printf '%s\n' "$VER_OUT" \
  | sed -n 's/.*clang version \([0-9][0-9.]*\).*/\1/p' | head -1)"
if [ -z "$GOT_LLVM" ]; then
  # 退化路径：有些 NDK 的 clang 不打印 "clang version N"，改读它的 resource dir
  RDIR="$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/lib/clang/* 2>/dev/null | head -1 || true)"
  if [ -n "$RDIR" ]; then
    GOT_LLVM="$(basename "$RDIR")"
    echo "[ndk-llvm] 从 resource dir 读到 LLVM 版本"
  fi
fi
[ -n "$GOT_LLVM" ] || die "读不出 LLVM 版本" \
  "clang --version 的输出里找不到 'clang version N.N.N'：$(printf '%s' "$VER_OUT" | head -1)"
echo "[ndk-llvm] LLVM=$GOT_LLVM"

# ── 3. 与钉值核对 ──
WANT_LLVM="$(bash "$ROOT_DIR/scripts/fetch-pinned.sh" --llvm)"
if [ -z "$WANT_LLVM" ]; then
  die "钉值表没有 llvmVersion" \
    "NDK $GOT_NDK 内置 LLVM $GOT_LLVM。请把 llvmVersion 填进 scripts/userland-sources.json ——" \
    "阶段1c 要编的 clang 必须与 sysroot 同源，否则头文件与编译器假设会对不上。"
fi
# 允许前缀匹配：钉 20 而实际 20.0.0（钉 major 即可）；钉 20.1 而实际 20.1.8 也算配。
case "$GOT_LLVM" in
  "$WANT_LLVM"|"$WANT_LLVM".*) : ;;
  *)
    die "NDK 的 LLVM 版本与钉值不符" \
      "钉值表要 $WANT_LLVM，NDK $GOT_NDK 里是 $GOT_LLVM。改 NDK 就要同时改 llvmVersion ——" \
      "否则编出来的 clang 与 sysroot 不同源。"
    ;;
esac

echo "[ok] NDK $GOT_NDK / LLVM $GOT_LLVM 与钉值一致（ndkVersion=$WANT_NDK llvmVersion=$WANT_LLVM）"
echo "[ok] 阶段1c（编 clang）的前提成立：sysroot 与目标编译器同源"