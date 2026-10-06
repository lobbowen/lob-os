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

# 第二个及之后的参数都会并进同一条 ::error —— 早先只取 ${2:-}，
# 于是调用点传的第 3、第 4 句被静默丢掉（写上去像是说了，其实没输出）。
# 所以这里用 shift 收下全部剩余参数。
die() {
  # 第二个及之后的参数都并进同一条 ::error。只取 ${2:-} 的话，
  # 调用点传的第 3 句往后会被**静默丢掉** —— 写上去像是说了，其实没输出。
  local title="$1"; shift
  echo "::error title=$title::$(printf '%s\n' "$@")"
  exit 1
}

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

# ── 取 LLVM 版本：优先读 clang_source_info.md ──
#
# 为什么不是直接抓 `clang version N.N.N`（早先就是这么写的）：
# **NDK r29 的 clang --version 不给语义版本。** 上游 changelog 逐版记的是
# AOSP clang 修订号 —— r27 clang-r522817 / r28 clang-r530567e /
# r29 clang-r563880c / r30 clang-r574158c / r31 clang-r596125。
# 那个 rNNNNNN 不是 LLVM 版本，`clang version` 那行要么没有、要么版本号
# 与源码不对应。所以抓正则抓出来的数**不能用来挑 llvmorg-* 源码**。
#
# 上游自己指了路：release notes 说 "See `clang_source_info.md` in the toolchain"。
# 那个文件里才有「这份 LLVM 来自哪个版本/commit」，那才是能对上
# llvmorg-<语义版本> 的东西。所以**先读它**。
#
# 三条路径依次降级，每条都实测过：
#   1. clang_source_info.md（权威，唯一能给出语义版本的）
#   2. `clang --version` 的语义版本号（老 NDK 是这个形态）
#   3. resource dir 名 lib/clang/<ver>（最后兜底，可能只是 major）
VER_OUT="$("$CLANG" --version 2>&1 | head -3)"
echo "[ndk-llvm] $VER_OUT"

# AOSP clang 的修订号在输出末尾，形如
#   "Android (…) clang version 20.0.0git (…/ndk r563880c)"
# 它**不在** "clang version" 紧后面，所以不能只在那一句里找。
# 实测：只匹配 "clang version r…" 抓不到；要在整行里找独立的 rNNNNNN。
GOT_REV="$(printf '%s\n' "$VER_OUT" | sed -n 's/.*[ (]r\([0-9a-f]\{6,\}\)[) ].*/\1/p' | head -1)"
[ -n "$GOT_REV" ] || GOT_REV="$(printf '%s\n' "$VER_OUT" | grep -o 'clang-r[0-9a-f]\{6,\}' | head -1 | sed 's/clang-//')"
if [ -n "$GOT_REV" ]; then
  echo "[ndk-llvm] NDK 这一版的 clang 修订号 = $GOT_REV（不是语义版本）"
fi

GOT_LLVM=""
SRC=""
for si in "$NDK"/toolchains/llvm/prebuilt/*/AndroidVersion.txt \
           "$NDK"/toolchains/llvm/prebuilt/*/clang_source_info.md \
           "$NDK"/AndroidVersion.txt \
           "$NDK"/clang_source_info.md \
           "$NDK"/toolchains/llvm/prebuilt/*/share/clang_source_info.md; do
  [ -f "$si" ] || continue
  # 这个文件有两种真实形态，都要认：
  #
  #   (a) "20.1.8"  —— AOSP 预编包的 AndroidVersion.txt 形态
  #       update-prebuilts.py 读的就是它：
  #         full_version = contents[0]           # 例如 '7.0.1'
  #         revision     = contents[1].split()[-1]  # 例如 'r326829'
  #       **里面没有 llvmorg 字样** —— 我第一版只 grep llvmorg，
  #       对着真实文件实测 rc=1（读不到），已修。
  #   (b) "llvmorg-20.1.8" —— 有些构建自己写的 tag 形态
  #
  # (a) 先找纯语义版本：X.Y 或 X.Y.Z。
  #     **必须用 -E 且不能带捕获组** —— 本机 grep 是 toybox 0.8.13，
  #     不支持 BRE 的 \( \) 组（实测 grep -o '[0-9]\+\.[0-9]\+(\.[0-9]\+)\?' 返回空）。
  #     CI 上是 GNU grep，两边都要能用，所以写成 alternation 而不用组。
  #     「based on r563880c」那行不含 X.Y 形态，天然不会被取到。
  v="$(grep -o -E '[0-9]+\.[0-9]+\.[0-9]+|[0-9]+\.[0-9]+' "$si" | head -1)"
  # (b) 再找 llvmorg- 形态
  [ -n "$v" ] || v="$(grep -o 'llvmorg-[0-9][0-9.]*' "$si" | head -1)"
  if [ -n "$v" ]; then GOT_LLVM="${v#llvmorg-}"; SRC="$si"; break; fi
done
if [ -n "$GOT_LLVM" ]; then
  echo "[ndk-llvm] 从 $SRC 读到 LLVM 语义版本 = $GOT_LLVM"
fi

if [ -z "$GOT_LLVM" ]; then
  # 退化路径 2：`clang version 20.0.0`（老 NDK 是这个形态）
  GOT_LLVM="$(printf '%s\n' "$VER_OUT" \
    | sed -n 's/.*clang version \([0-9][0-9.]*\).*/\1/p' | head -1)"
  [ -n "$GOT_LLVM" ] && SRC="clang --version"
