#!/usr/bin/env bash

set -uo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/bundle-env.sh" jq

OUT_DIR="$WORK/out"
mkdir -p "$OUT_DIR"

bash "$ROOT_DIR/scripts/toolchain/fetch-pinned.sh" --pin jq "$ROOT_DIR/work/jq.tar.gz" \
  || die "jq 源码取不到" "钉值见 scripts/component-sources.json"
rm -rf "$WORK/src" && mkdir -p "$WORK/src"
tar xzf "$ROOT_DIR/work/jq.tar.gz" -C "$WORK/src" --strip-components=1

(
  set -e
  cd "$WORK/src"

  CHOST=aarch64-linux-android CC="$CC" AR="$AR_BIN" RANLIB="$RANLIB_BIN" \
  ac_cv_prog_cc_cross=yes \
  ac_cv_func_malloc_0_nonnull=yes \
    ./configure --host=aarch64-linux-android --build=x86_64-pc-linux-gnu \
      --prefix="$OUT_DIR" --disable-maintainer-mode \
      --with-oniguruma=builtin \
      > "$WORK/configure.log" 2>&1 \
    || { echo "=== jq configure 失败取证（末 30 行）==="; tail -30 "$WORK/configure.log"; exit 1; }

  make -j"$JOBS" LDFLAGS="-static" > "$WORK/build.log" 2>&1 \
    || { echo "=== jq 编译失败取证（末 30 行）==="; tail -30 "$WORK/build.log"; exit 1; }
)

mkdir -p "$OUT_DIR/bin"
if [ -f "$WORK/src/jq" ]; then

  cp -f "$WORK/src/jq" "$OUT_DIR/bin/jq"
elif [ -f "$WORK/src/.libs/jq" ]; then
  cp -f "$WORK/src/.libs/jq" "$OUT_DIR/bin/jq"
elif [ -f "$OUT_DIR/bin/jq" ]; then
  :
else
  echo "=== jq 产物查找的取证 ==="
  ls -la "$WORK/src/.libs" 2>/dev/null | head -15 || echo "（没有 .libs/）"
  find "$WORK/src" -maxdepth 2 -name jq -type f 2>/dev/null | head -5
fi

SO="$OUT_DIR/bin/jq"
if [ ! -f "$SO" ]; then

  echo "=== jq 源码树（make 之后）==="
  ls -la "$WORK/src" 2>/dev/null | head -20
  echo "=== jq 的构建日志 ==="
  [ -f "$WORK/build.log" ] && tail -30 "$WORK/build.log" || echo "（没有 build.log —— make 可能压根没跑）"
  echo "=== configure 日志末尾 ==="
  [ -f "$WORK/configure.log" ] && tail -20 "$WORK/configure.log" || echo "（没有 configure.log）"
  die "jq 没产出" "$SO 不存在（上面是取证输出）"
fi

emit_piece jq "$OUT_DIR" bin/jq 300000 "jq"
echo "[ok] jq 落位（可执行件，bin/jq 那一侧；lib/ 下不再复制一份没人要的拷贝）"
