#!/usr/bin/env bash
# pkg-config —— 开发环境件（阶段1d）。
#
# ── 它是什么 ──
# 读 .pc 文件并输出 `-I/-L/-l` 的小程序。程序跑 ./configure 时靠它探测库；
# cmake 的 find_package（pkg-config 模式）也用它。
#
# 为什么装 **pkgconf** 而不是 freedesktop 的 pkg-config：
#   · pkgconf 是活跃维护的那支（freedesktop 那个已只做维护）
#   · 它**零外部依赖** —— 实测 configure.ac 里无 PKG_CHECK_MODULES、
#     不提 glib、不提 libffi。所以交叉编译它不需要先编任何依赖。
#   · 它自带的 pkg-config 兼容 freedesktop 那个（命令行与 .pc 格式都兼容），
#     所以装出来的就是标准 `pkg-config`。
#
# 落位名用 pkg-config（用户与 configure 脚本按这个名字找它），
# 源码钉值键名用 pkgconf（上游项目名）——两者不同的原因写在上面。
set -euo pipefail
export LC_ALL=C

HERE="$(dirname "$0")"
cd "$HERE/.."
ROOT_DIR="$(pwd)"

API="${ANDROID_API:-23}"
JOBS="${JOBS:-4}"
SRC_KEY="pkgconf"          # 钉值表里的键
TOOL="pkg-config"          # 落位与调用名
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
command -v make >/dev/null 2>&1 || die "缺 make" \
  "autotools 靠 make 驱动（runner 自带；缺了请 apt-get install make）"

mkdir -p "$OUT/bin"
WORK="$ROOT_DIR/work/$SRC_KEY"
mkdir -p "$WORK"

# ── 取源码 ──
SRC_VER="$(bash "$ROOT_DIR/scripts/fetch-pinned.sh" --src-version $SRC_KEY)"
SRC="$WORK/$SRC_KEY-src"
if [ ! -d "$SRC" ]; then
  TGZ="$WORK/$SRC_KEY.tar.gz"
  note "取 $SRC_KEY $SRC_VER 源码（仓内唯一入口，sha256 逐字节校验）"
  bash "$ROOT_DIR/scripts/fetch-pinned.sh" --pin $SRC_KEY "$TGZ" || die "取源码失败" "钉值见 userland-sources.json"
  rm -rf "$SRC" && mkdir -p "$SRC"
  tar xzf "$TGZ" -C "$SRC" --strip-components=1 || die "解包失败" "$TGZ"
fi
# 形状自检：release tarball 自带 configure，无需 autoreconf
[ -x "$SRC/configure" ] || die "源码树异常" \
  "缺 configure —— pkgconf 的 release tarball 自带它（连 aclocal.m4、Makefile.in 都在）。" \
  "没有就得装 autotools 先 autoreconf，那是另一条更重的路。"
GOT_VER="$(sed -n 's/^AC_INIT(\[pkgconf\],\[\([0-9.]*\)\].*/\1/p' "$SRC/configure.ac" | head -1)"
[ "$GOT_VER" = "$SRC_VER" ] || die "版本不符" "钉的是 $SRC_VER，configure.ac 里是 $GOT_VER"
note "源码 $GOT_VER 就位（自带 configure，零外部依赖）"

# ── configure ──
BUILD="$WORK/build"
INST="$WORK/_inst"
rm -rf "$BUILD" "$INST" && mkdir -p "$BUILD" "$INST"

# 静态编（与 make/cmake 同理由：工具件不该依赖任何共享库）
# dlopen 是 pkgconf 的一个可选能力（--enable-dlopen）—— 关掉，
# 因为它 dlopen 的是宿主插件目录，对交叉编译出的静态件没有意义。
(
  set -e
  cd "$BUILD"
  "$SRC/configure" \
    --host=aarch64-linux-android --build=x86_64-pc-linux-gnu \
    --prefix="$INST" \
    --disable-shared --enable-static \
    --disable-dlopen --disable-dlopen-self --disable-dlopen-self-static \
    CC="$CC" AR="$LLVM_AR" RANLIB="$LLVM_RANLIB" \
    CFLAGS="-O2 -D__ANDROID_API__=$API" \
    LDFLAGS="-static" \
    > "$WORK/configure.log" 2>&1 \
    || { echo "=== configure 失败取证（末 40 行）==="; tail -40 "$WORK/configure.log"; exit 1; }
)
note "configure 通过"

