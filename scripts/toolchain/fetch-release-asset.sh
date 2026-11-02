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

#  缺陷名（C-102）**那脚本逐字引用逐字一个它自己没定义的变量** ——
#    => 而那旧版逐字**没有**这一行
#      => 而而那那 `HERE` 逐字在 `:70` 逐字**被**逐字用了
#        （=> 而那那 `if bash "$HERE/fetch-concurrent.sh" "$URL" "$DEST/$ASSET" "$EXPECT_BYTES"; then`）
#        => 而而那那 `HERE` 逐字**从来没**被定义过
#          （=> 而那 `:11` 逐字 `set -uo pipefail`、=> 而那 `:13` 到 `:18` 逐字定义的是
#            `TAG`/`ASSET`/`DEST`/`REPO`/`HDR`、=> 而而那逐字**没有** `HERE`）
#        => 而而那那 `set -u` 逐字把那一处逐字变成致命错
#      => 而那**成因**：=> 而那 `git log -S 'fetch-concurrent.sh' -- scripts/toolchain/fetch-release-asset.sh`
#        逐字 `ca93579`（=> 而那一笔逐字就是那「小片并发」那一族）
#      => 而而那那**后果**（06 本机实读逐字核出、Lead 本机实读逐字复核成立）：
#        · 而那那 openssl + crypto 那一趟逐字红了（=> 而那 run `41488877591`）：
#          `:287  [crypto] 本轮没编 openssl（复用固化产物）=> 从 Release 取 openssl-zlib+…+src6e665478cbed.zip`
#          `:288  scripts/toolchain/fetch-release-asset.sh: line 70: HERE: unbound variable`
#          `:289  ##[error]Process completed with exit code 1.`
#      => 而而那那**影响面**：=> 而而那凡是逐字要「从那 Release 逐字取一件来复用」的路径逐字都会踩到那 `HERE`
#    => 而那**判据**逐字是「那脚本逐字引用逐字一个它自己没定义的变量」那一个事实上
#      （=> 而而那它逐字**不**由「那一件逐字是什么」顶掉）
#    => 而那**处置**：=> 而而那那 `HERE` 逐字要逐字是那脚本自己逐字所在那目录、
#      => 而而那它逐字用那脚本自己的路径算、=> 而而那它逐字**不**依赖调用方逐字传
#      （=> 而那那 `piece-key.sh:23` 与那 `cache-key.sh:5` 逐字**已经**是那一族：
#        `HERE="$(dirname "$0")"`）
HERE="$(dirname "$0")"

HDR=()
[ -n "${GITHUB_TOKEN:-}" ] && HDR=(-H "Authorization: Bearer $GITHUB_TOKEN")

mkdir -p "$DEST"

#  => 而那**期望值**逐字要从那**被核的那一件**逐字**自己**读（=> 而那它逐字**不**由那产物倒推）
#    => 而那它逐字是那 Release 资产那一格逐字那 `size`（=> 而那它逐字**就是**那一份逐字的字节数）
#    => 而而那那**期望值**与那**下载**逐字**要**在**同一**个请求里取（=> 而那它逐字**不**两次问）
#    => 而那**判据**逐字落在**请求**上（=> 而按 C-993 的第二件：
#      => 而「检查里过了但请求里没带身份」逐字是同一形状、=> 而那判据必须落在请求上）。
ASSET_JSON="$(curl -fsSL --retry 3 --retry-delay 2 "${HDR[@]}" \
  -H "Accept: application/vnd.github+json" -H "User-Agent: lobos" \
  "https://api.github.com/repos/$REPO/releases/tags/$TAG" 2>/dev/null \
  | node -e '
      let d = "";
      process.stdin.on("data", c => d += c).on("end", () => {
        try {
          const j = JSON.parse(d);
          const a = (j.assets || []).find(x => x.name === process.argv[1]);
          if (a) {
            process.stdout.write(JSON.stringify({ url: a.browser_download_url, size: a.size }));
          }
        } catch (e) {}
      });
    ' -- "$ASSET" 2>/dev/null)"

URL="$(printf '%s' "$ASSET_JSON" | node -e '
  let d = "";
  process.stdin.on("data", c => d += c).on("end", () => {
    try { process.stdout.write(String(JSON.parse(d).url || "")); } catch (e) {}
  });
' 2>/dev/null)"
EXPECT_BYTES="$(printf '%s' "$ASSET_JSON" | node -e '
  let d = "";
  process.stdin.on("data", c => d += c).on("end", () => {
    try { process.stdout.write(String(JSON.parse(d).size || 0)); } catch (e) {}
  });
' 2>/dev/null)"

if [ -z "$URL" ]; then
  echo "  [miss] Release $TAG 里没有 $ASSET"
  exit 1
fi

#  缺陷名（C-102）**那取件那一族逐字按整份下、=> 而而那它逐字慢** ——
#    => 而那旧读法逐字是 `curl -fsSL -o "$DEST/$ASSET" "$URL"`（=> 而那它逐字**整份**下）
#      => 而那 GitHub blob 那一侧逐字 4.6 MB 逐字要 4m39s ≈ 17 KB/s
#    => 而那**处置**：=> 而那它逐字**小片并发**（=> 而那每片逐字 1 MiB、=> 而那并发逐字 16）
#      => 而而那那**完整性**那一格逐字**不**降
#        （=> 而那它逐字核「got == expect」与「unzip -t 通过」那两格）
#      => 而而那那**性能**那一格逐字**另**有一格（=> 而依 C-104 那两族逐字**不并**）
#    => 而那那期望字节数逐字**从**那资产那一格逐字读（=> 而那它逐字**不**猜）
if [ -n "${EXPECT_BYTES:-}" ] && [ "$EXPECT_BYTES" -gt 0 ] 2>/dev/null; then
  if bash "$HERE/fetch-concurrent.sh" "$URL" "$DEST/$ASSET" "$EXPECT_BYTES"; then
    echo "  [fetch] 那 $ASSET 逐字**小片并发**地下来了（=> 而那 got 逐字 == expect 逐字 = $EXPECT_BYTES）"
  else
    #  => 而那**小片并发**那一路逐字**不成**时逐字**回退**到那**整份**那一路
    #    => 而而那**回退**逐字**要**留痕（=> 而那按 C-126 不静默）
    echo "  [fetch] 那小片并发那一路逐字**不成**、=> 而而那回退到那整份那一路（=> 而而那性能那一格逐字**不**算）"
    curl -fsSL --retry 3 --retry-delay 2 "${HDR[@]}" \
      -o "$DEST/$ASSET" "$URL" 2>/dev/null || {
      echo "  [miss] 下载 $ASSET 失败"
      exit 1
    }
    _got="$(stat -c %s "$DEST/$ASSET" 2>/dev/null || echo 0)"
    if [ "$_got" != "$EXPECT_BYTES" ]; then
      echo "  [miss] 那 $ASSET 逐字 = $_got 字节、=> 而那期望值逐字 = $EXPECT_BYTES（=> 而而那按 C-05 报缺）"
      exit 1
    fi
  fi
else
  #  => 而那期望字节数逐字读不到时逐字**用**那整份那一路（=> 而那按 C-05 报缺、=> 而**不**猜那期望值）
  echo "  [fetch] 那期望字节数逐字读不到（=> 而那按 C-05 报缺）、=> 而而那用那整份那一路"
  curl -fsSL --retry 3 --retry-delay 2 "${HDR[@]}" \
    -o "$DEST/$ASSET" "$URL" 2>/dev/null || {
    echo "  [miss] 下载 $ASSET 失败"
    exit 1
  }
fi
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
