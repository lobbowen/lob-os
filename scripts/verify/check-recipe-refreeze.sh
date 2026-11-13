#!/usr/bin/env bash

set -uo pipefail
export LC_ALL=C

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CACHE_KEY="$ROOT/scripts/toolchain/cache-key.sh"

die() { echo "[error] $1" >&2; shift; for l in "$@"; do echo "        $l" >&2; done; exit 2; }

PIECES=()
if [ $# -ge 1 ]; then
  PIECES=("$@")
else

  node -e '
const fs=require("fs");
const d=JSON.parse(fs.readFileSync(process.argv[1],"utf8"));
const pins=d.pins||{};
const ids=[];
for (const k of Object.keys(pins)) { const id=pins[k].id; if (id && ids.indexOf(id)<0) ids.push(id); }
if (ids.indexOf("git")<0) ids.push("git");
process.stdout.write(ids.join(" "));
' "$ROOT/.github/native-capabilities-pin.json" > /tmp/.lobos-pieces.$$
  read -r -a PIECES < /tmp/.lobos-pieces.$$
  rm -f /tmp/.lobos-pieces.$$
fi
[ ${#PIECES[@]} -ge 1 ] || die "那九件逐字读不到" "而那 pin 记录逐字读不到、按 C-05 报缺"

if [ ! -x "$CACHE_KEY" ] && [ ! -f "$CACHE_KEY" ]; then
  die "cache-key.sh 读不到：$CACHE_KEY" "而那一格逐字要那指纹算据、算据逐字不在"
fi

publishedAssetNames() {
  local tool="$1"
  local tag asset
  tag="$(bash "$CACHE_KEY" tag "$tool" 2>/dev/null || true)"
  [ -n "$tag" ] || return 0
  [ -n "${GITHUB_TOKEN:-}" ] || return 0
  asset="$(bash "$CACHE_KEY" asset "$tool" 2>/dev/null || true)"
  [ -n "$asset" ] || return 0

  curl -sS -L --max-time 30 -H "Authorization: Bearer $GITHUB_TOKEN" \
    "https://api.github.com/repos/lobbowen/lob-os/releases/tags/$tag" 2>/dev/null \
    | node -e '
let d="";process.stdin.on("data",c=>d+=c).on("end",()=>{
  try {
    const j=JSON.parse(d);
    for (const a of (j.assets||[])) process.stdout.write(String(a.name||"")+"\n");
  } catch(e) {}
})' 2>/dev/null | grep -E 'src[0-9a-f]+' || true
}

echo "== 那「recipe 指纹变了要重新固化」那一格 =="
echo "   算据：scripts/toolchain/cache-key.sh"
echo "   当前 HEAD：$(git -C "$ROOT" rev-parse HEAD 2>/dev/null | cut -c1-12)"
echo ""

rc_all=0
for p in "${PIECES[@]}"; do

  cur_asset="$(bash "$CACHE_KEY" asset "$p" 2>/dev/null || true)"
  cur_src="$(printf '%s' "$cur_asset" | grep -o 'src[0-9a-f]*' | head -1)"
  if [ -z "$cur_src" ]; then
    echo "--- $p ---"
    echo "   [缺] 那当前指纹逐字算不出来（按 C-05 报缺、不猜）"
    rc_all=1; continue
  fi
  echo "--- $p ---"
  echo "   当前指纹：$cur_src"
  echo "   当前资产名：${cur_asset:-(读不到)}"

  published="$(publishedAssetNames "$p")"

  if printf '%s\n' "$published" | sed 's/%2B/+/g' | grep -Fxq "$(printf '%s' "$cur_asset" | sed 's/%2B/+/g')"; then
    echo "   当前资产名在 Release 上：${cur_asset}"
    echo "这一格逐字**答的是**「那当前这一份逐字有没有被发布出去」、答案逐字是「有」"
    echo "   [绿] 那当前资产名逐字**在** Release 上（它逐字**不需要**重新固化）"
    continue
  fi

  if [ -z "$published" ]; then
    echo "   当前资产名在 Release 上：${cur_asset:-(算不出)}"
    echo "   来源：Release 上那一份读不到（按 C-05 报缺、不猜）"
    if [ -z "${GITHUB_TOKEN:-}" ]; then
      echo "   [缺] 那 Release 上那一份逐字读不到（那 token 逐字是空的）"
      echo "这一格逐字**只能**在 CI 里判（那 CI 那一族逐字有那 token）"
      rc_all=1; continue
    fi
    echo "   [缺] 那 Release 上那一份逐字读不到（按 C-05 报缺、不猜）"
    echo "那 pin 记录逐字是那**上一次**固化那一份的、它逐字**不**当判据"
    rc_all=1; continue
  fi

  echo "   当前资产名：${cur_asset}"
  echo "   Release 上逐字有的那几份："
  printf '%s\n' "$published" | sed 's/^/        /' | head -8
  echo "   [红] 那当前资产名逐字**不**在 Release 上（这一件逐字**要**发布出去）"
  rc_all=1
  continue
done

echo ""
if [ "$rc_all" = "0" ]; then
  echo "当前那几份逐字**都**在 Release 上（它们逐字**不用**再发布）"
else
  echo "有那几件逐字**当前那一份不**在 Release 上（它们逐字**要**发布出去）"
  echo "那**发布那一族**归 06、那**规范那一层**归 04"
fi
echo ""
echo "退出码=$rc_all"
exit $rc_all
