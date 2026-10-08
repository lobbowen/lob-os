#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/gh-absence.sh"

usage() { echo "[error] 用法: bash scripts/package/gh-release-upload.sh <release tag> [--title T] [--notes N|--notes-file F] [--prune 正则] [--keep 名称]… [--skip-existing] <文件>…"; }
optval() { [ $# -ge 2 ] || { echo "[error] $1 缺值"; usage; exit 2; }; }

TAG=""
TITLE=""
NOTES=""
NOTES_FILE=""
PRUNE=""
SKIP_EXISTING=0
declare -a KEEPS=()
declare -a FILES=()

while [ $# -gt 0 ]; do
  case "$1" in
    --title)      optval "$@"; TITLE="$2"; shift 2 ;;
    --notes)      optval "$@"; NOTES="$2"; shift 2 ;;
    --notes-file) optval "$@"; NOTES_FILE="$2"; shift 2 ;;
    --prune)      optval "$@"; PRUNE="$2"; shift 2 ;;
    --keep)       optval "$@"; KEEPS+=("$2"); shift 2 ;;
    --skip-existing) SKIP_EXISTING=1; shift ;;
    -*)           echo "[error] 不认识的选项：$1"; usage; exit 2 ;;
    *)            if [ -z "$TAG" ]; then TAG="$1"; else FILES+=("$1"); fi; shift ;;
  esac
done

if [ -z "$TAG" ] || [ "${#FILES[@]}" -eq 0 ]; then
  echo "[error] 参数不齐：需要 <release tag> 和至少一个文件（读到 tag='${TAG:-空}'、文件 ${#FILES[@]} 个）。"
  usage
  exit 2
fi
[ -z "$NOTES" ] || [ -z "$NOTES_FILE" ] || { echo "[error] --notes 与 --notes-file 只能给一个。"; exit 2; }
REPO="${GITHUB_REPOSITORY:-}"
[ -n "$REPO" ] || { echo "::error title=仓库未知::环境里没有 GITHUB_REPOSITORY，gh 不知道该往哪个仓发布 —— 不许猜。"; exit 1; }

for f in "${FILES[@]}"; do
  [ -f "$f" ] || { echo "[error] 要发布的文件不存在或不是普通文件：$f"; exit 2; }
  [ -s "$f" ] || { echo "[error] 要发布的文件是 0 字节：$f —— 空产物投出去等于把线上资产换成没有。"; exit 2; }
done

CREATE_NEEDED=0
if ! VIEW_ERR="$(gh release view "$TAG" --repo "$REPO" 2>&1 >/dev/null)"; then
  if gh_absent "$VIEW_ERR"; then
    echo "[gh-release-upload] Release $TAG 不存在，创建中…"
    CREATE_NEEDED=1
  else
    echo "::error title=读 Release 失败::看不清 $TAG 的线上状态就不许继续发布（原因：$VIEW_ERR）"
    exit 1
  fi
elif [ "$SKIP_EXISTING" = 1 ]; then
  echo "[gh-release-upload] Release $TAG 已存在 —— --skip-existing：归档只建一次，本次不动它。"
  exit 0
fi
if [ "$CREATE_NEEDED" = 1 ]; then
  CREATE_ARGS=(gh release create "$TAG" --repo "$REPO" --title "${TITLE:-$TAG}")
  if [ -n "$NOTES_FILE" ]; then
    [ -s "$NOTES_FILE" ] || { echo "[error] --notes-file 指向的文件不存在或为空：$NOTES_FILE"; exit 2; }
    CREATE_ARGS+=(--notes-file "$NOTES_FILE")
  else
    CREATE_ARGS+=(--notes "${NOTES:-由 scripts/package/gh-release-upload.sh 发布。}")
  fi
  "${CREATE_ARGS[@]}" >/dev/null || { echo "::error title=创建 Release 失败::$TAG 建不出来 —— 本次发布没发生。"; exit 1; }
fi

