#!/usr/bin/env bash
# 编 zlib —— **一件一个脚本**，产出共享库 libz.so。
#
# 此前它与 openssl、curl 一起编到同一个 $DEPS/ 下（静态 .a），
# 于是依赖它们的件把依赖静态链了进去 —— 一个二进制里装了几个件。
# 现在每件一个脚本、产出 .so、只记 DT_NEEDED，运行时从全局软链找。
#
# 依据：ldconfig(8)「checks the header and filenames when determining which
#       versions should have their links updated」
set -uo pipefail
# shellcheck disable=SC1091
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/piece-env.sh" zlib

OUT_DIR="$WORK/out"
mkdir -p "$OUT_DIR"

bash "$ROOT_DIR/scripts/toolchain/fetch-pinned.sh" --pin zlib "$ROOT_DIR/work/zlib.tar.gz" \
  || die "zlib 源码取不到" "钉值见 scripts/component-sources.json"
rm -rf "$WORK/src" && mkdir -p "$WORK/src"
tar xzf "$ROOT_DIR/work/zlib.tar.gz" -C "$WORK/src" --strip-components=1

(
  set -e
  cd "$WORK/src"
  # ★ zlib 的 configure 是手写的，只认 --prefix 与 --static ——
  #   --shared 是 autoconf 的选项，它**不认**（会静默忽略）。
  #   不给 --static 时它默认编共享库（Makefile.in 里 SHARED_MODE=1）。
  #   失败过一次：传了 --shared，它当没看见，只产出 libz.a。
  #   要显式共享就传 SHARED_MODE=1（它的 configure 会认这个环境变量）。
  CHOST=aarch64-linux-android CC="$CC" AR="$AR_BIN" RANLIB="$RANLIB_BIN" \
    SHARED_MODE=1 \
    ./configure --prefix="$OUT_DIR" > "$WORK/configure.log" 2>&1 \
    || { echo "=== zlib configure 失败取证（末 30 行）==="; tail -30 "$WORK/configure.log"; exit 1; }
  make -j"$JOBS" > "$WORK/build.log" 2>&1 \
    || { echo "=== zlib 编译失败取证（末 30 行）==="; tail -30 "$WORK/build.log"; exit 1; }
  make install > "$WORK/install.log" 2>&1 \
    || { echo "=== zlib install 失败取证（末 30 行）==="; tail -30 "$WORK/install.log"; exit 1; }
)

SO="$(ls "$OUT_DIR"/lib/libz.so 2>/dev/null | head -1)"
[ -n "$SO" ] || {
  ls -la "$OUT_DIR/lib" >&2 || true
  die "zlib 没产出共享库" "$OUT_DIR/lib 下没有 libz.so（只有 .a 就说明 SHARED_MODE 没生效）"
}
# 共享库要有 SONAME 层（libz.so.1）—— linker 运行时找的是它
ls "$OUT_DIR"/lib/libz.so.1 >/dev/null 2>&1 \
  || die "zlib 共享库缺 SONAME 层" "$OUT_DIR/lib 下只有 libz.so，没有 libz.so.1"

# 落位：库 → usr/lib/<id>/<版本>/lib/，并建 usr/lib/libz.so 全局软链
land_piece zlib "$SO" 1000
echo "[ok] zlib 是共享库（不是静态链进别的二进制）"
