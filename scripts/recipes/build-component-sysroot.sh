#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

HERE="$(dirname "$0")"
cd "$HERE/../.."
ROOT_DIR="$(pwd)"

OUT="${OUT:-dist}"
case "$OUT" in /*) ;; *) OUT="$ROOT_DIR/$OUT" ;; esac
TOOL="sysroot"
ANDROID_API="${ANDROID_API:-35}"
ABI="${ABI:-aarch64-v8a}"

die() {
  local title="$1"; shift
  echo "::error title=$title::$(printf '%s\n' "$@")"
  exit 1
}

WANT_NDK="$(bash "$ROOT_DIR/scripts/toolchain/fetch-pinned.sh" --ndk)"

[ -n "${CC:-}" ] || die "缺 CC" \
  "需要 NDK 的 clang 来编探针、验证这个 sysroot 真能编东西。CC 由 CI 的 locate-ndk.sh 注入；本机跑请先 export CC=<.../bin/aarch64-linux-android35-clang>"
TC="$(dirname "$CC")"
NDK="$(cd "$TC/../../../../.." && pwd 2>/dev/null || true)"
LLVM_READELF="${LLVM_READELF:-$TC/llvm-readelf}"
[ -f "$LLVM_READELF" ] || die "缺 llvm-readelf" \
  "$LLVM_READELF 不存在 —— 它由 CI 的 locate-ndk.sh 注入，本机跑请一并 export"
WORK="$ROOT_DIR/work/$TOOL"
mkdir -p "$WORK"
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
  die "拿不到 NDK" \
    "本脚本从 CC 反推 NDK 根目录（<ndk>/toolchains/llvm/prebuilt/<host>/bin 往上 5 层）。" \
    "CC=$CC —— 它由 CI 的 scripts/toolchain/locate-ndk.sh 注入。本机跑请先 export CC=<那个 clang 的路径>。"
fi

GOT_NDK="$(awk -F= '/^Pkg\.Revision/ {gsub(/[[:space:]]/,"",$2); print $2; exit}' "$NDK/source.properties" 2>/dev/null || true)"
if [ -z "$GOT_NDK" ]; then
  die "读不出 NDK 版本" "$NDK/source.properties 里没有 Pkg.Revision —— 无法确认与 clang 同源"
fi
if [ "$GOT_NDK" != "$WANT_NDK" ]; then
  die "NDK 版本与钉值不符" "要 $WANT_NDK（component-sources.json 钉的，clang 件按它编），实际 $GOT_NDK（$NDK）" \
    "它是从 CC=$CC 反推的 —— 若 CC 指向的不是钉值那一版，那是上游注入错了。"
fi
echo "[sysroot] NDK=$NDK 版本=$GOT_NDK（与钉值一致）"

SYSROOT=""
for cand in "$NDK"/toolchains/llvm/prebuilt/*/sysroot; do
  [ -d "$cand" ] && SYSROOT="$cand" && break
done
[ -n "$SYSROOT" ] || die "NDK 里没有 sysroot" "找 $NDK/toolchains/llvm/prebuilt/*/sysroot 没找到"
echo "[sysroot] sysroot=$SYSROOT"

INC="$SYSROOT/usr/include"
TRIPLE=""
if [ -n "${CC:-}" ]; then
  BASE_C="$(basename "$CC")"
  case "$BASE_C" in
    aarch64-linux-android[0-9]*-clang) TRIPLE="aarch64-linux-android" ;;
  esac
fi
if [ -z "$TRIPLE" ]; then
  case "$ABI" in
    arm64-v8a|aarch64-v8a|aarch64-linux-android) TRIPLE="aarch64-linux-android" ;;
    *) die "ABI 与目标不对应" "ABI=$ABI 且 CC=${CC:-（未注入）} 认不出 triple。本仓只编 aarch64：CC 文件名形如 aarch64-linux-android35-clang，或 ABI 写 arm64-v8a / aarch64-v8a。两者不是一回事 —— $SYSROOT/usr/lib 下没有 $ABI。" ;;
  esac