assets_tsv() {
  gh release view "$TAG" --repo "$REPO" --json assets \
    --jq '.assets[] | "\(.name)\t\(.size)"' 2>/dev/null || true
}
asset_size() {
  local fld sz name="$1"
  while IFS=$'\t' read -r fld sz; do
    [ -n "$fld" ] || continue
    [ "$fld" = "$name" ] && { printf '%s\n' "${sz:-}"; return 0; }
  done <<<"$TSV"
  return 1
}
delete_asset_by_name() { # <名称> —— 只按清单里真有的 id 删
  local id nm="$1" ids
  ids="$(gh api "repos/$REPO/releases/tags/$TAG" --jq ".assets[] | select(.name==\"$nm\") | .id" 2>/dev/null || true)"
  [ -n "$ids" ] || return 0
  while IFS= read -r id; do
    [ -n "$id" ] || continue
    gh api -X DELETE "repos/$REPO/releases/assets/$id" >/dev/null || {
      echo "::error title=删旧资产失败::$TAG 上的 $nm (id=$id) 删不掉，覆盖上传不能继续。"; return 1; }
  done <<<"$ids"
}

# ── 3. 逐文件覆盖上传 ────────────────────────────────────────────────────
declare -a NAMES=()
declare -a SIZES=()
for f in "${FILES[@]}"; do
  NAME="${f##*/}"
  SIZE="$(stat -c %s "$f" 2>/dev/null || true)"
  [ -n "$SIZE" ] || { echo "::error title=读不出文件大小::$f —— 无法事后确认。"; exit 1; }
  if UP_ERR="$(gh release upload "$TAG" "$f" --repo "$REPO" --clobber 2>&1 >/dev/null)"; then
    echo "[gh-release-upload] clobber $NAME（$SIZE 字节）"
  else
    # 同名资产记录还在、后端对象却孤立/损坏时 --clobber 会 404（2026-09-23 实证）。
    # 但「先删后传」会把线上唯一的那份资产删没了才失败，所以只对 404 这个特征做回退：
    # 网络抖动/5xx 时旧资产还在、还能下载，此时退 1 让它保持原样，不许动手。
    LOW="${UP_ERR,,}"
    case "$LOW" in
      *404*|*"not found"*)
        echo "[gh-release-upload] clobber $NAME 失败（${UP_ERR//$'\n'/ }），回退为「按名字删旧记录后重传」" ;;
      *)
        echo "::error title=覆盖上传失败::$TAG 上 $NAME 覆盖失败，且失败特征不是孤立资产（${UP_ERR//$'\n'/ }）—— 线上现有资产没被动过，本次发布没发生。"
        exit 1 ;;
    esac
    delete_asset_by_name "$NAME" || exit 1
    gh release upload "$TAG" "$f" --repo "$REPO" >/dev/null 2>&1 || {
      echo "::error title=上传失败::$TAG 上 $NAME 既覆盖不了也新建不了 —— 本次发布没发生。"; exit 1; }
  fi
  NAMES+=("$NAME")
  SIZES+=("$SIZE")
  KEEPS+=("$NAME")
done

# ── 4. 滚动通道清理（--prune 给了才做）──────────────────────────────────
if [ -n "$PRUNE" ]; then
  TSV="$(assets_tsv)"
  while IFS=$'\t' read -r nm sz; do
    [ -n "$nm" ] || continue
    [[ "$nm" =~ $PRUNE ]] || continue
    keep=0
    for k in "${KEEPS[@]}"; do [ "$nm" = "$k" ] && { keep=1; break; }; done
    [ "$keep" = 1 ] && continue
    echo "[gh-release-upload] 清理旧资产 $nm"
    delete_asset_by_name "$nm" || exit 1
  done <<<"$TSV"
fi

# ── 5. 事后确认：上传命令退 0 不等于线上真有了 ───────────────────────────
TSV="$(assets_tsv)"
BAD=""
for i in "${!NAMES[@]}"; do
  CUR="$(asset_size "${NAMES[$i]}" || true)"
  if [ -z "$CUR" ]; then
    BAD="$BAD ${NAMES[$i]}(缺失)"
  elif [ "$CUR" != "${SIZES[$i]}" ]; then
    BAD="$BAD ${NAMES[$i]}(线上 $CUR ≠ 本地 ${SIZES[$i]})"
  fi
done
[ -z "$BAD" ] || { echo "::error title=发布后确认不过::$TAG 上这些资产没对上：${BAD# }—— 别把这一行当发布成功。"; exit 1; }
echo "[gh-release-upload] [ok] $TAG 已就位："
for i in "${!NAMES[@]}"; do printf '  %s  %s 字节\n' "${NAMES[$i]}" "${SIZES[$i]}"; done
