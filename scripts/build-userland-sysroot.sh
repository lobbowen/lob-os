#!/usr/bin/env bash
# 开发环境的 sysroot —— 头文件 + 目标库，让设备上能「编译」而不只是「跑」。
#
# ── 为什么 sysroot 单独成件（而不是跟 clang 捆一起）──
# 核实过的事实：NDK 的 sysroot 是**宿主无关**的 —— 它只有 aarch64 的头文件与
# 目标文件（.a/.so），不含任何 x86_64 宿主二进制。所以它
#   · 不需要交叉编译任何东西（从 NDK 里拷出来就是成品）
#   · 体积小得多（头文件 + 静态库，量级 ~150-250MB，不含 1GB+ 的 LLVM）
#   · 版本必须与 clang 严格匹配 —— 所以两者的**版本号要在清单里同源**
#     （本件与 clang 件读同一个 tools/userland-sources.json 的 ndkVersion）
#
# ── 它是「几乎每个要编译的程序都会碰」的东西 ──
# 对齐 Linux 判据：没有 sysroot 时，clang 会去找 /usr/include 与 /usr/lib，
# 找不到就报 "fatal error: 'stdio.h' file not found"。这是**静默到最后一刻
# 才炸**的错误形态 —— 程序装好了、跑起来了，直到第一次编译才崩。
# 它属于基础设施，不属于「有真实消费者才补」的小众能力。
#
# ── 落位 ──
# 商店 COMPONENT 通道 → files/programs/<id>/<version>/sysroot
# 编译时通过 --sysroot=<那个路径> 指向它；usr/include 的软链由程序侧或
# 后续阶段的封装件负责（这里只提供 sysroot 本体，不猜调用方怎么用）。
#
# 依赖：clang 件（同 NDK 版本）。清单里的 requires 声明它。
set -euo pipefail
export LC_ALL=C

HERE="$(dirname "$0")"
cd "$HERE/.."
ROOT_DIR="$(pwd)"

OUT="${OUT:-dist}"
# OUT 允许绝对路径（CI 与本地都这么用），所以先判定性转绝对路径，
# 后面一律拼 "$OUT" —— 写成 "$ROOT_DIR/$OUT" 会在绝对路径下变成
# /repo//abs/path，那个路径不存在，报错还指向一个看不懂的地方。
case "$OUT" in /*) ;; *) OUT="$ROOT_DIR/$OUT" ;; esac
TOOL="sysroot"
ANDROID_API="${ANDROID_API:-23}"
ABI="${ABI:-aarch64-v8a}"

die() { echo "::error title=$1::${2:-}"; exit 1; }

# ── 定位 NDK ──
# 不猜：CI 的 build-userland.yml「定位 NDK」步会注入 ANDROID_NDK_LATEST_HOME；
# 本机跑时可用环境变量指定。三种都拿不到就明确报错，不静默产出空件。
NDK="${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}}"
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
  NDK=$(ls -d "${ANDROID_HOME:-/nonexistent}"/ndk/* 2>/dev/null | sort -V | tail -1 || true)
fi
[ -n "$NDK" ] && [ -d "$NDK" ] || die "无 NDK" \
  "要 sysroot 就得有 NDK（它就是 sysroot 的来源）。设 ANDROID_NDK_LATEST_HOME 或 ANDROID_HOME。"

# 版本必须与钉值表一致 —— sysroot 与 clang 要同源，否则 clang 编出来的东西链不上。
WANT_NDK="$(bash "$ROOT_DIR/scripts/fetch-pinned.sh" --ndk)"
GOT_NDK="$(awk -F= '/^Pkg\.Revision/ {gsub(/[[:space:]]/,"",$2); print $2; exit}' "$NDK/source.properties" 2>/dev/null || true)"
if [ -z "$GOT_NDK" ]; then
  die "读不出 NDK 版本" "$NDK/source.properties 里没有 Pkg.Revision —— 无法确认与 clang 同源"
fi
if [ "$GOT_NDK" != "$WANT_NDK" ]; then
  die "NDK 版本与钉值不符" "要 $WANT_NDK（userland-sources.json 钉的，clang 件按它编），实际 $GOT_NDK"
fi
echo "[sysroot] NDK=$NDK 版本=$GOT_NDK（与钉值一致）"

# ── 定位 sysroot ──
SYSROOT=""
for cand in "$NDK"/toolchains/llvm/prebuilt/*/sysroot; do
  [ -d "$cand" ] && SYSROOT="$cand" && break
done
[ -n "$SYSROOT" ] || die "NDK 里没有 sysroot" "找 $NDK/toolchains/llvm/prebuilt/*/sysroot 没找到"
echo "[sysroot] sysroot=$SYSROOT"

