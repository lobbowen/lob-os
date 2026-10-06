#!/usr/bin/env bash
# GNU make —— 开发环境件（阶段1d）。
#
# ── 为什么 make 不必等 clang ──
# make **自带构建系统**（包里带现成的 configure，无需 autoreconf）。
# 所以交叉编译它只需要 NDK 的 clang 当编译器，不需要设备上已经有 clang。
# 这与阶段5 的 busybox 同一形态（两者都已成功/在链路上）。
#
# 与 clang 那批的差别只有一个：clang 是「用编译器编出来的工具」，
# 交叉编译 LLVM 是自举（Termux 级别的工程量）；make/cmake/pkg-config
# 是「自带构建系统的独立工具」，交叉编译它们是常规操作。
#
# 形态：静态编、不链任何共享库 —— 与阶段5 的 busybox 同理由
# （底座/工具件之间不互相依赖到「少一件就起不来」）。
set -euo pipefail
export LC_ALL=C

HERE="$(dirname "$0")"
cd "$HERE/.."
ROOT_DIR="$(pwd)"

ABI="${ABI:-arm64-v8a}"
API="${ANDROID_API:-23}"
JOBS="${JOBS:-4}"
TOOL="make"
OUT="${OUT:-dist}"
case "$OUT" in /*) ;; *) OUT="$ROOT_DIR/$OUT" ;; esac

die() { echo "::error title=$1::${2:-}"; exit 1; }
note() { echo "[$TOOL] $*"; }

[ -n "${CC:-}" ] || die "缺 CC" "需要 NDK 的 clang（build-userland.yml 的「定位 NDK」步会注入）"
TC="$(dirname "$CC")"
LLVM_AR="$TC/llvm-ar"
LLVM_RANLIB="$TC/llvm-ranlib"
LLVM_STRIP="${LLVM_STRIP:-$TC/llvm-strip}"
LLVM_READELF="${LLVM_READELF:-$TC/llvm-readelf}"
for t in "$LLVM_AR" "$LLVM_RANLIB" "$LLVM_READELF"; do
  [ -x "$t" ] || die "缺工具" "$t 不存在"
done
NDK_ROOT="$(cd "$TC/../../../../.." && pwd)"
[ -d "$NDK_ROOT" ] || die "定位 NDK 失败" "从 clang 路径反推得到 '$NDK_ROOT'"

mkdir -p "$OUT/bin"
WORK="$ROOT_DIR/work/$TOOL"
mkdir -p "$WORK"

# ── 取源码 ──
MAKE_VER="$(bash "$ROOT_DIR/scripts/fetch-pinned.sh" --src-version $TOOL)"
SRC="$WORK/$TOOL-src"
if [ ! -d "$SRC" ]; then
  TGZ="$WORK/$TOOL.tar.gz"
  note "取 $TOOL $MAKE_VER 源码（走仓内唯一入口，sha256 逐字节校验）"
  bash "$ROOT_DIR/scripts/fetch-pinned.sh" --pin $TOOL "$TGZ" || die "取源码失败" "钉值见 userland-sources.json 的 $TOOL 键"
  rm -rf "$SRC" && mkdir -p "$SRC"
  tar xzf "$TGZ" -C "$SRC" --strip-components=1 \
    || die "解包失败" "$TGZ —— 格式是否与 .tar.gz 相符？"
fi
# 源码树形状自检：不是「解包成功就算」
[ -f "$SRC/configure" ] || die "源码树异常" \
  "缺 configure —— GNU make 的发布包自带它，没有的话需要 autoreconf（而那要 autotools）"
[ -f "$SRC/Makefile.am" ] || die "源码树异常" "缺 Makefile.am"
ACTUAL="$(sed -n 's/^AC_INIT(\[GNU Make\],\[\([0-9.]*\)\].*/\1/p' "$SRC/configure.ac" | head -1)"
[ "$ACTUAL" = "$MAKE_VER" ] || die "版本不符" \
  "钉的是 $MAKE_VER，configure.ac 里是 $ACTUAL（钉值写错或源站给了别的版本）"
note "源码 $ACTUAL 就位（自带 configure，无需 autoreconf）"

# ── configure ──
BUILD="$WORK/build"
rm -rf "$BUILD" "$WORK/_inst" && mkdir -p "$BUILD" "$WORK/_inst"

