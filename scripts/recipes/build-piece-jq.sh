#!/usr/bin/env bash
# 编 jq —— 一件一个脚本，产出静态可执行件 liblobosjq.so。
#
# jq 是单个可执行文件（没有库形态），编译期静态链进 libc —— 那是它自己的实现，
# 不是「把别的件链进来」。依赖判据是动态的：ElfFacts 读 DT_NEEDED，
# 若它 NEEDED 了系统库那属于系统提供，不是件。
set -uo pipefail
# shellcheck disable=SC1091
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/piece-env.sh" jq

OUT_DIR="$WORK/out"
mkdir -p "$OUT_DIR"

bash "$ROOT_DIR/scripts/toolchain/fetch-pinned.sh" --pin jq "$ROOT_DIR/work/jq.tar.gz" \
  || die "jq 源码取不到" "钉值见 scripts/component-sources.json"
rm -rf "$WORK/src" && mkdir -p "$WORK/src"
tar xzf "$ROOT_DIR/work/jq.tar.gz" -C "$WORK/src" --strip-components=1

(
  set -e
  cd "$WORK/src"
  # 交叉编 Android —— configure 的 jq 配方认 aarch64-linux-android
  CHOST=aarch64-linux-android CC="$CC" AR="$AR_BIN" RANLIB="$RANLIB_BIN" \
    ./configure --prefix="$OUT_DIR" --disable-maintainer-mode \
      --with-oniguruma=builtin \
      > "$WORK/configure.log" 2>&1 \
    || { echo "=== jq configure 失败取证（末 30 行）==="; tail -30 "$WORK/configure.log"; exit 1; }
  make -j"$JOBS" LDFLAGS="-static" > "$WORK/build.log" 2>&1 \
    || { echo "=== jq 编译失败取证（末 30 行）==="; tail -30 "$WORK/build.log"; exit 1; }
)

SO="$OUT_DIR/bin/jq"
[ -f "$SO" ] || die "jq 没产出" "$SO 不存在"
mkdir -p "$OUT_DIR/lib"
cp -f "$SO" "$OUT_DIR/lib/liblobosjq.so"
chmod +x "$OUT_DIR/lib/liblobosjq.so"

land_piece jq "$OUT_DIR/lib/liblobosjq.so" 300000
echo "[ok] jq 落位（可执行件，落usr/lib/jq/<版本>/bin/）"
