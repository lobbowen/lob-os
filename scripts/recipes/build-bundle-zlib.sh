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
  #  静态还是共享由 configure 的 shared 变量决定（第 83 行 shared=1）
  #   它靠一次 .so 探测（第 517~529 行）来判定，失败就**静默退回静态**
  #   （Makefile 里 SHAREDLIB/SHAREDLIBV 全空，只剩 libz.a）。
  #   交叉编 Android 时那次探测没通过。
  #
  #   正解：**显式给 LDSHARED 与三个库名**，绕开探测。
  #   这是 configure 官方给的入口 —— 它全部写成 ${VAR-默认} 形态
  #   （第 297/443/444/445 行），外部已设的值就保留，
  #   且第 1018~1034 行的 sed 会把这些值原样写进 Makefile。
  #   库名**从 zlib 自己的 zlib.h 里读版本号拼出来**（不改名、不写死）：
  #     VER = 1.3.2（zlib.h 的 ZLIB_VERSION）· VER1 = 1
  #     SHAREDLIB=libz.so · SHAREDLIBV=libz.so.$VER · SHAREDLIBM=libz.so.$VER1
  #     这与 configure 第 327~329、443~445 行的算法逐字一致。
  ZVER="$(sed -n 's/.*#define ZLIB_VERSION "\([^"]*\)".*/\1/p' zlib.h | sed -n '1p')"
  ZVER1="${ZVER%%.*}"
  [ -n "$ZVER" ] || { echo "=== zlib.h 取证 ==="; sed -n '1,12p' zlib.h; exit 1; }
  Z_SHLIB="libz.so"
  Z_SHLIBV="libz.so.$ZVER"
  Z_SHLIBM="libz.so.$ZVER1"
  echo "[zlib] 库名取自 zlib.h：$Z_SHLIBV（SONAME 层 $Z_SHLIBM）"

  # 下面这一组环境变量必须**整体**在 ./configure 那条命令的前缀里（一条逻辑行，中间不能有注释）。
  #
  # 缺陷名（C-102）**注释行把续行组打断，于是 configure 一个环境变量也没收到**：
  #   bash 的续行（行尾反斜杠）把下一行接上来，而接上来的那一行若是注释，
  #   注释吃掉该逻辑行的余下部分并**终止这一条命令**；
  #   而它终止时那一组赋值**只落在当前 shell**（不是导出给子进程的环境）。
  #   本机复现（recipe 原文那几行）：
  #     在同一个 shell 里读到 CHOST=[aarch64-linux-android] CC=[mycc] ...
  #     而 configure 作为子进程读到的是 CC=<unset> CFLAGS=<unset> CHOST=<unset>
  #   后果有两条，都要记：
  #     (1) configure 第 99/100 行 old_cc 与 old_cflags 都空 ⇒ 第 522 行走的是
  #         "No shared library support."（不带 try without defining 那半句）这一支；
  #     (2) 更要紧的是 **LDSHARED 没进 configure 的环境** ⇒ configure 第 297 行
  #         取到的是**默认值**（带 --version-script）；
  #         而那次探测只链一个空测试对象，zlib.map 里的符号它一个都没有 ⇒
  #         ld.lld 报 version script assignment of 'ZLIB_1.2.0' to symbol 'compressBound' failed: symbol not defined
  #         ⇒ 探测失败 ⇒ 第 532-536 行把 SHAREDLIBV 清空 ⇒ 退回静态。
  #   读数（run 40064205705 的 work/zlib/src/configure.log 第 28 到 32 行原文）：
  #     <NDK>/aarch64-linux-android35-clang -shared -Wl,-soname,libz.so.1,--version-script,zlib.map -O3 -fPIC -o ztest...so ztest...o
  #     ld.lld: error: version script assignment of 'ZLIB_1.2.0' to symbol 'compressBound' failed: symbol not defined
  #   而 artifact 里那份 work/zlib/configure.log 是**stdout 重定向**那份（699 字节 14 行），
  #   所以那一行探测命令在内部那份 configure.log 里，不在 stdout 那份里。
  #
  # 而 rpath 给在**被依赖的那一份**上（01 今天 S100 裁的第五格走丙），
  # 走 **LDFLAGS**，而它在 Makefile.in:289 那条 recipe 行的末尾
  #   （$(LDSHARED) $(SFLAGS) -o $@ $(PIC_OBJS) $(LDSHAREDLIBC) $(LDFLAGS)）。
  # 而 LDFLAGS **只在 make 那一趟给**（不给 configure）—— 因为 configure 的探测那行
  #   （第 521 行 try $LDSHARED $SFLAGS $LDFLAGS ...）是不带引号展开的，
  #   而 rpath 那一格的值形态必须带单引号（见下面那一处），两者不能共存于那一行。
  # 而 LDSHARED 那一格必须**不带引号**：探测那行同样是不带引号展开的。
  CHOST=aarch64-linux-android CC="$CC" AR="$AR_BIN" RANLIB="$RANLIB_BIN" \
  uname=Linux-host \
  CFLAGS="-O2 -fPIC -D__ANDROID_API__=$API" \
  LDSHARED="$CC -shared -Wl,-soname,$Z_SHLIBM" \
  SHAREDLIB="$Z_SHLIB" SHAREDLIBV="$Z_SHLIBV" SHAREDLIBM="$Z_SHLIBM" \
  ./configure --prefix="$OUT_DIR" > "$WORK/configure.log" 2>&1 \
    || { echo "=== zlib configure 失败取证（末 30 行）==="; tail -30 "$WORK/configure.log"; exit 1; }

  #  我此前做错的几处，都记在这里免得再犯 
  #   1. Makefile.in 里**没有** LIBZ 这个变量 —— 库名是 SHAREDLIBV
  #      （configure 第 444 行）。grep '^LIBZ = ' 永远空。
  #   2. SRCDIR 不是「静态/共享」开关 —— 它是源码子目录前缀
  #      （configure 第 22 行 SRCDIR=`dirname $0`，官方用来支持 out-of-tree 构建）。
  #      改它等于改源码搜索路径。
  #   3. shared 目标依赖 examplesh/minigzipsh，它们在官方 tarball 里**存在**。
  #
  # 真判据：读 configure 自己写进 Makefile 的 SHAREDLIBV，空则说明退回静态了。
  LIB_SO="$(sed -n 's/^SHAREDLIBV[[:space:]]*=[[:space:]]*//p' Makefile | sed -n '1p')"
  case "$LIB_SO" in
    *.so|*.so.*) : ;;
    *) echo "=== Makefile 里的库名取证 ==="
       sed -n '/^STATICLIB[[:space:]]*=/p;/^SHAREDLIB/p' Makefile | sed -n '1,6p'
       echo "--- configure.log 末尾 ---"; tail -15 "$WORK/configure.log"
       die "zlib 退回静态了" "Makefile 里 SHAREDLIBV 是空的：'$LIB_SO'（传了 LDSHARED 仍失败，看上面 configure.log）"
  esac
  echo "[zlib] 共享库目标 = $LIB_SO"

  # 而 rpath 那两格在 make 那一趟给（不给 configure，见上面那一段的理由）：
  #   单引号包住**整个** -Wl,-rpath,$ORIGIN，而 $ 写成 $$（make 折一次成一个）。
  #   而 --enable-new-dtags 一起给（它决定出 DT_RUNPATH 而不是 DT_RPATH）。
  make -j"$JOBS" LDFLAGS="-Wl,--enable-new-dtags '-Wl,-rpath,\$\$ORIGIN'" "$LIB_SO" > "$WORK/build.log" 2>&1 \
    || { echo "=== zlib 编译失败取证（末 30 行）==="; tail -30 "$WORK/build.log"; exit 1; }

  # 不用 make install —— install 目标还会装 example/minigzip 这些宿主可执行件，
  # 我们只要库和头文件，按 Makefile 里那几个变量名自己落位。
  # 产物是一整条链：libz.so.1.3.2（实体）+ libz.so.1 · libz.so（软链）。
  # linker 运行时找的是带 SONAME 的那个（libz.so.1）。
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
# 共享库要有 SONAME 层（libz.so.1）—— linker 运行时找的是它
[ -f "$OUT_DIR/lib/libz.so.1" ] \
  || die "zlib 共享库缺 SONAME 层" "$OUT_DIR/lib 下只有 libz.so，没有 libz.so.1"

# 落位：库 → usr/lib/<id>/<版本>/lib/，并建 usr/lib/libz.so 全局软链
emit_piece zlib "$OUT_DIR" lib/libz.so 1000
echo "[ok] zlib 是共享库（不是静态链进别的二进制）"