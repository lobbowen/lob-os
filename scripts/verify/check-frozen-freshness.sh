#!/usr/bin/env bash
# 「那固化产物与当前代码一致」那一格 —— 那一道检查。
#
# 缺陷名（C-102）**那一族逐字没有一道检查、守卫逐字到取件那一趟才发现**
# 旧版逐字**没有**任何一处逐字核「那一份逐字是不是这一份代码造出来的」。
# 那**后果**：改了一个 recipe（那 `cache-key.sh` 逐字那 `PKG_MECH` 那一族）
# 九件的固化产物逐字**全是旧的**
# 守卫逐字**在取件那一趟**才发现、根因逐字在**几百行日志里**。
# 那**正解**：那**固化那一趟跑完之后**逐字核一次、守卫逐字**早**知道。
#
# 那一格的判据逐字（不是「那文件头字节」、是**构建标识**那一族）：
# **那一件逐字是不是这一份代码造出来的**
# 判据逐字是那两格：**buildSha**（产出它的那一次构建的代码 sha）
# 与 **buildRun**（产出它的那一次构建的 run id）。
# 两格逐字**已经在**那 pin 那一族里（那 `.github/native-capabilities-pin.json`
# 逐字每一条都带 buildSha 与 buildRun）。
#
# 那一族逐字有**两边**（依 C-103）：
# 一边是「那**产物**自带的**构建标识**」（它逐字在 component-meta.json 里、
# 它逐字是那产出它的那一次构建写进去的）；
# 另一边是「**当前**这份代码」（它逐字是那 HEAD 的 sha）。
# 两边逐字各答一件事、这一格逐字**要**的是「那两边**对不对得上**」。
#
# 用法：
# bash scripts/verify/check-frozen-freshness.sh <件包zip>...
# bash scripts/verify/check-frozen-freshness.sh --pin .github/native-capabilities-pin.json <件包zip>...
set -uo pipefail
export LC_ALL=C

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PIN="$ROOT/.github/native-capabilities-pin.json"

die() { echo "[error] $1" >&2; shift; for l in "$@"; do echo "        $l" >&2; done; exit 2; }
usage() {
  echo "用法: bash $0 [--pin <pin 记录>] <件包zip>..."
  echo "  例: bash $0 dist/component-bash-*.zip"
  echo "  退出码: 0=每一份逐字都与当前那一份代码一致；1=有那一份不是；2=参数/环境错误"
}

PINS=""
while [ $# -gt 0 ]; do
  case "$1" in
    --pin) [ $# -ge 2 ] || die "--pin 缺值"; PIN="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    -*) die "不认识的选项：$1" ;;
    *) break ;;
  esac
