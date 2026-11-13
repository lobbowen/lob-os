#!/usr/bin/env bash
# 「recipe 指纹变了那九件逐字要重新固化」那一格 —— 那一道自检。
#
# 缺陷名（C-102）**那一族逐字要等到取件那一趟才发现、根因逐字在几百行日志里**
# 旧版逐字**没有**任何一处逐字在**改动那一格**的那一趟就报「那九件逐字要重新固化」。
# 那**后果**：改了一个 recipe（那 `cache-key.sh` 逐字那 `PKG_MECH` 那一族）
# 九件的固化产物逐字**全是旧的**
# 守卫逐字**在取件那一趟**才红、根因逐字在**几百行日志里**。
# 那**正解**：那**改动那一格**的那一趟逐字就算出那新的指纹、
# 它逐字跟那**当前**已发布的那一份逐字比、不等就逐字**当场**报。
#
# 那一族逐字有**两边**（依 C-103）：
# 一边是「那**当前**这份代码**算出来**的指纹」（它逐字是那 `cache-key.sh` 那一族算的）；
# 另一边是「那**已经发布**的那一份的指纹」（它逐字是那 Release 上那资产名里的那一段）。
# 两边逐字各答一件事、这一格逐字**要**的是「那两边**对不对得上**」。
#
# 这一族逐字**不是探针**：它是那一族逐字**该有的自检**
# （它逐字答的是「那一族的**状态**」、它逐字**不**是为了取读数而临时造的东西）。
#
# 用法：
# bash scripts/verify/check-recipe-refreeze.sh # 核那九件
# bash scripts/verify/check-recipe-refreeze.sh <件名>... # 核那几件
set -uo pipefail
export LC_ALL=C

ROOT="$(cd "$(dirname "$0")/../.."&& pwd)"
CACHE_KEY="$ROOT/scripts/toolchain/cache-key.sh"

die() { echo "[error] $1" >&2; shift; for l in "$@"; do echo "$l" >&2; done; exit 2; }

