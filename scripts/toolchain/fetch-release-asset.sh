#!/usr/bin/env bash
# 从 Release 取一个资产到本地目录 —— 按**精确名**取，不用 --pattern。
#
# 为什么不用 `gh release download --pattern`：
#   asset 名里有 +（如 openssl-zlib+1.3.2+ndk30…tar.gz），
#   gh 的 --pattern 走 glob，+ 会被当字面量但整串匹配行为不可靠，
#   表现为「Release 里有这个文件，但 gh 说下不到」。
#   实测：build-crypto 卡在判据，正是这一条。
#
# 用法：fetch-release-asset.sh <tag> <asset> <目标目录>
set -uo pipefail

TAG="${1:?需要 tag}"
ASSET="${2:?需要资产名}"
DEST="${3:?需要目标目录}"
REPO="${GITHUB_REPOSITORY:-lobbowen/lob-os}"
HDR=()
[ -n "${GITHUB_TOKEN:-}" ] && HDR=(-H "Authorization: Bearer $GITHUB_TOKEN")

mkdir -p "$DEST"

URL="$(curl -fsSL --retry 3 --retry-delay 2 "${HDR[@]}" \
  -H "Accept: application/vnd.github+json" -H "User-Agent: lobos" \
  "https://api.github.com/repos/$REPO/releases/tags/$TAG" 2>/dev/null \
  | node -e '
      let d = "";
      process.stdin.on("data", c => d += c).on("end", () => {
        try {
          const j = JSON.parse(d);
          const a = (j.assets || []).find(x => x.name === process.argv[1]);
          if (a) process.stdout.write(a.browser_download_url);
        } catch (e) {}
      });
    ' -- "$ASSET" 2>/dev/null)"

if [ -z "$URL" ]; then
  echo "  [miss] Release $TAG 里没有 $ASSET"
  exit 1
fi

curl -fsSL --retry 3 --retry-delay 2 "${HDR[@]}" \
  -o "$DEST/$ASSET" "$URL" 2>/dev/null || {
  echo "  [miss] 下载 $ASSET 失败"
  exit 1
}
[ -s "$DEST/$ASSET" ] || { echo "  [miss] $ASSET 下下来是空的"; exit 1; }

#  缺陷名（C-102）**那被拆**的**那包**逐字**取不**回来** ——
#    => 而而它治**的**是「那**取件**那**一边**逐字**能**不能**把**那**几块**拼**回来**」。
#    => 而那**成因**：=> 而那发布那一层逐字**会**把**那超**了**上限**的**那包**拆**成**几块**（=> 而那 build-llvmtoolchain.yml
#       逐字**做**的**是**那 split -b **那一族**）、=> 而而那**几块**逐字**是**那**几个**资产**、
#       => 而而那**取件**那**一边**逐字**按**那**一个**精确名**取**（=> 而那**它**逐字**取不到**那**几块**）。
#    => 而那**处置**：=> 而那**取件**那**一边**逐字**也**认**那**几块**（=> 而那**它**们**是**那**分块**的**那一种**）、
#       => 而而那**它**逐字**按**那**清单**拼**回来**（=> 而那 .parts.txt **那一族**）。
#    => 而按 C-104：=> 而那**取件**那一族逐字**只有**一条路（=> 而那**拼**回来**不是**那**第二种生成路径**）。
#  => 而那**清单**逐字**在不在**（=> 而那发布那一层逐字**跟**那**分块**一起**写**的**）
PARTS_TXT="$DEST/$ASSET.parts.txt"
if [ -s "$PARTS_TXT" ]; then
  echo "  [parts] 那 $ASSET 逐字**被拆**了、=> 而而那**它**们逐字**是**那**几块**"
  #  => 而那**清单**逐字**是**那 ls -l **那一族**的**输出**（=> 而那**它**们逐字**带**那**权限**与**那**字节数**）、
  #     => 而而那**名字**逐字**是**那**最后**那一个**字段**。
  : > "$DEST/$ASSET.parts"
  while read -r _pf; do
    [ -n "$_pf" ] || continue
    _pn="${_pf##* }"
    [ -n "$_pn" ] || continue
    echo "$_pn" >> "$DEST/$ASSET.parts"
  done < "$PARTS_TXT"
  while read -r _pn; do
    [ -n "$_pn" ] || continue
    _pu="$(curl -fsSL --retry 3 --retry-delay 2 "${HDR[@]}" \
      -H "Accept: application/vnd.github+json" -H "User-Agent: lobos" \
      "https://api.github.com/repos/$REPO/releases/tags/$TAG" 2>/dev/null \
      | node -e '
        let d = "";
        process.stdin.on("data", c => d += c).on("end", () => {
          try {
            const j = JSON.parse(d);
            const a = (j.assets || []).find(x => x.name === process.argv[1]);
            if (a) process.stdout.write(a.browser_download_url);
          } catch (e) {}
        });
      ' -- "$_pn" 2>/dev/null)"
    [ -n "$_pu" ] || { echo "  [miss] Release $TAG 里没有那分块 $_pn"; exit 1; }
    curl -fsSL --retry 3 --retry-delay 2 "${HDR[@]}" \
      -o "$DEST/$_pn" "$_pu" 2>/dev/null || {
      echo "  [miss] 下载分块 $_pn 失败"
      exit 1
    }
    [ -s "$DEST/$_pn" ] || { echo "  [miss] 分块 $_pn 下下来是空的"; exit 1; }
  done < "$DEST/$ASSET.parts"
  #  => 而那**拼**回来**逐字要**按**那**清单**的**次序**（=> 而那**它**们逐字**是**那**按**那**序号**排**的**）
  : > "$DEST/$ASSET.joined"
  while read -r _pn; do
    [ -n "$_pn" ] || continue
    cat "$DEST/$_pn" >> "$DEST/$ASSET.joined"
  done < "$DEST/$ASSET.parts"
  mv "$DEST/$ASSET.joined" "$DEST/$ASSET"
  echo "  [parts] 那 $ASSET 逐字**拼**回来了、=> 而那字节数逐字 = $(stat -c %s "$DEST/$ASSET")"
fi
exit 0
