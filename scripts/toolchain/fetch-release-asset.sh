#!/usr/bin/env bash

set -uo pipefail

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

HERE="$(dirname "$0")"

HDR=()
[ -n "${GITHUB_TOKEN:-}" ] && HDR=(-H "Authorization: Bearer $GITHUB_TOKEN")

mkdir -p "$DEST"

gh_read_asset() {
  local want="$1"
  curl -fsSL --retry 3 --retry-delay 2 "${HDR[@]}" \
    -H "Accept: application/vnd.github+json" -H "User-Agent: lobos" \
    "https://api.github.com/repos/$REPO/releases/tags/$TAG" 2>/dev/null \
    | node -e '
        let d = "";
        process.stdin.on("data", c => d += c); process.stdin.on("end", () => {
          try {
            const j = JSON.parse(d);
            const a = (j.assets || []).find(x => x.name === process.argv[1]);
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

if [ -z "$URL" ] && [ "$PARTS_ONLY" != "1" ]; then
  echo "  [miss] Release $TAG 里没有 $ASSET"
  exit 1
fi

if [ "$PARTS_ONLY" = "1" ]; then
  _po_name="$ASSET.parts.txt"

  _po_json="$(gh_read_asset "$ASSET.parts.txt")"
  _po_url="$(printf '%s' "$_po_json" | node -e '
  let d = "";
  process.stdin.on("data", c => d += c); process.stdin.on("end", () => {
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
  :
elif [ -n "${EXPECT_BYTES:-}" ] && [ "$EXPECT_BYTES" -gt 0 ] 2>/dev/null; then
  if bash "$HERE/fetch-concurrent.sh" "$URL" "$DEST/$ASSET" "$EXPECT_BYTES"; then
    echo "  [fetch] 那 $ASSET 逐字**小片并发**地下来了（那 got 逐字 == expect 逐字 = $EXPECT_BYTES）"
  else

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

PARTS_TXT="$DEST/$ASSET.parts.txt"
if [ -s "$PARTS_TXT" ]; then
  echo "  [parts] 那 $ASSET 逐字**被拆**了、那**它**们逐字**是**那**几块**"

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