# 那**九件**逐字是那底座件那一族（它是那 `native-capabilities-pin.json` 那一族记的那几件）
PIECES=()
if [ $# -ge 1 ]; then
  PIECES=("$@")
else
  # 那**九件**逐字是那底座件那一族（它是那取件那一族
  # `fetch-prebuilt-pieces.sh:193` 逐字 `PIECES=(bash busybox crypto curl git jq openssl ripgrep zlib)`）。
  # 那 pin 那一族逐字只记那**八件**（那 git 那一件逐字走 tool 筐、它逐字不在 pin 记录里）
  # 那**九件**逐字**该**都核（那 git 那一件逐字用那 tool 筐那算法）。
  node -e '
const fs=require("fs");
const d=JSON.parse(fs.readFileSync(process.argv[1],"utf8"));
const pins=d.pins||{};
const ids=[];
for (const k of Object.keys(pins)) { const id=pins[k].id; if (id && ids.indexOf(id)<0) ids.push(id); }
if (ids.indexOf("git")<0) ids.push("git");
process.stdout.write(ids.join(""));
' "$ROOT/.github/native-capabilities-pin.json" > /tmp/.lobos-pieces.$$
  read -r -a PIECES < /tmp/.lobos-pieces.$$
  rm -f /tmp/.lobos-pieces.$$
fi
[ ${#PIECES[@]} -ge 1 ] || die "那九件逐字读不到" "而那 pin 记录逐字读不到、按 C-05 报缺"

if [ ! -x "$CACHE_KEY" ] && [ ! -f "$CACHE_KEY" ]; then
  die "cache-key.sh 读不到：$CACHE_KEY" "而那一格逐字要那指纹算据、算据逐字不在"
fi

# 那**已发布**那一份的资产名（从那 Release 上读、逐字要那 token）
publishedAssetNames() {
  local tool="$1"
  local tag asset
  tag="$(bash "$CACHE_KEY"tag "$tool"2>/dev/null || true)"
  [ -n "$tag" ] || return 0
  [ -n "${GITHUB_TOKEN:-}" ] || return 0
  asset="$(bash "$CACHE_KEY"asset "$tool"2>/dev/null || true)"
  [ -n "$asset" ] || return 0
  # 那**已发布**的那一份逐字是那 tag 下逐字**同源**的那一个（那 src 段逐字在名字里）
  curl -sS -L --max-time 30 -H "Authorization: Bearer $GITHUB_TOKEN" \
    "https://api.github.com/repos/lobbowen/lob-os/releases/tags/$tag" 2>/dev/null \
    | node -e '
let d="";process.stdin.on("data",c=>d+=c).on("end",()=>{
  try {
    const j=JSON.parse(d);
    for (const a of (j.assets||[])) process.stdout.write(String(a.name||"")+"\n");
  } catch(e) {}
})'2>/dev/null | grep -E 'src[0-9a-f]+' || true
}

echo "== 那「recipe 指纹变了要重新固化」那一格 =="
echo "算据：scripts/toolchain/cache-key.sh"
echo "当前 HEAD：$(git -C "$ROOT"rev-parse HEAD 2>/dev/null | cut -c1-12)"
echo ""

rc_all=0
for p in "${PIECES[@]}"; do
  # 那**当前**的指纹逐字是那算据给的（那 tag 那一族逐字含那 src<12 位>）
  # 那**当前**的指纹逐字是那 `cache-key.sh asset` 那一族算的
  # （资产名逐字含那 src<12 位> 那一段、它逐字就是那指纹）。
  cur_asset="$(bash "$CACHE_KEY"asset "$p"2>/dev/null || true)"
  cur_src="$(printf '%s' "$cur_asset"| grep -o 'src[0-9a-f]*' | head -1)"
  if [ -z "$cur_src" ]; then
    echo "--- $p ---"
    echo "[缺] 那当前指纹逐字算不出来（按 C-05 报缺、不猜）"
    rc_all=1; continue
  fi
  echo "--- $p ---"
  echo "当前指纹：$cur_src"
  echo "当前资产名：${cur_asset:-(读不到)}"
  # 那**已发布**那一份逐字**以那 Release 上那一份为准**
  # （它逐字是那**真正**发布出去的那一份、它逐字**不是**那陈旧记录）
  # 那**先**问那 Release、那 pin 记录逐字**只**是那读不到时的一个**提示**
  # （那 pin 记录逐字是那**上一次**固化那一份的、它在「刚固化完」那一趟逐字**必然陈旧**）
  # 那**这一格逐字答的是**：那 Release 上逐字**有没有当前资产名那一份**
  # （是 Lead 逐字核过的那 A 列那一族）
  # 那**取法**逐字**按那当前资产名**逐字去 Release 上**找那一份**
  # （它逐字**不**取「第一个含 src 的」）
  # 那「第一个含 src 的」逐字答的是**别的那一问**：
  # · 那一列（B）逐字是「那 API 顺序里第一个含 src 的 asset」它逐字**不是**当前那一份
  # · 那一列（C）逐字是「最新 updated_at 那一份」它逐字答的是「最新发布的那一份」那一问
  # · 那一列（A）逐字是「当前那一份在不在」那**这一格**要答的逐字是它
  # 那**判据**（Lead 那一读）：
  # 「凡要答『那当前这一份逐字有没有被发布出去』，判据必须落在
  # 『那 Release 上逐字有没有那当前资产名』那一个事实上、
  # 而那它逐字不许取『第一个匹配的』、它逐字不许取『最新发布的』」
  published="$(publishedAssetNames "$p")"
  # 那**匹配**逐字是那**当前资产名**逐字**整条**比（那 %2B 与那 + 逐字是同一个）
  if printf '%s\n'"$published" | sed 's/%2B/+/g'| grep -Fxq "$(printf '%s'"$cur_asset" | sed 's/%2B/+/g')"; then
    echo "当前资产名在 Release 上：${cur_asset}"
    echo "这一格逐字**答的是**「那当前这一份逐字有没有被发布出去」、答案逐字是「有」"
    echo "[绿] 那当前资产名逐字**在** Release 上（它逐字**不需要**重新固化）"
    continue
  fi
  # 那 Release 逐字读不到时逐字**报缺**（按 C-05 不猜填）
  if [ -z "$published" ]; then
    echo "当前资产名在 Release 上：${cur_asset:-(算不出)}"
    echo "来源：Release 上那一份读不到（按 C-05 报缺、不猜）"
    if [ -z "${GITHUB_TOKEN:-}" ]; then
      echo "[缺] 那 Release 上那一份逐字读不到（那 token 逐字是空的）"
      echo "这一格逐字**只能**在 CI 里判（那 CI 那一族逐字有那 token）"
      rc_all=1; continue
    fi
    echo "[缺] 那 Release 上那一份逐字读不到（按 C-05 报缺、不猜）"
    echo "那 pin 记录逐字是那**上一次**固化那一份的、它逐字**不**当判据"
    rc_all=1; continue
  fi
  # 那 Release 上逐字**读到了**、当前资产名逐字**不在**里面
  # 它逐字是那**真**的红、它逐字是「那一格逐字报红、一族逐字其实逐字已经是新的」的**反面**
  echo "当前资产名：${cur_asset}"
  echo "Release 上逐字有的那几份："
  printf '%s\n'"$published" | sed 's/^/        /' | head -8
  echo "[红] 那当前资产名逐字**不**在 Release 上（这一件逐字**要**发布出去）"
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