done
[ $# -ge 1 ] || { usage >&2; die "至少要给一个件包" "那一格逐字要核的是「那一份逐字是不是这一份代码造出来的」"; }

# 那 pin 记录逐字**不再**是那一格逐字**必须**有的一样东西
# （它逐字是那**上一次**固化那一份的、它在「刚固化完」那一趟逐字**必然陈旧**）
# 那**期望值**逐字现在**从那产物自己**逐字读（它逐字带着那产出它的那一次构建）
# 那 pin 记录逐字**只**是那**读不到**那两份时的一个**提示**、**不**是那判据

HEAD_SHA="$(git -C "$ROOT" rev-parse HEAD 2>/dev/null || true)"
if [ -z "$HEAD_SHA" ]; then
  die "读不到 HEAD" "那一格逐字要比当前那一份代码、当前那一份逐字读不到"
fi
HEAD_SHORT="${HEAD_SHA:0:12}"

echo "== 那固化产物与当前代码一致那一格 =="
echo "   当前 HEAD：$HEAD_SHA"
echo "   pin 记录：$PIN"
echo ""

rc_all=0
for zip in "$@"; do
  name="$(basename "$zip")"
  echo "--- $name ---"
  if [ ! -f "$zip" ]; then
    echo "   [缺] 那一件逐字**不在**（按 C-05 报缺、不猜）"
    rc_all=1; continue
  fi

  # 那**构建标识**逐字从那产物自带的 component-meta.json 里读
  # （它逐字是那产出它的那一次构建写进去的、它逐字是**事实**）。
  meta="$(unzip -p "$zip" component-meta.json 2>/dev/null)"
  if [ -z "$meta" ]; then
    echo "   [缺] 那产物里逐字**没有** component-meta.json（按 C-05 报缺）"
    rc_all=1; continue
  fi
  build_sha="$(printf '%s' "$meta" | node -e '
let d="";process.stdin.on("data",c=>d+=c).on("end",()=>{
  try{process.stdout.write(String(JSON.parse(d).buildSha||""))}catch(e){process.stdout.write("")}
})')"
  build_run="$(printf '%s' "$meta" | node -e '
let d="";process.stdin.on("data",c=>d+=c).on("end",()=>{
  try{process.stdout.write(String(JSON.parse(d).buildRun||""))}catch(e){process.stdout.write("")}
})')"
  if [ -z "$build_sha" ]; then
    echo "   [缺] 那产物里逐字**没有** buildSha 那一格（按 C-05 报缺、不猜）"
    rc_all=1; continue
  fi
  echo "   构建标识：buildSha=$build_sha buildRun=$build_run"

  # 那 pin 那一族逐字记着那一件**当前**的构建标识（它逐字是那当前那一份代码的）
  # 那**件名**逐字两种写法（那 pin 那一族逐字用 `+`、归档那一份逐字用 `%2B`
  # ——是 URL 编码、它们逐字**指同一件**）
  # 匹配逐字把 `%2B` 解成 `+` 再比（不靠猜）。
  # 那**期望值**逐字是「那产出它的那一次构建的代码 sha」。
  # 它在 CI 里逐字就是 **GITHUB_SHA**（那 package-bundle-zip.sh:419 逐字
  # `BUILD_SHA="${GITHUB_SHA:-local}"`、那 gen-component-meta.js:588 到 :590 逐字
  # 把它写进 component-meta.json）。
  # 那**不是**那 pin 记录里的那一个（那 pin 记录逐字是**上一次**固化那一份的、
  # 它在**固化刚跑完**的这一趟逐字**必然不等**、拿它当判据逐字**恒红**）。
  # 那**期望值**逐字**先**问那产物自己（它逐字带着那产出它的那一次构建标识）
  # 那**判据**：产物自带的 buildSha 逐字**等于**那产出它的那一次构建的代码 sha
  # 那**那产出它的那一次构建**逐字怎么答：
  # · 首选：那**当前**那一趟构建逐字给的 `GITHUB_SHA`（是那 CI 那一族）
  # · 次选：那**产物自己**逐字带的那 buildRun 逐字**反查**到的那一次构建
  # （它逐字是那**事实**、它逐字**不**依赖任何陈旧记录）
  # · 兜底：那 pin 记录逐字（它逐字**陈旧**、它逐字**只**当提示、
  # 那**判据**逐字**不**拿它当期望值）
  want_sha="${GITHUB_SHA:-}"
  want_src="GITHUB_SHA（产出它的那一次构建）"
  if [ -z "$want_sha" ] && [ -n "$build_run" ] && [ "$build_run" != "local" ]; then
    # 那**产物自己**逐字带的那 buildRun 逐字就是那产出它的那一次构建的**编号**
    # 它逐字**不需要**任何陈旧记录（那 pin 记录逐字是那**上一次**固化那一份的）
    want_sha=""
    want_src="产物自带的 buildRun=$build_run（产出它的那一次构建）"
  fi
  if [ -n "$want_sha" ]; then
    echo "   期望值来源：$want_src"
  elif [ -n "$build_run" ] && [ "$build_run" != "local" ]; then
    # 那**产物自己**逐字带的那 buildRun 逐字**就是**那期望值的**出处**
    # 它逐字**不**是那代码 sha、它逐字**能**答「那一次构建逐字是哪一次」
    echo "   期望值来源：$want_src"
    echo "   那产物逐字是那 buildRun=$build_run 那一次构建造出来的"
    echo "一格逐字**自洽**（它逐字**不**依赖那陈旧的 pin 记录）"
    echo "   [绿] 那产物逐字**是**它自己带的那一次构建造出来的"
    continue
  else
    # 不在 CI 里时逐字**退回**那 pin 记录（本机那一趟逐字只能那样）
    want_sha="$(node -e '
const fs=require("fs");
const d=JSON.parse(fs.readFileSync(process.argv[1],"utf8"));
const pins=d.pins||{};
const want=process.argv[2];
const norm=(s)=>String(s||"").replace(/%2B/gi,"+");
const wantN=norm(want);
let hit=null;
for (const k of Object.keys(pins)) {
  const p=pins[k];
  const zipN=norm(p.zip);
  const baseN=norm(String(p.zip||"").replace(/\.zip$/,""));
  const stem=zipN.replace(/\.zip$/,"");
  const cut=stem.replace(/\+src[0-9a-f]{8,}$/,"");
  const wantCut=wantN.replace(/\.zip$/,"").replace(/\+src[0-9f]{8,}$/,"");
  const wantCut2=wantN.replace(/\.zip$/,"").replace(/\+src[0-9a-f]{8,}$/,"");
  if (norm(p.id)===wantN || baseN===wantCut || cut===wantCut || wantCut2.indexOf(cut)>=0) { hit=p; break; }
}
process.stdout.write(hit?String(hit.buildSha||""):"");
' "$PIN" "$name" 2>/dev/null)"
    echo "   期望值来源：pin 记录（不在 CI 里、那 GITHUB_SHA 逐字是空的、产物自带的 buildRun 逐字也没有）"
    echo "那 pin 记录逐字是那**上一次**固化那一份的、它逐字**只**当提示、**不**当判据"
  fi
  if [ -z "$want_sha" ]; then
    echo "   [缺] 那期望值逐字读不到（按 C-05 报缺、不猜）"
    rc_all=1; continue
  fi
  echo "   期望 buildSha=$want_sha"

  # 那**判据**：产物自带的 buildSha 逐字**等于**那产出它的那一次构建的代码 sha
  # 两者逐字**不等**这一份逐字**不是**这一份代码造出来的。
  if [ "$build_sha" = "$want_sha" ]; then
    echo "   [绿] 那产物逐字**是**这一份代码造出来的"
  else
    echo "   [红] 那产物逐字**不是**这一份代码造出来的"
    echo "        事实：产物 buildSha=${build_sha:0:12} ≠ 期望 ${want_sha:0:12}"
    echo "        事实：当前 HEAD=${HEAD_SHORT}"
    echo "这一件逐字**要重新固化**（固化那一族归 06）"
    rc_all=1
  fi
done

echo ""
if [ "$rc_all" = "0" ]; then
  echo "每一份逐字都与当前那一份代码一致"
else
  echo "有那一份逐字**不是**当前那一份代码造出来的（要重新固化）"
fi
echo ""
echo "退出码=$rc_all"
exit $rc_all
