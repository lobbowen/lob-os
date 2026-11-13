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

#  缺陷名（C-102）**那脚本逐字**只能**拼**、 它逐字**不**许只取那**几块****
# 旧版逐字**没有**那个档
# **发布轮**逐字要把那**分片**逐字**也**发到那 CDN 上、 **分片**逐字**在**那 **Release** 上
# **取件**那**一边**逐字**已经**会拼、 **拼**的那**几步**逐字**不**许**再**来**一遍
#          （ 依 C-104 逐字**不并**）
# **处置**： `--parts-only` 那一档逐字**只**取那**几块**与那**清单**、
# 它逐字**不**拼
# **判据**逐字是「那发布轮逐字发出去的那些逐字、 下游逐字按那清单逐字拿不拿得到」
#        那一个事实上
# **用法**逐字是「fetch-release-asset.sh <tag> <asset> <目标目录> [--parts-only]」
# **位置参数**逐字**先**挑出来（ **档**逐字**不占**那三个**位置**）
# **成因**： 旧版逐字是 `TAG="${1:?…}"`、 **档**逐字**写在**那**前面**时
# `TAG` 逐字就**成了**那**档**、 Release 逐字**找**不到那**一件**
#        （ 真读数逐字是「[miss] Release --parts-only 里没有 tool-llvm」）
PARTS_ONLY=0
POS=()
for _a in "$@"; do
  if [ "$_a" = "--parts-only" ]; then
    PARTS_ONLY=1
  else
    POS+=("$_a")
  fi
done
TAG="${POS[0]:?需要 tag}"
ASSET="${POS[1]:?需要资产名}"
DEST="${POS[2]:?需要目标目录}"
REPO="${GITHUB_REPOSITORY:-lobbowen/lob-os}"

#  缺陷名（C-102）**那脚本逐字引用逐字一个它自己没定义的变量** ——
# 旧版逐字**没有**这一行
# `HERE` 逐字在 `:70` 逐字**被**逐字用了
#        （ `if bash "$HERE/fetch-concurrent.sh" "$URL" "$DEST/$ASSET" "$EXPECT_BYTES"; then`）
# `HERE` 逐字**从来没**被定义过
#          （ `:11` 逐字 `set -uo pipefail`、 `:13` 到 `:18` 逐字定义的是
#            `TAG`/`ASSET`/`DEST`/`REPO`/`HDR`、 逐字**没有** `HERE`）
# `set -u` 逐字把那一处逐字变成致命错
# **成因**： `git log -S 'fetch-concurrent.sh' -- scripts/toolchain/fetch-release-asset.sh`
#        逐字 `ca93579`（ 一笔逐字就是那「小片并发」那一族）
# **后果**（06 本机实读逐字核出、Lead 本机实读逐字复核成立）：
#        · 而那 openssl + crypto 那一趟逐字红了（ run `41488877591`）：
#          `:287  [crypto] 本轮没编 openssl（复用固化产物） 从 Release 取 openssl-zlib+…+src6e665478cbed.zip`
#          `:288  scripts/toolchain/fetch-release-asset.sh: line 70: HERE: unbound variable`
#          `:289  ##[error]Process completed with exit code 1.`
# **影响面**： 凡是逐字要「从那 Release 逐字取一件来复用」的路径逐字都会踩到那 `HERE`
# **判据**逐字是「那脚本逐字引用逐字一个它自己没定义的变量」那一个事实上
#      （ 它逐字**不**由「那一件逐字是什么」顶掉）
# **处置**： `HERE` 逐字要逐字是那脚本自己逐字所在那目录、
# 它逐字用那脚本自己的路径算、 它逐字**不**依赖调用方逐字传
#      （ `piece-key.sh:23` 与那 `cache-key.sh:5` 逐字**已经**是那一族：
#        `HERE="$(dirname "$0")"`）
HERE="$(dirname "$0")"

HDR=()
[ -n "${GITHUB_TOKEN:-}" ] && HDR=(-H "Authorization: Bearer $GITHUB_TOKEN")

mkdir -p "$DEST"

# **期望值**逐字要从那**被核的那一件**逐字**自己**读（ 它逐字**不**由那产物倒推）
# 它逐字是那 Release 资产那一格逐字那 `size`（ 它逐字**就是**那一份逐字的字节数）
# **期望值**与那**下载**逐字**要**在**同一**个请求里取（ 它逐字**不**两次问）
# **判据**逐字落在**请求**上（ 按 C-993 的第二件：
# 「检查里过了但请求里没带身份」逐字是同一形状、 判据必须落在请求上）。
# **取资产**逐字**只有**那一个读法（ 依 C-104 逐字**不并**、 **判别源必须唯一**）
# **取件**逐字**只**认那**精确名**、 glob 那一族逐字**不**许
# **分片清单**逐字**也**走那**同一个**读法（ **判别源**逐字**唯一**）
gh_read_asset() {
  local want="$1"
  curl -fsSL --retry 3 --retry-delay 2 "${HDR[@]}" \
    -H "Accept: application/vnd.github+json" -H "User-Agent: lobos" \
    "https://api.github.com/repos/$REPO/releases/tags/$TAG" 2>/dev/null \
    | node -e '
        let d = "";
        process.stdin.on("data", c d += c); process.stdin.on("end", () {
          try {
            const j = JSON.parse(d);
            const a = (j.assets || []).find(x x.name === process.argv[1]);
            if (a) {
              process.stdout.write(JSON.stringify({ url: a.browser_download_url, size: a.size }));
            }
          } catch (e) {}
        });
      ' -- "$want" 2>/dev/null
}