fi
LIBDIR="$SYSROOT/usr/lib/$TRIPLE"
[ -d "$INC" ] || die "sysroot 缺头文件" "$INC 不存在"
[ -d "$LIBDIR" ] || die "sysroot 缺目标库目录" "$LIBDIR 不存在（triple=$TRIPLE，sysroot/usr/lib 下现有：$(ls "$SYSROOT/usr/lib" 2>/dev/null | tr '\n' ' ')）"

N_HDRS=$(find "$INC" -maxdepth 1 -name '*.h' | wc -l)
[ "$N_HDRS" -gt 0 ] || die "头文件是空的" "$INC 下没有 .h —— NDK 结构变了？"
for must in stdio.h stdlib.h string.h; do
  [ -f "$INC/$must" ] || die "缺标准头文件" "$must 不在 $INC —— 编译任何东西都会立刻失败"
done
echo "[sysroot] 头文件 $N_HDRS 个顶层 .h（含 stdio/stdlib/string 必备三件）"

APILIB=""
for cand in "$LIBDIR/$ANDROID_API" "$LIBDIR"; do
  [ -d "$cand" ] && APILIB="$cand" && break
done
[ -n "$APILIB" ] || die "缺 API$ANDROID_API 的目标库" "$LIBDIR 下没有 $ANDROID_API（现有：$(ls "$LIBDIR" 2>/dev/null | tr '\n' ' '))"
echo "[sysroot] 目标库层=$APILIB"

STAGE="$OUT/.sysroot-stage"
rm -rf "$STAGE" && mkdir -p "$STAGE/sysroot"

echo "[sysroot] 拷头文件"
cp -a "$INC" "$STAGE/sysroot/include"

echo "[sysroot] 拷目标库"
mkdir -p "$STAGE/sysroot/lib"
cp -a "$APILIB"/. "$STAGE/sysroot/lib/" 2>/dev/null || true
for extra in "$LIBDIR"/libclang_rt*.a "$LIBDIR"/libclang_rt*.so "$APILIB"/libclang_rt*.a "$APILIB"/libclang_rt*.so; do
  [ -e "$extra" ] && cp -a "$extra" "$STAGE/sysroot/lib/" && continue
  :
done

fail=0
chk() {
  if [ -e "$2" ]; then
    echo "[ok]   $1"
  else
    echo "[miss] $1 —— 缺了它，clang 会在第一次编译时报 'file not found'"; fail=1
  fi
}
echo "== sysroot 自检 =="
chk "stdio.h"   "$STAGE/sysroot/include/stdio.h"
chk "stdlib.h"  "$STAGE/sysroot/include/stdlib.h"
chk "string.h"  "$STAGE/sysroot/include/string.h"
chk "android/api-level.h" "$STAGE/sysroot/include/android/api-level.h"
chk "sysroot/lib 存在"     "$STAGE/sysroot/lib"

if [ ! -e "$STAGE/sysroot/lib/libc.so" ]; then
  echo "[miss] lib/libc.so —— 链接任何程序都会失败（'cannot find -lc'）"; fail=1
fi
RT_MISSING=0
if ! ls "$STAGE/sysroot"/lib/libclang_rt*.a >/dev/null 2>&1; then
  RT_MISSING=1
  echo "[info] sysroot 内没有 libclang_rt*.a —— 它在 clang 的 runtime 目录里："
  echo "       $(ls -d "$NDK"/toolchains/llvm/prebuilt/*/lib/clang/*/lib/linux 2>/dev/null | head -1)"
  echo "       clang 编 .o 时从那里取，不必在 sysroot 内。下面的真编探针会验证这一点。"
fi