INC="$SYSROOT/usr/include"
LIBDIR="$SYSROOT/usr/lib/$ABI"
[ -d "$INC" ] || die "sysroot 缺头文件" "$INC 不存在"
[ -d "$LIBDIR" ] || die "sysroot 缺目标库目录" "$LIBDIR 不存在 —— ABI 写法对吗（$ABI）"

# 头文件必须是真头文件，不能是空目录（NDK 结构变化时报错，不产出半个件）
N_HDRS=$(find "$INC" -maxdepth 1 -name '*.h' | wc -l)
[ "$N_HDRS" -gt 0 ] || die "头文件是空的" "$INC 下没有 .h —— NDK 结构变了？"
for must in stdio.h stdlib.h string.h; do
  [ -f "$INC/$must" ] || die "缺标准头文件" "$must 不在 $INC —— 编译任何东西都会立刻失败"
done
echo "[sysroot] 头文件 $N_HDRS 个顶层 .h（含 stdio/stdlib/string 必备三件）"

# 目标库：libc.so 等按 API 等级分目录。取与 ANDROID_API 对应的那一层。
APILIB=""
for cand in "$LIBDIR/$ANDROID_API" "$LIBDIR"; do
  [ -d "$cand" ] && APILIB="$cand" && break
done
[ -n "$APILIB" ] || die "缺 API$ANDROID_API 的目标库" "$LIBDIR 下没有 $ANDROID_API（现有：$(ls "$LIBDIR" 2>/dev/null | tr '\n' ' '))"
echo "[sysroot] 目标库层=$APILIB"

# ── 组装 ──
# 暂存目录建在 dist/ 底下，**不是 work/** —— 因为 package-userland.sh 只拷 dist/，
# 留在 work/ 的东西不会进 zip。件装到设备上就是个空壳，而判据只看 bin/sysroot
# 还会通过（那是最坏的形态：判据说好，实际不能用）。
STAGE="$OUT/.sysroot-stage"
rm -rf "$STAGE" && mkdir -p "$STAGE/sysroot"

# 头文件：只拷 include 树。头文件里可能有符号链接，cp -a 保。
echo "[sysroot] 拷头文件"
cp -a "$INC" "$STAGE/sysroot/include"

# 目标库：$API 层的内容 + 该层的 .so/.a，以及 clang 内建运行库。
echo "[sysroot] 拷目标库"
mkdir -p "$STAGE/sysroot/lib"
cp -a "$APILIB"/. "$STAGE/sysroot/lib/" 2>/dev/null || true
# NDK 把内置运行库（libclang_rt.*）放在 sysroot/usr/lib/<triple>/ 根，不在 API 层。
for extra in "$LIBDIR"/libclang_rt*.a "$LIBDIR"/libclang_rt*.so; do
  [ -e "$extra" ] && cp -a "$extra" "$STAGE/sysroot/lib/" && continue
  :
done

# ── 自检：不是「拷完了」，是「编东西要用的东西都在」──
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

# libc.so 与 libclang_rt 是「链得上」的两端，缺任一则链接必失败
if [ ! -e "$STAGE/sysroot/lib/libc.so" ]; then
  echo "[miss] lib/libc.so —— 链接任何程序都会失败（'cannot find -lc'）"; fail=1
fi
if ! ls "$STAGE/sysroot"/lib/libclang_rt*.a >/dev/null 2>&1; then
  echo "[miss] lib/libclang_rt*.a —— 编任何东西都要它（内建函数如 __aeabi_uldivmod）"; fail=1
fi
[ "$fail" -eq 0 ] || die "sysroot 不完整" "上面 miss 的几件是编译的硬依赖，产出半个 sysroot 比不产出更坏"

# 体积（进清单的 size 格要用）
TREE_KB=$(du -sk "$STAGE/sysroot" | cut -f1)
N_FILES=$(find "$STAGE/sysroot" -type f | wc -l)
echo "[sysroot] $N_FILES 个文件，$((TREE_KB / 1024)) MiB"

# ── 版本与落位 ──
# 版本用 NDK 版本：sysroot 的身份就是它来自哪个 NDK。
# 先建 OUT/bin —— version 文件要落在 OUT 根下，目录不存在写不进去，
# 而报错会指向「No such file or directory」，看不出是哪个目录没建。
mkdir -p "$OUT/bin"
printf '%s' "$GOT_NDK" > "$OUT/$TOOL.version"

# 把暂存树移到 dist/sysroot（可见路径）——
# 落位后入口 bin/sysroot 打印的正是 <件根>/sysroot，与实际目录一致。
# 先删旧树再移：sysroot 跨版本时不能留下上一次的残留头文件。
rm -rf "$OUT/$TOOL"
mv "$STAGE/$TOOL" "$OUT/$TOOL"
rmdir "$STAGE" 2>/dev/null || rm -rf "$STAGE"

# 入口：sysroot 不是可执行件，入口给一份元数据（clang --sysroot 要指向真实目录，
# 不能靠入口文件；入口存在是为了让商店清单的 entry 契约成立）。
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