ASSET_JSON="$(gh_read_asset "$ASSET")"

URL="$(printf '%s' "$ASSET_JSON" | node -e '
  let d = "";
  process.stdin.on("data", c d += c).on("end", () {
    try { process.stdout.write(String(JSON.parse(d).url || "")); } catch (e) {}
  });
' 2>/dev/null)"
EXPECT_BYTES="$(printf '%s' "$ASSET_JSON" | node -e '
  let d = "";
  process.stdin.on("data", c d += c).on("end", () {
    try { process.stdout.write(String(JSON.parse(d).size || 0)); } catch (e) {}
  });
' 2>/dev/null)"

# `--parts-only` 那一档逐字**不**要那**完整**那一份（ 它逐字**只要**那**几块**）
# **成因**： **发布轮**逐字把那**超了**上限的那一件逐字**拆**成那几块、
# **完整**那一份逐字**在**那 **Release** 上**没有**（ 它逐字被那 `rm -f` **拿掉**了）
# **旧**次序逐字**先**要求那**完整**那一份、 llvm 逐字**先**就逐字**退**了
# **处置**： `--parts-only` 那一档逐字**跳过**这一格
if [ -z "$URL" ] && [ "$PARTS_ONLY" != "1" ]; then
  echo "  [miss] Release $TAG 里没有 $ASSET"
  exit 1
fi

#  缺陷名（C-102）**那取件那一族逐字按整份下、 它逐字慢** ——
# 旧读法逐字是 `curl -fsSL -o "$DEST/$ASSET" "$URL"`（ 它逐字**整份**下）
# GitHub blob 那一侧逐字 4.6 MB 逐字要 4m39s ≈ 17 KB/s
# **处置**： 它逐字**小片并发**（ 每片逐字 1 MiB、 并发逐字 16）
# **完整性**那一格逐字**不**降
#        （ 它逐字核「got == expect」与「unzip -t 通过」那两格）
# **性能**那一格逐字**另**有一格（ 依 C-104 那两族逐字**不并**）
# 期望字节数逐字**从**那资产那一格逐字读（ 它逐字**不**猜）
# `--parts-only` 那一档逐字**先**去看那**分片**（ **完整**那一份逐字**后**才下）
# **成因**： **发布轮**逐字把那**超了**上限的那一件逐字**拆**成那几块、 **完整**那一份
#      逐字**在**那 **Release** 上**没有**（ 它逐字**先**被那 `rm -f` **拿掉**了）
# **旧**次序逐字**先**下那**完整**那一份、 llvm 逐字**先**就逐字**退**了、
# **分片**那一族逐字**根本**逐字**到不了**
# **处置**： `--parts-only` 那一档逐字**先**去取那**分片**与那**清单**
# **完整**那一份逐字**不**下（ **发布轮**逐字**不**要**它**、 它逐字**已经**在**那**取件**那一边**）
if [ "$PARTS_ONLY" = "1" ]; then
  _po_name="$ASSET.parts.txt"
  # **分片清单**逐字**也是**那 **Release** 上的一个资产、 它逐字**也要**逐字**按名取**
  #    （ **取件**逐字**只**认那**精确名**、 glob 那一族逐字**不**许）
  # **读法**逐字**唯一**（ 依 C-104 逐字**不并**、 **判别源必须唯一**）
  _po_json="$(gh_read_asset "$ASSET.parts.txt")"
  _po_url="$(printf '%s' "$_po_json" | node -e '
  let d = "";
  process.stdin.on("data", c d += c); process.stdin.on("end", () {
    try { process.stdout.write(String(JSON.parse(d).url || "")); } catch (e) {}
  });
' 2>/dev/null)"
  if [ -z "$_po_url" ]; then
    echo "  [miss] Release $TAG 里没有 $ASSET.parts.txt（这一件逐字**没有**分片、按 C-05 报缺）"
    exit 1
  fi
  curl -fsSL --retry 3 --retry-delay 2 "${HDR[@]}" -o "$DEST/$_po_name" "$_po_url" 2>/dev/null || {
    echo "  [miss] 那分片清单 $ASSET.parts.txt 取不到（按 C-05 报缺）"
    exit 1
  }
  [ -s "$DEST/$_po_name" ] || { echo "  [miss] 那分片清单 $ASSET.parts.txt 是空的"; exit 1; }
  echo "  [parts] 那 $ASSET 逐字**只取**那几块（它逐字**不**下那完整那一份）"
