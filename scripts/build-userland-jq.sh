#!/usr/bin/env bash
set -euo pipefail

HERE=$(dirname "$0")
cd "$HERE/.."
ROOT_DIR=$(pwd)

if [ -z "${CC:-}" ]; then
  echo "::error title=缺 CC::需要 CC（aarch64-linux-android21-clang）"
  exit 1
fi

OUT="${OUT:-dist}"
mkdir -p "$ROOT_DIR/$OUT/bin" work
TC=$(dirname "$CC")
AR_BIN="$TC/llvm-ar"
RANLIB_BIN="$TC/llvm-ranlib"
READELF_BIN="$TC/llvm-readelf"
for f in "$AR_BIN" "$RANLIB_BIN" "$READELF_BIN"; do
  [ -x "$f" ] || { echo "::error title=缺工具::$f 不存在"; exit 1; }
done

echo "[jq] 取源码（钉值表的 jq 那一格）"
bash "$ROOT_DIR/scripts/fetch-pinned.sh" --pin jq "$ROOT_DIR/work/jq.tar.gz" \
  --version-file "$ROOT_DIR/$OUT/jq.version"
echo "[jq] 源码包 $(stat -c%s work/jq.tar.gz) 字节，逐字节等于仓内钉值"

rm -rf work/jq && mkdir -p work/jq
tar xzf work/jq.tar.gz -C work/jq --strip-components=1
[ -f work/jq/configure ] || { echo "::error title=发布包里没有 configure::上游 release 包应自带 configure"; exit 1; }
if [ ! -d work/jq/vendor/oniguruma ]; then
  echo "::error title=发布包里没有 vendored oniguruma::需要 vendor/oniguruma（--with-oniguruma=builtin 的前提）"
  ls work/jq/vendor 2>/dev/null || true
  ls work/jq/modules 2>/dev/null || true
  exit 1
fi
echo "[jq] 源码树就位（含 vendored oniguruma）"

cd work/jq
export CC AR="$AR_BIN" RANLIB="$RANLIB_BIN" CFLAGS="-O2 -DNDEBUG"
CONFIGURE_COMMON="--host=aarch64-linux-android --build=x86_64-pc-linux-gnu"

ONIG_PREFIX="$ROOT_DIR/work/onig"
echo "[jq] 静态编 vendored oniguruma → $ONIG_PREFIX"
cd "$ROOT_DIR/work/jq/vendor/oniguruma"
if [ ! -x ./configure ]; then
  echo "[jq] vendor/oniguruma 没有 configure，用 autoreconf 生成"
  if ! autoreconf -i >/dev/null 2>&1; then
    echo "::error title=autoreconf 失败::vendor/oniguruma 需要 autoconf/automake/libtool"
    exit 1
  fi
fi
./configure $CONFIGURE_COMMON --prefix="$ONIG_PREFIX" --disable-shared --enable-static \
  --disable-dependency-tracking CC="$CC" AR="$AR_BIN" RANLIB="$RANLIB_BIN" >/dev/null
if ! make -j2 >/dev/null; then
  echo "::error title=oniguruma 编译失败::静态编不过，jq 就会带 .so 出门，不可接受"
  exit 1
fi
make install >/dev/null
ONIG_LIBS=$(ls "$ONIG_PREFIX/lib" | tr '\n' ' ' || true)
echo "[jq] oniguruma 就位：$ONIG_LIBS"

cd "$ROOT_DIR/work/jq"
echo "[jq] configure jq（--disable-shared --enable-static --with-oniguruma=<prefix>）"
./configure $CONFIGURE_COMMON --disable-shared --enable-static --disable-docs \
  --with-oniguruma="$ONIG_PREFIX" CC="$CC" AR="$AR_BIN" RANLIB="$RANLIB_BIN" >/dev/null
echo "[jq] make"
if ! make -j2 >/dev/null; then
  echo "::error title=make 失败::见上"
  exit 1
fi

BIN_SRC=""
for cand in .libs/jq jq; do
  if [ -f "$cand" ] && "$READELF_BIN" -h "$cand" >/dev/null 2>&1; then BIN_SRC="$cand"; break; fi
done
if [ -z "$BIN_SRC" ]; then
  echo "::error title=找不到 ELF 真身::候选都不是 ELF（libtool 包装脚本不算）"
  ls -la .libs 2>/dev/null | head -n 10 || true
  file jq 2>/dev/null || true
  exit 1
fi
echo "[jq] 真身：$BIN_SRC"
mkdir -p "$ROOT_DIR/$OUT/bin"
if ! cp "$BIN_SRC" "$ROOT_DIR/$OUT/bin/jq"; then
  echo "::error title=拷产物失败::目标目录形态如下（$ROOT_DIR/$OUT）"
  ls -la "$ROOT_DIR/$OUT" 2>/dev/null || true
  exit 1
fi
chmod 0755 "$ROOT_DIR/$OUT/bin/jq"
if ! "$READELF_BIN" -h "$ROOT_DIR/$OUT/bin/jq" >/dev/null 2>&1; then
  KIND=$(file -b "$ROOT_DIR/$OUT/bin/jq" || true)
  echo "::error title=产物不是 ELF::$KIND"
  exit 1
fi

if "$READELF_BIN" -d "$ROOT_DIR/$OUT/bin/jq" | grep -q 'libonig'; then
  echo "::error title=jq 依赖外部 libonig::vendored oniguruma 被编成了共享库 —— 我们的件只有 bin/jq，上机必缺库"
  "$READELF_BIN" -d "$ROOT_DIR/$OUT/bin/jq" | grep -i needed || true
  exit 1
fi
SIZE=$(stat -c%s "$ROOT_DIR/$OUT/bin/jq")
echo "[jq] 产出 $ROOT_DIR/$OUT/bin/jq（$SIZE 字节，无外部 libonig）"
