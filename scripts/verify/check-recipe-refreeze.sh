#!/usr/bin/env bash
# 「recipe 指纹变了 ⇒ 那九件逐字要重新固化」那一格 —— 那一道自检。
#
# 缺陷名（C-102）**那一族逐字要等到取件那一趟才发现、=> 而而那根因逐字在几百行日志里**
#   => 而那旧版逐字**没有**任何一处逐字在**改动那一格**的那一趟就报「那九件逐字要重新固化」。
#   => 而而那**后果**：=> 而那改了一个 recipe（=> 而那 `cache-key.sh` 逐字那 `PKG_MECH` 那一族）
#     => 而而那九件的固化产物逐字**全是旧的**
#     => 而而那守卫逐字**在取件那一趟**才红、=> 而而那根因逐字在**几百行日志里**。
#   => 而那**正解**：=> 而那**改动那一格**的那一趟逐字就算出那新的指纹、
#     => 而而它逐字跟那**当前**已发布的那一份逐字比、=> 而而那不等就逐字**当场**报。
#
# ★ 那一族逐字有**两边**（依 C-103）：
#   一边是「那**当前**这份代码**算出来**的指纹」（=> 而那它逐字是那 `cache-key.sh` 那一族算的）；
#   另一边是「那**已经发布**的那一份的指纹」（=> 而那它逐字是那 Release 上那资产名里的那一段）。
#   => 而那两边逐字各答一件事、=> 而那这一格逐字**要**的是「那两边**对不对得上**」。
#
# ★ 这一族逐字**不是探针**：=> 而它是那一族逐字**该有的自检**
#   （=> 而那它逐字答的是「那一族的**状态**」、=> 而那它逐字**不**是为了取读数而临时造的东西）。
#
# 用法：
#   bash scripts/verify/check-recipe-refreeze.sh                 # 核那九件
#   bash scripts/verify/check-recipe-refreeze.sh <件名>...        # 核那几件
set -uo pipefail
export LC_ALL=C

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CACHE_KEY="$ROOT/scripts/toolchain/cache-key.sh"

die() { echo "[error] $1" >&2; shift; for l in "$@"; do echo "        $l" >&2; done; exit 2; }

#  => 而那**九件**逐字是那底座件那一族（=> 而那它是那 `native-capabilities-pin.json` 那一族记的那几件）
PIECES=()
if [ $# -ge 1 ]; then
  PIECES=("$@")
else
  #  => 而那**九件**逐字是那底座件那一族（=> 而那它是那取件那一族
  #     `fetch-prebuilt-pieces.sh:193` 逐字 `PIECES=(bash busybox crypto curl git jq openssl ripgrep zlib)`）。
  #  => 而那 pin 那一族逐字只记那**八件**（=> 而那 git 那一件逐字走 tool 筐、=> 而那它逐字不在 pin 记录里）
  #     => 而而那**九件**逐字**该**都核（=> 而那 git 那一件逐字用那 tool 筐那算法）。
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
[ ${#PIECES[@]} -ge 1 ] || die "那九件逐字读不到" "而那 pin 记录逐字读不到、=> 而而那按 C-05 报缺"

if [ ! -x "$CACHE_KEY" ] && [ ! -f "$CACHE_KEY" ]; then
  die "cache-key.sh 读不到：$CACHE_KEY" "而那一格逐字要那指纹算据、=> 而那算据逐字不在"
fi

echo "== 那「recipe 指纹变了要重新固化」那一格 =="
echo "   算据：scripts/toolchain/cache-key.sh"
echo "   当前 HEAD：$(git -C "$ROOT" rev-parse HEAD 2>/dev/null | cut -c1-12)"
echo ""

rc_all=0
for p in "${PIECES[@]}"; do
  #  => 而那**当前**的指纹逐字是那算据给的（=> 而那 tag 那一族逐字含那 src<12 位>）
  #  => 而那**当前**的指纹逐字是那 `cache-key.sh asset` 那一族算的
  #     （=> 而那资产名逐字含那 src<12 位> 那一段、=> 而那它逐字就是那指纹）。
  cur_asset="$(bash "$CACHE_KEY" asset "$p" 2>/dev/null || true)"
  cur_src="$(printf '%s' "$cur_asset" | grep -o 'src[0-9a-f]*' | head -1)"
  if [ -z "$cur_src" ]; then
    echo "--- $p ---"
    echo "   [缺] 那当前指纹逐字算不出来（=> 而按 C-05 报缺、=> 而不猜）"
    rc_all=1; continue
  fi
  echo "--- $p ---"
  echo "   当前指纹：$cur_src"
  echo "   当前资产名：${cur_asset:-(读不到)}"
  #  => 而那**旧**的那一份逐字是那 pin 记录里记的那一件（=> 而那它逐字是那**上一次**固化的）
  old="$(node -e '
const fs=require("fs");
const d=JSON.parse(fs.readFileSync(process.argv[1],"utf8"));
const pins=d.pins||{};
const want=process.argv[2];
for (const k of Object.keys(pins)) {
  const q=pins[k];
  if (q.id===want) { process.stdout.write(String(q.zip||"")); break; }
}
' "$ROOT/.github/native-capabilities-pin.json" "$p" 2>/dev/null)"
  old_src="$(printf '%s' "$old" | grep -o 'src[0-9a-f]*' | head -1)"
  echo "   已发布那一份：${old_src:-(pin 记录里读不到)}"
  if [ -z "$old_src" ]; then
    echo "   [缺] 那已发布那一份的指纹逐字读不到（=> 而按 C-05 报缺、=> 而不猜）"
    rc_all=1; continue
  fi
  if [ "$cur_src" = "$old_src" ]; then
    echo "   [绿] 那指纹逐字**没变**（=> 而那这一件逐字**不用**重新固化）"
  else
    echo "   [红] 那指纹逐字**变了**（=> 而那这一件逐字**要重新固化**）"
    echo "        事实：当前 $cur_src ≠ 已发布 $old_src"
    echo "        => 而那改动了那一族 recipe、=> 而而那固化产物逐字是旧的"
    echo "        => 而而那**固化那一族**归 06、=> 而那**规范那一层**归 04"
    rc_all=1
  fi
done

echo ""
if [ "$rc_all" = "0" ]; then
  echo "=> 而那指纹逐字**都没变**（=> 而那那几件逐字**不用**重新固化）"
else
  echo "=> 而那有那几件逐字**要重新固化**（=> 而那在取件那一趟之前就该知道）"
fi
echo ""
echo "退出码=$rc_all"
exit $rc_all