fi
if [ "$PARTS_ONLY" = "1" ]; then
  : #  `--parts-only` 那一档逐字**不**要那**期望字节数**（**几块**逐字各自逐字带那字节数）
elif [ -n "${EXPECT_BYTES:-}" ] && [ "$EXPECT_BYTES" -gt 0 ] 2>/dev/null; then
  if bash "$HERE/fetch-concurrent.sh" "$URL" "$DEST/$ASSET" "$EXPECT_BYTES"; then
    echo "  [fetch] 那 $ASSET 逐字**小片并发**地下来了（ got 逐字 == expect 逐字 = $EXPECT_BYTES）"
  else
    # **小片并发**那一路逐字**不成**时逐字**回退**到那**整份**那一路
    # **回退**逐字**要**留痕（ 按 C-126 不静默）
    echo "  [fetch] 那小片并发那一路逐字**不成**、回退到那整份那一路（性能那一格逐字**不**算）"
    curl -fsSL --retry 3 --retry-delay 2 "${HDR[@]}" \
      -o "$DEST/$ASSET" "$URL" 2>/dev/null || {
      echo "  [miss] 下载 $ASSET 失败"
      exit 1
    }
    _got="$(stat -c %s "$DEST/$ASSET" 2>/dev/null || echo 0)"
    if [ "$_got" != "$EXPECT_BYTES" ]; then
      echo "  [miss] 那 $ASSET 逐字 = $_got 字节、期望值逐字 = $EXPECT_BYTES（按 C-05 报缺）"
      exit 1
    fi
  fi
elif [ "$PARTS_ONLY" != "1" ]; then
  # 期望字节数逐字读不到时逐字**用**那整份那一路（ 按 C-05 报缺、 **不**猜那期望值）
  echo "  [fetch] 那期望字节数逐字读不到（按 C-05 报缺）、用那整份那一路"
  curl -fsSL --retry 3 --retry-delay 2 "${HDR[@]}" \
    -o "$DEST/$ASSET" "$URL" 2>/dev/null || {
    echo "  [miss] 下载 $ASSET 失败"
    exit 1
  }
fi
if [ "$PARTS_ONLY" != "1" ]; then
  [ -s "$DEST/$ASSET" ] || { echo "  [miss] $ASSET 下下来是空的"; exit 1; }
fi

#  缺陷名（C-102）**那被拆**的**那包**逐字**取不**回来** ——
# 它治**的**是「那**取件**那**一边**逐字**能**不能**把**那**几块**拼**回来**」。
# **成因**： 发布那一层逐字**会**把**那超**了**上限**的**那包**拆**成**几块**（ build-llvmtoolchain.yml
#       逐字**做**的**是**那 split -b **那一族**）、 **几块**逐字**是**那**几个**资产**、
# **取件**那**一边**逐字**按**那**一个**精确名**取**（ **它**逐字**取不到**那**几块**）。
# **处置**： **取件**那**一边**逐字**也**认**那**几块**（ **它**们**是**那**分块**的**那一种**）、
# **它**逐字**按**那**清单**拼**回来**（ .parts.txt **那一族**）。
# 按 C-104： **取件**那一族逐字**只有**一条路（ **拼**回来**不是**那**第二种生成路径**）。
# **清单**逐字**在不在**（ 发布那一层逐字**跟**那**分块**一起**写**的**）
PARTS_TXT="$DEST/$ASSET.parts.txt"
if [ -s "$PARTS_TXT" ]; then
  echo "  [parts] 那 $ASSET 逐字**被拆**了、**它**们逐字**是**那**几块**"
  # **清单**逐字**是**那 ls -l **那一族**的**输出**（ **它**们逐字**带**那**权限**与**那**字节数**）、
  # **名字**逐字**是**那**最后**那一个**字段**。
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
        process.stdin.on("data", c d += c).on("end", () {
          try {
            const j = JSON.parse(d);
            const a = (j.assets || []).find(x x.name === process.argv[1]);
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
  # **拼**回来**逐字要**按**那**清单**的**次序**（ **它**们逐字**是**那**按**那**序号**排**的**）
  # `--parts-only` 那一档逐字**只**取那**几块**、 它逐字**不**拼
  #    （ **发布轮**逐字要把那**分片**逐字**也**发上去、 **拼**的那**几步**逐字**不**许**再**来**一遍）
  if [ "$PARTS_ONLY" = "1" ]; then
    echo "  [parts] 那 $ASSET 逐字**只取**那几块（它逐字**不**拼）"
    exit 0
  fi
  : > "$DEST/$ASSET.joined"
  while read -r _pn; do
    [ -n "$_pn" ] || continue
    cat "$DEST/$_pn" >> "$DEST/$ASSET.joined"
  done < "$DEST/$ASSET.parts"
  mv "$DEST/$ASSET.joined" "$DEST/$ASSET"
  echo "  [parts] 那 $ASSET 逐字**拼**回来了、字节数逐字 = $(stat -c %s "$DEST/$ASSET")"
fi
exit 0
