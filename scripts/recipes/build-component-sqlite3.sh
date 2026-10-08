#!/usr/bin/env bash
set -euo pipefail

HERE=$(dirname "$0")
cd "$HERE/../.."
ROOT_DIR=$(pwd)

if [ -z "${CC:-}" ]; then
  echo "::error title=缺 CC::需要 CC（NDK 的 clang（CI 里 locate-ndk.sh 注入，形如 …/bin/aarch64-linux-android35-clang））"
  exit 1
fi

OUT="${OUT:-dist}"
mkdir -p "$OUT/bin" work

echo "[sqlite3] 取源码（钉值表的 sqlite3 那一格）"
bash "$ROOT_DIR/scripts/toolchain/fetch-pinned.sh" --pin sqlite3 "$ROOT_DIR/work/sqlite.zip" \
  --version-file "$ROOT_DIR/$OUT/sqlite3.version"
echo "[sqlite3] 源码包 $(stat -c%s work/sqlite.zip) 字节"
rm -rf work/sqlite
mkdir -p work/sqlite
unzip -q work/sqlite.zip -d work/sqlite
SRC=$(find work/sqlite -maxdepth 1 -type d -name 'sqlite-amalgamation-*' | head -n 1 || true)
if [ -z "$SRC" ]; then
  echo "::error title=解包异常::解包后没有 sqlite-amalgamation-* 目录"
  find work/sqlite -maxdepth 2 | head -n 10 || true
  exit 1
fi
echo "[sqlite3] 源码树 $SRC"

"$CC" -O2 -DNDEBUG -DSQLITE_THREADSAFE=1 -DSQLITE_ENABLE_FTS5 -DSQLITE_ENABLE_JSON1 \
  -o "$OUT/bin/sqlite3" "$SRC/shell.c" "$SRC/sqlite3.c" -lm -ldl

SIZE=$(stat -c%s "$OUT/bin/sqlite3")
echo "[sqlite3] 产出 $OUT/bin/sqlite3（$SIZE 字节）"
