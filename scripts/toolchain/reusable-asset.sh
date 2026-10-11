#!/usr/bin/env bash
# 判「这个固化产物能不能直接用」—— 验内容，不只验存在。
#
# 验三件事，缺一不可：
#   1. 包里有 component-meta.json    装配器只读包里的那份（照抄 .deb 的 control）
#   2. 那份 meta 里的 entry 与本件该有的 entry 一致
#      —— 否则「包里说有 A、实际装的是 B」，装配器装完才发现，落位已被污染
#   3. 包里真有 entry 指的那个路径（文件或软链都算）
#      —— 共享库整条链里 libz.so 与 libz.so.1 都是软链，只认常规文件会漏
#
# 为什么必须验到这一层：已经踩过三次。
#   · 只判 gh release view 成功      → 旧格式包被当新的用（CI 报 success，包是空的）
#   · 只判包里有没有 meta           → entry 错配的包照样命中（openssl 的包里装的是 crypto）
#   · 只按文件名列表找                → 软链形式的 libz.so 被当成「不在包里」
# 根子同一个：判据只验形式、不验内容。
#
# 用法：
#   reusable-asset.sh <tag> <asset> [<entry>]
#   reusable-asset.sh --probe-dir <目录> <asset> <entry>
set -uo pipefail

PROBE_DIR=""
if [ "${1:-}" = "--probe-dir" ]; then
  PROBE_DIR="$2"; ASSET="$3"; WANT_ENTRY="${4:-}"
else
  TAG="${1:?需要 tag}"; ASSET="${2:?需要资产名}"; WANT_ENTRY="${3:-}"
fi

read_meta_entry() {
  tar -xzf "$1" -O ./component-meta.json 2>/dev/null \
    || unzip -p "$1" '*/component-meta.json' 2>/dev/null | head -1
}

verify_local() {
  local f="$1" listing vl
  [ -s "$f" ] || return 1

  case "$f" in
    *.tar.gz|*.tgz) listing="$(tar -tzf "$f" 2>/dev/null)" ;;
    *.zip)          listing="$(unzip -Z1 "$f" 2>/dev/null)" ;;
    *)              listing="$(grep -ao 'component-meta\.json' "$f" 2>/dev/null)" ;;
  esac
  [ -n "$listing" ] || return 1

  # 1. 有 component-meta.json
  printf '%s\n' "$listing" | grep -qE '(^|/)component-meta\.json$' || return 1

  [ -n "$WANT_ENTRY" ] || return 0

  # 2. meta 里的 entry 与期望一致
  local entry
  entry="$(read_meta_entry "$f" | node -e '
    let d = "";
    process.stdin.on("data", c => d += c).on("end", () => {
      try { process.stdout.write((JSON.parse(d).entry) || ""); } catch (e) {}
    });
  ' 2>/dev/null)"
  [ "$entry" = "$WANT_ENTRY" ] || return 1

  # 3. 包里真有那个路径（文件或软链都算）
  #    tar -tzvf 的路径恒在第 6 字段；软链行末是链接目标不是路径，不能用 $NF。
  #    首字符区分类型：'-' 常规文件 / 'l' 软链。
  vl="$(tar -tzvf "$f" 2>/dev/null | awk 'NF>=6 {t=$1; p=$6; sub(/^\.\//,"",p); print t" "p}')"
  if [ -n "$vl" ]; then
    printf '%s\n' "$vl" | awk -v w="$WANT_ENTRY" '
      { t=$1; p=$2;
        if ((substr(t,1,1)=="-" || substr(t,1,1)=="l") && p==w) { print "hit"; exit } }
    ' | grep -q hit && return 0
  fi
  printf '%s\n' "$listing" | sed 's#^\./##' | grep -qx "$WANT_ENTRY" && return 0
  return 1
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
  echo "  [miss] $ASSET 在，但内容不对（缺 component-meta.json / entry 不符 / 缺那个路径）—— 需要重新固化"
  exit 1
fi

echo "  [ok] $ASSET 可复用"
exit 0
