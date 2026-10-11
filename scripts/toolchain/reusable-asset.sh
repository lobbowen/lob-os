#!/usr/bin/env bash
# 判「这个固化产物能不能直接用」——**必须验内容，不能只判存在**。
#
# 为什么：
#   只判 `gh release view <tag>` 成功 ⇒ 命中。资产名在改版前后可能不变
#   （复用判据给的 asset 名只含 id/deps/ndk/api，不含格式版本），
#   于是旧格式的包会被当成新的继续用 —— CI 报 success，包里却没有
#   component-meta.json，装配器读不了，整条链静默地空转。
#
#   同类错误还有过两次：Mirror 判「写没写」（truncate 覆盖看不出来）、
#   verify-apk-native 与内核用同一套错误判据（两边一起错，查不出）。
#   根子同一个：判据只验形式、不验内容。
#
# 用法：
#   reusable-asset.sh <tag> <asset 名>        命中则 exit 0，否则 exit 1
#   reusable-asset.sh --probe-dir <目录> <asset 名>   对已下载到本地的包验
set -uo pipefail

PROBE_DIR=""
if [ "${1:-}" = "--probe-dir" ]; then
  PROBE_DIR="$2"; ASSET="$3"
else
  TAG="${1:?需要 tag}"; ASSET="${2:?需要资产名}"
fi

# 标准件包必须有 component-meta.json —— 装配器只读包里的那份（照抄 .deb 的 control）
REQUIRED="component-meta.json"

verify_local() {
  local f="$1"
  [ -s "$f" ] || return 1
  case "$f" in
    *.tar.gz|*.tgz) tar -tzf "$f" 2>/dev/null | grep -qE "(^|/)$REQUIRED$" || return 1 ;;
    *.zip)          unzip -l "$f" 2>/dev/null | grep -q "$REQUIRED" || return 1 ;;
    *)              grep -q "$REQUIRED" "$f" 2>/dev/null || return 1 ;;
  esac
  return 0
}

if [ -n "$PROBE_DIR" ]; then
  verify_local "$PROBE_DIR/$ASSET"
  exit $?
fi

command -v gh >/dev/null 2>&1 || { echo "::error title=缺 gh::判重用资产需要 gh" >&2; exit 2; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

if ! gh release download "$TAG" --repo "${GITHUB_REPOSITORY:-lobbowen/lob-os}" \
     --pattern "$ASSET" --dir "$TMP" >/dev/null 2>&1; then
  echo "  [miss] Release $TAG 里没有 $ASSET"
  exit 1
fi

if ! verify_local "$TMP/$ASSET"; then
  echo "  [miss] $ASSET 在，但包里没有 $REQUIRED —— 是旧格式，需要重新固化"
  exit 1
fi

echo "  [ok] $ASSET 可复用（含 $REQUIRED）"
exit 0
