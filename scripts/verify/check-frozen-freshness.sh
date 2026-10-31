#!/usr/bin/env bash
# 「那固化产物与当前代码一致」那一格 —— 那一道检查。
#
# 缺陷名（C-102）**那一族逐字没有一道检查、=> 而而那守卫逐字到取件那一趟才发现**
#   => 而那旧版逐字**没有**任何一处逐字核「那一份逐字是不是这一份代码造出来的」。
#   => 而而那**后果**：=> 而那改了一个 recipe（=> 而那 `cache-key.sh` 逐字那 `PKG_MECH` 那一族）
#     => 而而那九件的固化产物逐字**全是旧的**
#     => 而而那守卫逐字**在取件那一趟**才发现、=> 而而那根因逐字在**几百行日志里**。
#   => 而那**正解**：=> 而那**固化那一趟跑完之后**逐字核一次、=> 而那守卫逐字**早**知道。
#
# ★ 那一格的判据逐字（=> 而那不是「那文件头字节」、=> 而那是**构建标识**那一族）：
#   **那一件逐字是不是这一份代码造出来的**
#   => 而那判据逐字是那两格：**buildSha**（=> 而那产出它的那一次构建的代码 sha）
#     与 **buildRun**（=> 而那产出它的那一次构建的 run id）。
#   => 而那两格逐字**已经在**那 pin 那一族里（=> 而那 `.github/native-capabilities-pin.json`
#     逐字每一条都带 buildSha 与 buildRun）。
#
# ★ 那一族逐字有**两边**（依 C-103）：
#   一边是「那**产物**自带的**构建标识**」（=> 而那它逐字在 component-meta.json 里、
#     => 而那它逐字是那产出它的那一次构建写进去的）；
#   另一边是「**当前**这份代码」（=> 而那它逐字是那 HEAD 的 sha）。
#   => 而那两边逐字各答一件事、=> 而那这一格逐字**要**的是「那两边**对不对得上**」。
#
# 用法：
#   bash scripts/verify/check-frozen-freshness.sh <件包zip>...
#   bash scripts/verify/check-frozen-freshness.sh --pin .github/native-capabilities-pin.json <件包zip>...
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

if [ ! -f "$PIN" ]; then
  die "pin 记录读不到：$PIN" "那一格逐字要那 buildSha 那一族、=> 而那一族逐字不在 pin 记录里"
fi

HEAD_SHA="$(git -C "$ROOT" rev-parse HEAD 2>/dev/null || true)"
if [ -z "$HEAD_SHA" ]; then
  die "读不到 HEAD" "那一格逐字要比当前那一份代码、=> 而那当前那一份逐字读不到"
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
    echo "   [缺] 那一件逐字**不在**（=> 而按 C-05 报缺、=> 而不猜）"
    rc_all=1; continue
  fi

  #  => 而那**构建标识**逐字从那产物自带的 component-meta.json 里读
  #     （=> 而那它逐字是那产出它的那一次构建写进去的、=> 而那它逐字是**事实**）。
  meta="$(unzip -p "$zip" component-meta.json 2>/dev/null)"
  if [ -z "$meta" ]; then
    echo "   [缺] 那产物里逐字**没有** component-meta.json（=> 而按 C-05 报缺）"
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
    echo "   [缺] 那产物里逐字**没有** buildSha 那一格（=> 而按 C-05 报缺、=> 而不猜）"
    rc_all=1; continue
  fi
  echo "   构建标识：buildSha=$build_sha buildRun=$build_run"

  #  => 而那 pin 那一族逐字记着那一件**当前**的构建标识（=> 而那它逐字是那当前那一份代码的）
  #  => 而那**件名**逐字两种写法（=> 而那 pin 那一族逐字用 `+`、=> 而那归档那一份逐字用 `%2B`
  #     ——=> 而那那是 URL 编码、=> 而那它们逐字**指同一件**）
  #     => 而而那匹配逐字把 `%2B` 解成 `+` 再比（=> 而那不靠猜）。
  #  => 而那**期望值**逐字是「那产出它的那一次构建的代码 sha」。
  #     => 而那它在 CI 里逐字就是 **GITHUB_SHA**（=> 而那 package-bundle-zip.sh:419 逐字
  #       `BUILD_SHA="${GITHUB_SHA:-local}"`、=> 而而那 gen-component-meta.js:588 到 :590 逐字
  #       把它写进 component-meta.json）。
  #     => 而而那**不是**那 pin 记录里的那一个（=> 而那 pin 记录逐字是**上一次**固化那一份的、
  #       => 而而那它在**固化刚跑完**的这一趟逐字**必然不等**、=> 而而那拿它当判据逐字**恒红**）。
  want_sha="${GITHUB_SHA:-}"
  if [ -n "$want_sha" ]; then
    echo "   期望值来源：GITHUB_SHA（=> 而那产出它的那一次构建）"
  else
    #  => 而那不在 CI 里时逐字**退回**那 pin 记录（=> 而而那本机那一趟逐字只能那样）
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
    echo "   期望值来源：pin 记录（=> 而那不在 CI 里、=> 而而那 GITHUB_SHA 逐字是空的）"
  fi
  if [ -z "$want_sha" ]; then
    echo "   [缺] 那期望值逐字读不到（=> 而按 C-05 报缺、=> 而不猜）"
    rc_all=1; continue
  fi
  echo "   期望 buildSha=$want_sha"

  #  => 而那**判据**：=> 而那产物自带的 buildSha 逐字**等于**那产出它的那一次构建的代码 sha
  #     => 而而那两者逐字**不等** => 而那这一份逐字**不是**这一份代码造出来的。
  if [ "$build_sha" = "$want_sha" ]; then
    echo "   [绿] 那产物逐字**是**这一份代码造出来的"
  else
    echo "   [红] 那产物逐字**不是**这一份代码造出来的"
    echo "        事实：产物 buildSha=${build_sha:0:12} ≠ 期望 ${want_sha:0:12}"
    echo "        事实：当前 HEAD=${HEAD_SHORT}"
    echo "        => 而那这一件逐字**要重新固化**（=> 而那固化那一族归 06）"
    rc_all=1
  fi
done

echo ""
if [ "$rc_all" = "0" ]; then
  echo "=> 而那每一份逐字都与当前那一份代码一致"
else
  echo "=> 而那有那一份逐字**不是**当前那一份代码造出来的（=> 而那要重新固化）"
fi
echo ""
echo "退出码=$rc_all"
exit $rc_all