# 交叉编译的标准三件套：
#   --host=aarch64-linux-android  我们要产出的目标
#   --build=x86_64-pc-linux-gnu   跑 configure 的机器
# CFLAGS/LDFLAGS 里不加 -shared 之类 —— 静态编，configure 自己带 --disable-shared
#
# jobserver 不预判：configure 自己探测（configure.ac 里查 pipe/sigaction/
# SA_RESTART/WNOHANG，缺一就自动关），user_job_server=no 也能显式关。
# 猜它「在 Android 上一定不行」是没有依据的 —— 探测交给 configure。
( set -e
  cd "$BUILD"
  "$SRC/configure" \
    --host=aarch64-linux-android --build=x86_64-pc-linux-gnu \
    --prefix="$WORK/_inst" \
    --disable-shared --enable-static \
    --without-guile \
    CC="$CC" AR="$LLVM_AR" RANLIB="$LLVM_RANLIB" \
    CFLAGS="-O2 -D__ANDROID_API__=$API" \
    LDFLAGS="-static" \
    > "$WORK/configure.log" 2>&1 \
    || { echo "=== configure 失败取证（末 40 行）==="; tail -40 "$WORK/configure.log"; exit 1; }
)
note "configure 通过"

# 交叉目标是否真的生效，最终由**产物架构**判定（见下面的 file 检查），
# 那里比读 configure 的摘要可靠：摘要格式随 autoconf 版本变，产物不会。
# 这里只记一行事实供排障时对照。
note "configure 完成（产物架构在下面用 file 判定，不靠读 configure 摘要）"

# ── 编 ──
make -C "$BUILD" -j"$JOBS" > "$WORK/build.log" 2>&1 \
  || { echo "=== make 编译失败取证（error 行 + 末 40 行）==="; \
       grep -nE "error:|Error [0-9]+$|undefined (symbol|reference)" "$WORK/build.log" | head -25 || true; \
       tail -40 "$WORK/build.log"; exit 1; }

BIN="$BUILD/make"
[ -x "$BIN" ] || die "没产出 make" "$BIN 不存在或不可执行"
cp -f "$BIN" "$OUT/bin/$TOOL"
chmod 0755 "$OUT/bin/$TOOL"

# ── 形态自检 ──
SIZE=$(stat -c%s "$OUT/bin/$TOOL")
[ "$SIZE" -gt 300000 ] || die "产物可疑" "make 只有 $SIZE 字节 —— 静态编不该这么小"
INFO=$(file -b "$OUT/bin/$TOOL")
case "$INFO" in *aarch64*|*arm64*|*ARM64*) : ;; *) die "架构不对" "$INFO" ;; esac
"$LLVM_STRIP" --strip-unneeded "$OUT/bin/$TOOL" 2>/dev/null || true

# 静态编：不该有 PT_DYNAMIC。真机动态链失败是「起不来」的最常见形态。
DYN="$("$LLVM_READELF" -W -l "$OUT/bin/$TOOL" 2>/dev/null | awk '/^[[:space:]]*DYNAMIC/{print "y"}')"
if [ -n "$DYN" ]; then
  die "不是静态产物" "有 PT_DYNAMIC —— 底座/工具件不该依赖任何共享库"
fi

# 16KB 对齐（Android 15+ 硬要求）
BAD="$("$LLVM_READELF" -W -l "$OUT/bin/$TOOL" 2>/dev/null | awk '/^[[:space:]]*LOAD/{print $NF}' \
      | while read -r a; do
          case "$a" in 0x[0-9a-fA-F]*) ;; *) continue ;; esac
          d=$(( a )); [ "$d" -eq 0 ] && continue
          [ $(( d % 16384 )) -ne 0 ] && printf ' %s' "$a"
        done)"
[ -z "$BAD" ] || die "16KB 对齐不合格" "这些 LOAD 段：$BAD"

printf '%s' "$MAKE_VER" > "$OUT/$TOOL.version"
echo "[ok] $OUT/bin/$TOOL $(stat -c%s "$OUT/bin/$TOOL") 字节（静态、无 PT_DYNAMIC、16KB 对齐合格、aarch64）"
echo "[$TOOL] 落位：商店 COMPONENT 通道 → files/programs/$TOOL/<版本>/bin/$TOOL"
echo "[$TOOL] 判据要真跑一条 makefile（起得来不等于能用），由 userland-verify.json 的 criteria.$TOOL 承担"