# ── 编 ──
make -C "$BUILD" -j"$JOBS" > "$WORK/build.log" 2>&1 \
  || { echo "=== 编译失败取证（error 行 + 末 40 行）==="; \
       grep -nE "error:|Error [0-9]+$|undefined (symbol|reference)" "$WORK/build.log" | head -25 || true; \
       tail -40 "$WORK/build.log"; exit 1; }
make -C "$BUILD" install > "$WORK/install.log" 2>&1 \
  || { echo "=== install 失败（末 30 行）==="; tail -30 "$WORK/install.log"; exit 1; }

# 落位名：源码里叫 pkgconf，用户与 configure 按 pkg-config 找它
BIN=""
for cand in "$INST/bin/pkg-config" "$INST/bin/pkgconf"; do
  [ -x "$cand" ] && BIN="$cand" && break
done
[ -n "$BIN" ] || {
  echo "=== $INST/bin 下有什么 ==="; ls "$INST/bin" 2>/dev/null | head -5
  die "没产出 pkg-config" "$INST/bin 下既没有 pkg-config 也没有 pkgconf"
}
cp -f "$BIN" "$OUT/bin/$TOOL"
chmod 0755 "$OUT/bin/$TOOL"

# ── 形态自检 ──
SIZE=$(stat -c%s "$OUT/bin/$TOOL")
[ "$SIZE" -gt 50000 ] || die "产物可疑" "pkg-config 只有 $SIZE 字节 —— 静态编不该这么小"
INFO=$(file -b "$OUT/bin/$TOOL")
case "$INFO" in *aarch64*|*arm64*|*ARM64*) : ;; *) die "架构不对" "$INFO" ;; esac
"$LLVM_STRIP" --strip-unneeded "$OUT/bin/$TOOL" 2>/dev/null || true

DYN="$("$LLVM_READELF" -W -l "$OUT/bin/$TOOL" 2>/dev/null | awk '/^[[:space:]]*DYNAMIC/{print "y"}')"
[ -z "$DYN" ] || {
  NEEDED="$("$LLVM_READELF" -W -d "$OUT/bin/$TOOL" 2>/dev/null | sed -n 's/.*NEEDED.*\[\(.*\)\].*/\1/p' | tr '\n' ' ')"
  die "不是静态产物" "有 PT_DYNAMIC（NEEDED: ${NEEDED:-?}）—— 工具件不该依赖任何共享库"
}

BAD="$("$LLVM_READELF" -W -l "$OUT/bin/$TOOL" 2>/dev/null | awk '/^[[:space:]]*LOAD/{print $NF}' \
      | while read -r a; do
          case "$a" in 0x[0-9a-fA-F]*) ;; *) continue ;; esac
          d=$(( a )); [ "$d" -eq 0 ] && continue
          [ $(( d % 16384 )) -ne 0 ] && printf ' %s' "$a"
        done)"
[ -z "$BAD" ] || die "16KB 对齐不合格" "这些 LOAD 段：$BAD"

printf '%s' "$SRC_VER" > "$OUT/$TOOL.version"
echo "[ok] $OUT/bin/$TOOL $(stat -c%s "$OUT/bin/$TOOL") 字节（静态、无 PT_DYNAMIC、16KB 对齐、aarch64）"
echo "[$TOOL] 落位：商店 COMPONENT 通道 → files/programs/$TOOL/<版本>/bin/$TOOL"
echo "[$TOOL] 判据要给它一个 .pc 文件真查一次（起得来不等于能解析 .pc）"