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
  # jq 的 configure 会跑一个编出来的小程序来探测 —— 交叉编时它跑不了，
  # 所以要告诉它「不交叉」（ac_cv_prog_cc_cross=yes），
  # 否则报 cannot run C compiled programs。
  # jq 的 configure 会跑一个编出来的小程序做探测，交叉编时跑不了。
  # 要让它同时知道两件事：
  #   ac_cv_prog_cc_cross=yes  「不运行编出来的程序」（探测改用编译期检查）
  #   --host=aarch64-linux-android  「这是交叉编」（否则它仍想在本机跑）
  # 少任一个都会报cannot run C compiled programs。
  CHOST=aarch64-linux-android CC="$CC" AR="$AR_BIN" RANLIB="$RANLIB_BIN" \
  ac_cv_prog_cc_cross=yes \
  ac_cv_func_malloc_0_nonnull=yes \
    ./configure --host=aarch64-linux-android --build=x86_64-pc-linux-gnu \
      --prefix="$OUT_DIR" --disable-maintainer-mode \
      --with-oniguruma=builtin \
      > "$WORK/configure.log" 2>&1 \
    || { echo "=== jq configure 失败取证（末 30 行）==="; tail -30 "$WORK/configure.log"; exit 1; }
  # jq 的二进制在 .libs/ 下（libtool 产物），make install 才把它搬到 bin/
  make -j"$JOBS" LDFLAGS="-static" > "$WORK/build.log" 2>&1 \
    || { echo "=== jq 编译失败取证（末 30 行）==="; tail -30 "$WORK/build.log"; exit 1; }
)

# jq 是 libtool 工程：
#   · 编译产物在 .libs/jq（libtool 放那儿，剥掉 libtool 那层包装）
#   · make install 目标在它的 Makefile 里**不存在**（报 No rule to make target install）
# 正解：直接从 .libs/ 取、自己搬到 out/bin/。
mkdir -p "$OUT_DIR/bin"
if [ -f "$WORK/src/jq" ]; then
  # 取证显示：.libs/ 里只有 libjq.a（静态库），可执行文件在 src/jq
  cp -f "$WORK/src/jq" "$OUT_DIR/bin/jq"
elif [ -f "$WORK/src/.libs/jq" ]; then
  cp -f "$WORK/src/.libs/jq" "$OUT_DIR/bin/jq"
elif [ -f "$OUT_DIR/bin/jq" ]; then
  :   # 某些配置下 make 直接把 jq 装到了 prefix
else
  echo "=== jq 产物查找的取证 ==="
  ls -la "$WORK/src/.libs" 2>/dev/null | head -15 || echo "（没有 .libs/）"
  find "$WORK/src" -maxdepth 2 -name jq -type f 2>/dev/null | head -5
fi

SO="$OUT_DIR/bin/jq"
if [ ! -f "$SO" ]; then
  # 失败时给线索 —— 之前只说「jq 不存在」，看不出 make 到底做了什么
  echo "=== jq 源码树（make 之后）==="
  ls -la "$WORK/src" 2>/dev/null | head -20
  echo "=== jq 的构建日志 ==="
  [ -f "$WORK/build.log" ] && tail -30 "$WORK/build.log" || echo "（没有 build.log —— make 可能压根没跑）"
  echo "=== configure 日志末尾 ==="
  [ -f "$WORK/configure.log" ] && tail -20 "$WORK/configure.log" || echo "（没有 configure.log）"
  die "jq 没产出" "$SO 不存在（上面是取证输出）"
fi
mkdir -p "$OUT_DIR/lib"
cp -f "$SO" "$OUT_DIR/lib/liblobosjq.so"
chmod +x "$OUT_DIR/lib/liblobosjq.so"

land_piece jq "$OUT_DIR/lib/liblobosjq.so" 300000
echo "[ok] jq 落位（可执行件，落usr/lib/jq/<版本>/bin/）"
