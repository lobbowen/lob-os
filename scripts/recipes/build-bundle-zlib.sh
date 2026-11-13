#!/usr/bin/env bash

set -uo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/bundle-env.sh" zlib

OUT_DIR="$WORK/out"
mkdir -p "$OUT_DIR"

bash "$ROOT_DIR/scripts/toolchain/fetch-pinned.sh" --pin zlib "$ROOT_DIR/work/zlib.tar.gz" \
  || die "zlib 源码取不到" "钉值见 scripts/component-sources.json"
rm -rf "$WORK/src" && mkdir -p "$WORK/src"
tar xzf "$ROOT_DIR/work/zlib.tar.gz" -C "$WORK/src" --strip-components=1

(
  set -e
  cd "$WORK/src"

  ZVER="$(sed -n 's/.*#define ZLIB_VERSION "\([^"]*\)".*/\1/p' zlib.h | sed -n '1p')"
  ZVER1="${ZVER%%.*}"
  [ -n "$ZVER" ] || { echo "=== zlib.h 取证 ==="; sed -n '1,12p' zlib.h; exit 1; }
  Z_SHLIB="libz.so"
  Z_SHLIBV="libz.so.$ZVER"
  Z_SHLIBM="libz.so.$ZVER1"
  echo "[zlib] 库名取自 zlib.h：$Z_SHLIBV（SONAME 层 $Z_SHLIBM）"

  CHOST=aarch64-linux-android CC="$CC" AR="$AR_BIN" RANLIB="$RANLIB_BIN" \
  uname=Linux-host \
  CFLAGS="-O2 -fPIC -D__ANDROID_API__=$API" \
  LDSHARED="$CC -shared -Wl,-soname,$Z_SHLIBM" \
  SHAREDLIB="$Z_SHLIB" SHAREDLIBV="$Z_SHLIBV" SHAREDLIBM="$Z_SHLIBM" \
  ./configure --prefix="$OUT_DIR" > "$WORK/configure.log" 2>&1 \
    || { echo "=== zlib configure 失败取证（末 30 行）==="; tail -30 "$WORK/configure.log"; exit 1; }

  LIB_SO="$(sed -n 's/^SHAREDLIBV[[:space:]]*=[[:space:]]*//p' Makefile | sed -n '1p')"
  case "$LIB_SO" in
    *.so|*.so.*) : ;;
    *) echo "=== Makefile 里的库名取证 ==="
       sed -n '/^STATICLIB[[:space:]]*=/p;/^SHAREDLIB/p' Makefile | sed -n '1,6p'
       echo "--- configure.log 末尾 ---"; tail -15 "$WORK/configure.log"
       die "zlib 退回静态了" "Makefile 里 SHAREDLIBV 是空的：'$LIB_SO'（传了 LDSHARED 仍失败，看上面 configure.log）"
  esac
  echo "[zlib] 共享库目标 = $LIB_SO"

  make -j"$JOBS" LDFLAGS="-Wl,--enable-new-dtags '-Wl,-rpath,\$\$ORIGIN'" "$LIB_SO" > "$WORK/build.log" 2>&1 \
    || { echo "=== zlib 编译失败取证（末 30 行）==="; tail -30 "$WORK/build.log"; exit 1; }

  mkdir -p "$OUT_DIR/lib" "$OUT_DIR/include"
  SO_N=0
  for f in "$WORK/src"/libz.so*; do
    [ -e "$f" ] || continue
    cp -Pf "$f" "$OUT_DIR/lib/" 2>/dev/null || cp -f "$f" "$OUT_DIR/lib/"
    SO_N=$((SO_N + 1))
  done
  [ "$SO_N" -gt 0 ] || {
    echo "=== zlib 源码树里没有 libz.so* 的取证 ==="
    ls -la "$WORK/src"/libz* 2>/dev/null || echo "（没有 libz*）"
    echo "--- build.log 末尾 ---"; tail -20 "$WORK/build.log" 2>/dev/null
    die "zlib 没编出共享库" "源码树里没有 libz.so*（改了 Makefile 也没用？）"
  }
  echo "[zlib] 拷了 $SO_N 个 libz.so* 到 $OUT_DIR/lib"
  cp -f "$WORK/src/zlib.h" "$OUT_DIR/include/"
  cp -f "$WORK/src/zconf.h" "$OUT_DIR/include/"
)

SO="$OUT_DIR/lib/libz.so"
[ -f "$SO" ] || {
  ls -la "$OUT_DIR/lib" >&2 || true
  die "zlib 没产出共享库" "$OUT_DIR/lib 下没有 libz.so"
}

[ -f "$OUT_DIR/lib/libz.so.1" ] \
  || die "zlib 共享库缺 SONAME 层" "$OUT_DIR/lib 下只有 libz.so，没有 libz.so.1"

emit_piece zlib "$OUT_DIR" lib/libz.so 1000
echo "[ok] zlib 是共享库（不是静态链进别的二进制）"