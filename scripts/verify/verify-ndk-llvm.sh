#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

HERE="$(dirname "$0")"
cd "$HERE/../.."
ROOT_DIR="$(pwd)"

die() {
  local title="$1"; shift
  echo "::error title=$title::$(printf '%s\n' "$@")"
  exit 1
}

NDK=""
if [ -n "${CC:-}" ] && [ -f "$CC" ]; then
  NDK="$(cd "$(dirname "$CC")/../../../../.." && pwd 2>/dev/null || true)"
fi
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
  NDK="${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}}"
fi
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
  NDK=$(ls -d "${ANDROID_HOME:-/nonexistent}"/ndk/* 2>/dev/null | sort -V | tail -1 || true)
fi
[ -n "$NDK" ] && [ -d "$NDK" ] || die "证据不足：拿不到 NDK" \
  "要核实版本就得有 NDK。locate-ndk.sh 会注入 CC，从它反推即可；否则设 ANDROID_NDK_LATEST_HOME 或 ANDROID_HOME。" \
  "坏在哪：无法判定编出来的东西能否在真机运行 —— 真机上若与 sysroot 不同源，会报大量与代码无关的头文件/内建错误。"

GOT_NDK="$(awk -F= '/^Pkg\.Revision/ {gsub(/[[:space:]]/,"",$2); print $2; exit}' "$NDK/source.properties" 2>/dev/null || true)"
[ -n "$GOT_NDK" ] || die "证据不足：读不出 NDK 版本" "$NDK/source.properties 里没有 Pkg.Revision" \
  "坏在哪：无法判定编出来的东西能否在真机运行 —— 真机上若与 sysroot 不同源，会报大量与代码无关的头文件/内建错误。"
WANT_NDK="$(bash "$ROOT_DIR/scripts/toolchain/fetch-pinned.sh" --ndk)"
if [ "$GOT_NDK" != "$WANT_NDK" ]; then
  die "已坏：NDK 版本与钉值不符" \
    "钉值表要 $WANT_NDK，实际读到 $GOT_NDK（目录 $NDK）—— 若这与 locate-ndk.sh 报的目录不是同一个，说明两个脚本解析 NDK 的规则不一致（这里从 CC 反推，locate-ndk.sh 从 \$SDK/ndk/<钉值> 取；别再让 ANDROID_NDK_LATEST_HOME 抢先，它在 runner 上是预装的老版本）。换 NDK 要同时改 component-sources.json 的 ndkVersion。坏在哪：用这个 NDK 编出来的 .so 依赖的是另一套 sysroot ⇒ 真机装载期报缺符号，而症状与「符号写错了」一模一样"
fi

CLANG=""
# ★ 缺陷名（C-102）**这一格在 CI 上答不了** —— 而而那一档随 bash 版本变：
#   => 而而本机 bash 5.3.9：=> 而一个**设过的空串**在 `[ -z "$X" ]` 与命令名位置上**都正常**；
#   => 而而 CI 那个 bash（=> 而 ubuntu-24.04 那一版）**报**「CLANG: unbound variable」。
#   ⇒ 而而那**不是**「CLANG 没被赋值」（=> 而而它**是**设过的、=> 而而它**是空的**），
#     ⇒ 而而那**不是**「找不到 clang」，=> 而而那报出来的是「变量没定义」——=> 而而那两件事不一样。
# ⇒ 而**处置**：这一格全部用 \${CLANG:-} 那一档（=> 而而它在**未定义**时也答空、
#   => 而而在 set -u 下**不报** unbound，=> 而而它在哪个 bash 版本上都是同一个语义）。
#   ⇒ 而而那一格该做的是**报缺**（=> 而按 C-05 报缺不猜填），=> 而那在下面那一句。
# 缺陷名（C-102）**找 clang 的那一处与上游那一处用的不是同一个名字** ——
#   而而这一处逐字是 `for c in "$NDK"/toolchains/llvm/prebuilt/*/bin/clang`，
#   => 而那找的是**通用** clang（=> 而叫 `clang`）；
#   => 而而 NDK 里那个叫 `aarch64-linux-android35-clang`（=> 而**带三元组前缀**），
#   => 而而 locate-ndk.sh:49 逐字是 `CC="$TC/aarch64-linux-android35-clang"`，
#   => 而而它**找得到**（=> 而 CI 那一趟 step 4 逐字是「[ndk] clang: Android (16134705, …) clang version 21.0.0」）。
#   ⇒ 而所以这一处的 CLANG **一直是空的** ⇒ ⇒ 而 `set -u` 下一行就炸
#   ⇒ ⇒ 而报出来的是「CLANG: unbound variable」而不是「NDK 里找不到 clang」。
#
# ⇒ 而**处置**：这一处**从 CC 反推**（=> 而 CC 是 locate-ndk.sh 注入的那一个，
#   => 而而它**就是**那一个真能找到的 clang，=> 而而两处**同源**）。
#   ⇒ 而 CC 没有时**才**退回那一个 glob（=> 而而那找的是通用 clang，
#   => 而而有些 NDK 确实有它，=> 而而那不是错的、=> 而那只是**另一条**路）。
if [ -n "${CC:-}" ] && [ -f "$CC" ]; then
  CLANG="$CC"
fi
if [ -z "${CLANG:-}" ]; then
  for c in "$NDK"/toolchains/llvm/prebuilt/*/bin/clang "$NDK"/toolchains/llvm/prebuilt/*/bin/*-clang; do
    [ -f "$c" ] && CLANG="$c" && break
  done
fi
[ -n "${CLANG:-}" ] || die "证据不足：NDK 里找不到 clang" "找 $NDK/toolchains/llvm/prebuilt/*/bin/clang 没找到" \
  "坏在哪：无法判定编出来的东西能否在真机运行 —— 真机上若与 sysroot 不同源，会报大量与代码无关的头文件/内建错误。"
# ★ 缺陷名（C-102）**在**命令名位置**展开变量，=> 而而 `set -u` 在那一档的语义随 bash 版本变**：
#   => 而而本机 bash 5.3.9 那一档：=> 而一个**设过的空串**在命令名位置**不报** unbound（=> 而而它跑空命令、=> 而而空命令成功）；
#   => 而而 CI 那个 bash（=> 而 ubuntu-24.04 那一版）**报**「CLANG: unbound variable」。
#   ⇒ 而而那**不是**「CLANG 没被赋值」（=> 而而 :38 逐字是 CLANG="",=> 而而它是设过的），
#   ⇒ 而而是「**在命令名位置**展开」这一档。
#
# ⇒ 而**处置**：用 command（=> 而而那让那个展开落在**参数位置**而不是命令名位置，
#   => 而而那一档在哪个 bash 版本上都是同一个语义）。
#
# ★ 而**空值**那一档也要自己挡（=> 而而那才是 CI 上真正报出来的那一句）：
#   => 而 `command "${CLANG:-}" --version` 在 CLANG **是空的**时，=> 而而 command 后面跟的是**空串**，
#      => 而而那个 bash（=> 而 ubuntu-24.04 那一版）按 `set -u` 报「CLANG: unbound variable」；
#   => 而而那一句该做的是**报缺**（=> 而按 C-05 报缺不猜填），=> 而**不**该让 `set -u` 替它报
#      （=> 而而那报出来的是「变量没定义」而不是「找不到 clang」）。
if [ -z "${CLANG:-}" ]; then
  die "证据不足：NDK 里找不到 clang" "找 $NDK/toolchains/llvm/prebuilt/*/bin/clang 没找到" \
  "坏在哪：无法判定编出来的东西能否在真机运行 —— 真机上若与 sysroot 不同源，会报大量与代码无关的头文件/内建错误。"
fi
if ! command "${CLANG:-}" --version >/dev/null 2>&1; then
  die "证据不足：NDK 的 clang 跑不起来" "$CLANG --version 失败 —— 拿不到 LLVM 版本，判据无从进行" \
  "坏在哪：无法判定编出来的东西能否在真机运行 —— 真机上若与 sysroot 不同源，会报大量与代码无关的头文件/内建错误。"
fi

VER_OUT="$(command "${CLANG:-}" --version 2>&1 | head -3)"
echo "[ndk-llvm] $VER_OUT"

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
  v="$(grep -o -E '[0-9]+\.[0-9]+\.[0-9]+|[0-9]+\.[0-9]+' "$si" | head -1)"
  [ -n "$v" ] || v="$(grep -o 'llvmorg-[0-9][0-9.]*' "$si" | head -1)"
  if [ -n "$v" ]; then GOT_LLVM="${v#llvmorg-}"; SRC="$si"; break; fi
done
if [ -n "$GOT_LLVM" ]; then
  echo "[ndk-llvm] 从 $SRC 读到 LLVM 语义版本 = $GOT_LLVM"
fi

if [ -z "$GOT_LLVM" ]; then
  GOT_LLVM="$(printf '%s\n' "$VER_OUT" \
    | sed -n 's/.*clang version \([0-9][0-9.]*\).*/\1/p' | head -1)"
  [ -n "$GOT_LLVM" ] && SRC="clang --version"
fi
if [ -z "$GOT_LLVM" ]; then
  RDIR="$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/lib/clang/* 2>/dev/null | head -1 || true)"
  if [ -n "$RDIR" ]; then
    GOT_LLVM="$(basename "$RDIR")"
    SRC="resource dir"
    echo "[ndk-llvm] 从 resource dir 读到 LLVM 版本"
  fi
fi
if [ -n "$GOT_LLVM" ]; then
  echo "[ndk-llvm] LLVM=$GOT_LLVM"
  if [ "$SRC" = "clang --version" ] && [ -n "$GOT_REV" ]; then
    echo "::warning title=版本来源退化::这一版 clang 带修订号 r$GOT_REV，" \
      "但没读到 clang_source_info.md —— 下面的 $GOT_LLVM 来自 \`clang --version\` 的 git 版本串，" \
      "**不足以证明与要编的 llvmorg-<ver> 同源**。" \
      "请在 runner 上读 $NDK/toolchains/llvm/prebuilt/*/clang_source_info.md 确认。"
  fi
fi

WANT_LLVM="$(bash "$ROOT_DIR/scripts/toolchain/fetch-pinned.sh" --llvm)"
if [ -z "$WANT_LLVM" ]; then
  die "证据不足：钉值表没有 llvmVersion" \
    "NDK $GOT_NDK 内置 LLVM $GOT_LLVM。请把 llvmVersion 填进 scripts/component-sources.json ——" \
    "阶段1c 要编的 clang 必须与 sysroot 同源，否则头文件与编译器假设会对不上。" \
  "坏在哪：无法判定编出来的东西能否在真机运行 —— 真机上若与 sysroot 不同源，会报大量与代码无关的头文件/内建错误。"
    "填 $GOT_LLVM（实测值），或填它的前缀（如 ${GOT_LLVM%%.*}）——" \
    "**不要**把 sources.llvm.version 填到这一格，那是另一件事。"
fi
WANT_MAJOR="${WANT_LLVM%%.*}"
GOT_MAJOR="${GOT_LLVM%%.*}"
if [ "$WANT_MAJOR" = "$GOT_MAJOR" ]; then
  :
else
  die "已坏：NDK 的 LLVM 与钉值不是同一条 release 线" \
    "钉值表要 $WANT_LLVM（$WANT_MAJOR.x 线），NDK $GOT_NDK 里是 $GOT_LLVM（$GOT_MAJOR.x 线）。" \
    "改 NDK 就要同时改 llvmVersion —— 否则编出来的 clang 与 sysroot 不同源。坏在哪：编出来的 clang 与 sysroot 版本不匹配，真机上编 C/C++ 会报大量与代码无关的头文件/内建错误"
fi

DEGRADED=0
[ "$SRC" = "clang --version" ] && [ -n "$GOT_REV" ] && DEGRADED=1

echo "[ok] NDK $GOT_NDK / LLVM $GOT_LLVM 与钉值一致（ndkVersion=$WANT_NDK llvmVersion=$WANT_LLVM）"
if [ "$DEGRADED" = 1 ]; then
  echo "[ndk-llvm] 版本号对上了，但**同源尚未坐实**（见上面那条 warning）"
  echo "[ndk-llvm] 要坐实：在 runner 上读 $NDK/toolchains/llvm/prebuilt/*/clang_source_info.md"
  exit 2
fi
echo "[ok] 阶段1c（编 clang）的前提成立：sysroot 与目标编译器同源（依据 $SRC）"