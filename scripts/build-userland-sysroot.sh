#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

HERE="$(dirname "$0")"
cd "$HERE/.."
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

WANT_NDK="$(bash "$ROOT_DIR/scripts/fetch-pinned.sh" --ndk)"

NDK=""
if [ -n "${CC:-}" ]; then
  TC="$(dirname "$CC")"
  NDK="$(cd "$TC/../../../../.." && pwd 2>/dev/null || true)"
fi
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
  die "拿不到 NDK" \
    "本脚本从 CC 反推 NDK 根目录（<ndk>/toolchains/llvm/prebuilt/<host>/bin 往上 5 层）。" \
    "CC=$CC —— 它由 CI 的 scripts/locate-ndk.sh 注入。本机跑请先 export CC=<那个 clang 的路径>。"
fi

GOT_NDK="$(awk -F= '/^Pkg\.Revision/ {gsub(/[[:space:]]/,"",$2); print $2; exit}' "$NDK/source.properties" 2>/dev/null || true)"
if [ -z "$GOT_NDK" ]; then
  die "读不出 NDK 版本" "$NDK/source.properties 里没有 Pkg.Revision —— 无法确认与 clang 同源"
fi
if [ "$GOT_NDK" != "$WANT_NDK" ]; then
  die "NDK 版本与钉值不符" "要 $WANT_NDK（userland-sources.json 钉的，clang 件按它编），实际 $GOT_NDK（$NDK）" \
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
for extra in "$LIBDIR"/libclang_rt*.a "$LIBDIR"/libclang_rt*.so; do
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
if ! ls "$STAGE/sysroot"/lib/libclang_rt*.a >/dev/null 2>&1; then
  echo "[miss] lib/libclang_rt*.a —— 编任何东西都要它（内建函数如 __aeabi_uldivmod）"; fail=1
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