fi
if [ -z "$GOT_LLVM" ]; then
  # 退化路径 3：resource dir 名
  RDIR="$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/lib/clang/* 2>/dev/null | head -1 || true)"
  if [ -n "$RDIR" ]; then
    GOT_LLVM="$(basename "$RDIR")"
    SRC="resource dir"
    echo "[ndk-llvm] 从 resource dir 读到 LLVM 版本"
  fi
fi
if [ -n "$GOT_LLVM" ]; then
  echo "[ndk-llvm] LLVM=$GOT_LLVM"
  # 诚实标注数据来源：只有读到 clang_source_info.md 才算「确认同源」。
  # 从 `clang version` 退化读到的，在带修订号时**不能**当同源依据 ——
  # 实测那一行是 "20.0.0git"，语义版本的最后一段会带 git 后缀，
  # 它与要编的 llvmorg-<ver> 未必是同一个发布点。
  if [ "$SRC" = "clang --version" ] && [ -n "$GOT_REV" ]; then
    echo "::warning title=版本来源退化::这一版 clang 带修订号 r$GOT_REV，" \
      "但没读到 clang_source_info.md —— 下面的 $GOT_LLVM 来自 \`clang --version\` 的 git 版本串，" \
      "**不足以证明与要编的 llvmorg-<ver> 同源**。" \
      "请在 runner 上读 $NDK/toolchains/llvm/prebuilt/*/clang_source_info.md 确认。"
  fi
fi

# ── 3. 与钉值核对 ──
WANT_LLVM="$(bash "$ROOT_DIR/scripts/fetch-pinned.sh" --llvm)"
if [ -z "$WANT_LLVM" ]; then
  die "钉值表没有 llvmVersion" \
    "NDK $GOT_NDK 内置 LLVM $GOT_LLVM。请把 llvmVersion 填进 scripts/userland-sources.json ——" \
    "阶段1c 要编的 clang 必须与 sysroot 同源，否则头文件与编译器假设会对不上。" \
    "填 $GOT_LLVM（实测值），或填它的前缀（如 ${GOT_LLVM%%.*}）——" \
    "**不要**把 sources.llvm.version 填到这一格，那是另一件事。"
fi
# 比 **major**（= LLVM 的 release 线），不按完整版本串比。
#
# 为什么不能按串比（早先写的是 `"$GOT_LLVM" in "$WANT_LLVM".*`，已改）：
# NDK 声明的版本可能**没有对应的上游 tag**。实测 r29 的 AndroidVersion.txt
# 写 `21.0.0`，而上游 21-init 之后直接是 21.1.0，没有 llvmorg-21.0.0。
# 按串比的话，钉 21.1.8（确实与 NDK 同在 21.x 线）会被判红。
# major 就是 release 线，这才是「同源」要比较的东西。
WANT_MAJOR="${WANT_LLVM%%.*}"
GOT_MAJOR="${GOT_LLVM%%.*}"
if [ "$WANT_MAJOR" = "$GOT_MAJOR" ]; then
  :
else
  die "NDK 的 LLVM 与钉值不是同一条 release 线" \
    "钉值表要 $WANT_LLVM（$WANT_MAJOR.x 线），NDK $GOT_NDK 里是 $GOT_LLVM（$GOT_MAJOR.x 线）。" \
    "改 NDK 就要同时改 llvmVersion —— 否则编出来的 clang 与 sysroot 不同源。"
fi

DEGRADED=0
[ "$SRC" = "clang --version" ] && [ -n "$GOT_REV" ] && DEGRADED=1

echo "[ok] NDK $GOT_NDK / LLVM $GOT_LLVM 与钉值一致（ndkVersion=$WANT_NDK llvmVersion=$WANT_LLVM）"
if [ "$DEGRADED" = 1 ]; then
  # 上一条已经 warning 过了。这里**不能**再说「同源成立」——
  # 同一份输出里既说「不足以证明同源」又说「前提成立」是自相矛盾，
  # 而人只会记住后面那句。所以这里如实说：版本号对上了，来源还没坐实。
  echo "[ndk-llvm] 版本号对上了，但**同源尚未坐实**（见上面那条 warning）"
  echo "[ndk-llvm] 要坐实：在 runner 上读 $NDK/toolchains/llvm/prebuilt/*/clang_source_info.md"
  exit 2
fi
echo "[ok] 阶段1c（编 clang）的前提成立：sysroot 与目标编译器同源（依据 $SRC）"