echo "[sysroot] 真编一次（判据要测行为，不查文件在不在）"
PROBE="$WORK/probe"
mkdir -p "$PROBE"
cat > "$PROBE/probe.c" <<'PROBE_C'
unsigned long long probe_div(unsigned long long a, unsigned long long b) { return a / b; }
double probe_fdiv(double a, double b) { return a / b; }
PROBE_C
if "$CC" --sysroot="$STAGE/sysroot" -c "$PROBE/probe.c" -o "$PROBE/probe.o" 2>"$PROBE/err.log"; then
  echo "[ok] clang --sysroot 指到本件后，能编出 .o（内建函数已解决）"
  MACH="unknown"
  if [ -f "$PROBE/probe.o" ]; then
    HEX="$(od -An -tx1 -j 18 -N 2 "$PROBE/probe.o" 2>/dev/null | tr -d ' \n')"
    case "$HEX" in
      b700|00b7) MACH="AArch64" ;;
      3e00|003e) MACH="x86_64" ;;
      2800|0028) MACH="ARM" ;;
      *) MACH="e_machine=0x$HEX" ;;
    esac
  else
    MACH="（.o 不存在）"
  fi
  if [ "$MACH" = "AArch64" ]; then
    echo "[ok] 产物是 AArch64（e_machine=0xB7，不是宿主 x86_64）"
    [ "$RT_MISSING" -eq 1 ] && echo "[ok] 且 libclang_rt 不在 sysroot 内也能编 —— 确认它不是硬依赖"
  else
    echo "::error title=探针产物架构不对::$MACH"
    echo "  期望 AArch64（e_machine=0xB7）。clang 编成了别的架构 —— 交叉配置没生效。"
    echo "  这里读 ELF 头字节而不是 grep llvm-readelf 的输出：上一轮同一份产物的 grep -q AArch64 判了假、而 grep -i machine 却是 AArch64，判据抓错了东西，成因未查明。"
    echo "  llvm-readelf 的说法：$("$LLVM_READELF" -h "$PROBE/probe.o" 2>&1 | grep -i machine | head -1)"
    exit 1
  fi
else
  echo "::error title=sysroot 编不出东西::clang --sysroot=$STAGE/sysroot 编译失败（末 20 行）："
  if [ -f "$PROBE/err.log" ]; then tail -20 "$PROBE/err.log"; else echo "  （$PROBE/err.log 不存在）"; fi
  echo "  CC=$CC"
  if [ "$RT_MISSING" -eq 1 ]; then
    echo "  若报 __aeabi_* / 找不到内建函数：libclang_rt 没进 sysroot，且 clang 也没在自己的 runtime 目录里找到它。"
    echo "  探针的真实报错在上面，先按它定位，别直接归因到 libclang_rt。"
  fi
  exit 1
fi
[ "$fail" -eq 0 ] || die "sysroot 不完整" "上面 miss 的几件是编译的硬依赖，产出半个 sysroot 比不产出更坏"

TREE_KB=$(du -sk "$STAGE/sysroot" | cut -f1)
N_FILES=$(find "$STAGE/sysroot" -type f | wc -l)
echo "[sysroot] $N_FILES 个文件，$((TREE_KB / 1024)) MiB"

mkdir -p "$OUT/bin"
printf '%s' "$GOT_NDK" > "$OUT/$TOOL.version"

rm -rf "$OUT/$TOOL"
mv "$STAGE/$TOOL" "$OUT/$TOOL"
rmdir "$STAGE" 2>/dev/null || rm -rf "$STAGE"

cat > "$OUT/bin/$TOOL" <<EOF
#!/usr/bin/env bash
# sysroot 不是可执行件 —— 它是 clang 编译时指向的目录。
# 这个入口只回答「在哪」，让工具能自问自答（不靠约定猜路径）。
here="\$(cd "\$(dirname "\${BASH_SOURCE[0]}")/.." && pwd)"
echo "\$here/sysroot"
EOF
chmod 0755 "$OUT/bin/$TOOL"

echo "[ok] $OUT/bin/$TOOL（打印 sysroot 路径，不是编译入口）"
echo "[sysroot] 落位：商店 COMPONENT 通道 → files/programs/$TOOL/<version>/sysroot"
echo "[sysroot] 消费方：clang --sysroot=<上面那个路径>（clang 件同 NDK 版本）"