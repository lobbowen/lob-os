#!/usr/bin/env bash
# 恢复 work/ 中被清理的可再生源码解压树。
# 这些源码树由 tarball 完整保留，清理解压树是为了节省空间；构建前跑本脚本重建。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$ROOT/work"

restore() {
  local tb="$1" dir="$2" strip="$3"
  if [ -d "$WORK/$dir" ]; then echo "  skip $dir (already present)"; return; fi
  if [ ! -f "$WORK/$tb" ]; then echo "  MISSING tarball $tb" >&2; return 1; fi
  mkdir -p "$WORK/$dir"
  tar xf "$WORK/$tb" -C "$WORK/$dir" --strip-components="$strip"
  echo "  restored $dir from $tb"
}

echo "== 恢复可再生的源码解压树 =="
restore git.tar.xz   git-src 1
restore openssl.tar.gz openssl 1
restore curl.tar.gz   curl    1
restore zlib.tar.gz   zlib    1
echo "== 完成 =="
