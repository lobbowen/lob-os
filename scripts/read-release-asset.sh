#!/usr/bin/env bash
set -euo pipefail

LABEL="${1:-}"
TAG="${2:-}"
ASSET="${3:-}"
OUTDIR="${4:-}"
REPO="${GITHUB_REPOSITORY:-}"

unknown() {
  echo "::error title=读线上资产($LABEL)::$*" >&2
  exit 2
}
[ -n "$LABEL" ] && [ -n "$TAG" ] && [ -n "$ASSET" ] && [ -n "$OUTDIR" ] || {
  echo "[error] 用法: $0 <标签> <release tag> <资产名> <输出目录>" >&2
  exit 2
}
[ -n "$REPO" ] || unknown "环境里没有 GITHUB_REPOSITORY，gh 不知道去哪个仓取。"

mkdir -p "$OUTDIR"
ERR="$OUTDIR/.read-err"

source "$(dirname "$0")/gh-absence.sh"

if ! gh release view "$TAG" --repo "$REPO" >/dev/null 2>"$ERR"; then
  if gh_absent "$(cat "$ERR")"; then
    echo "[read:$LABEL] Release $TAG 不存在 —— 按首次发布处理"
    exit 10
  fi
  cat "$ERR" >&2 || true
  unknown "读 Release $TAG 失败，且原因不是「它不存在」—— 看不清线上状态就不许继续发布。"
fi

if gh release download "$TAG" -p "$ASSET" -O "$OUTDIR/$ASSET" --repo "$REPO" 2>"$ERR"; then
  echo "[read:$LABEL] 取到 $TAG/$ASSET ($(wc -c <"$OUTDIR/$ASSET") 字节)"
  exit 0
fi
if gh_absent "$(cat "$ERR")"; then
  echo "[read:$LABEL] Release $TAG 在，但没有资产 $ASSET —— 按首次发布处理"
  exit 10
fi
cat "$ERR" >&2 || true
unknown "Release $TAG 存在、取资产 $ASSET 却失败，且原因不是「资产不存在」—— 看不清线上状态就不许继续发布。"
