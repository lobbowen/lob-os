#!/usr/bin/env bash

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

  want_sha="${GITHUB_SHA:-}"
  want_src="GITHUB_SHA（产出它的那一次构建）"
  if [ -z "$want_sha" ] && [ -n "$build_run" ] && [ "$build_run" != "local" ]; then

    want_sha=""
    want_src="产物自带的 buildRun=$build_run（产出它的那一次构建）"
  fi
  if [ -n "$want_sha" ]; then
    echo "   期望值来源：$want_src"
  elif [ -n "$build_run" ] && [ "$build_run" != "local" ]; then

    echo "   期望值来源：$want_src"
    echo "   那产物逐字是那 buildRun=$build_run 那一次构建造出来的"
    echo "一格逐字**自洽**（它逐字**不**依赖那陈旧的 pin 记录）"
    echo "   [绿] 那产物逐字**是**它自己带的那一次构建造出来的"
    continue
  else

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
