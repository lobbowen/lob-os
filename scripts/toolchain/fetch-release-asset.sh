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
exit 0
