#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

HERE="$(dirname "$0")"
cd "$HERE/.."
ROOT_DIR="$(pwd)"

ABI="${ABI:-arm64-v8a}"
API="${ANDROID_API:-35}"
JOBS="${JOBS:-4}"
TOOL="make"
OUT="${OUT:-dist}"
case "$OUT" in /*) ;; *) OUT="$ROOT_DIR/$OUT" ;; esac

die() {
  local title="$1"; shift
  echo "::error title=$title::$(printf '%s\n' "$@")"
  exit 1
}
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
[ -f "$SRC/configure" ] || die "源码树异常" \
  "缺 configure —— GNU make 的发布包自带它，没有的话需要 autoreconf（而那要 autotools）"
[ -f "$SRC/Makefile.am" ] || die "源码树异常" "缺 Makefile.am"
ACTUAL="$(sed -n 's/^AC_INIT(\[GNU Make\],\[\([0-9.]*\)\].*/\1/p' "$SRC/configure.ac" | head -1)"
[ "$ACTUAL" = "$MAKE_VER" ] || die "版本不符" \
  "钉的是 $MAKE_VER，configure.ac 里是 $ACTUAL（钉值写错或源站给了别的版本）"
note "源码 $ACTUAL 就位（自带 configure，无需 autoreconf）"

BUILD="$WORK/build"
rm -rf "$BUILD" "$WORK/_inst" && mkdir -p "$BUILD" "$WORK/_inst"

( set -e
  cd "$BUILD"
  "$SRC/configure" \
    --host=aarch64-linux-android --build=x86_64-pc-linux-gnu \
    --prefix="$WORK/_inst" \
    --disable-shared --enable-static \
    --without-guile \
    --disable-posix-spawn \
    CC="$CC" AR="$LLVM_AR" RANLIB="$LLVM_RANLIB" \
    CFLAGS="-O2 -D__ANDROID_API__=$API" \
    LDFLAGS="-static" \
    > "$WORK/configure.log" 2>&1 \
    || { echo "=== configure 失败取证（末 40 行）==="; tail -40 "$WORK/configure.log"; exit 1; }
)
note "configure 通过"

note "configure 完成（产物架构在下面用 file 判定，不靠读 configure 摘要）"

make -C "$BUILD" -j"$JOBS" > "$WORK/build.log" 2>&1 \
  || { echo "=== make 编译失败取证（error 行 + 末 40 行）==="; \
       grep -nE "error:|Error [0-9]+$|undefined (symbol|reference)" "$WORK/build.log" | head -25 || true; \
       tail -40 "$WORK/build.log"; exit 1; }

note "编译完成；产物：$(ls -la "$BUILD/make" 2>&1 | head -1)"

BIN="$BUILD/make"
[ -x "$BIN" ] || die "没产出 make" "$BIN 不存在或不可执行"
cp -f "$BIN" "$OUT/bin/$TOOL"
chmod 0755 "$OUT/bin/$TOOL"

SIZE=$(stat -c%s "$OUT/bin/$TOOL")
[ "$SIZE" -gt 300000 ] || die "产物可疑" "make 只有 $SIZE 字节 —— 静态编不该这么小"
INFO=$(file -b "$OUT/bin/$TOOL")
case "$INFO" in *aarch64*|*arm64*|*ARM64*) : ;; *) die "架构不对" "$INFO" ;; esac
"$LLVM_STRIP" --strip-unneeded "$OUT/bin/$TOOL" 2>/dev/null || true

DYN="$("$LLVM_READELF" -W -l "$OUT/bin/$TOOL" 2>/dev/null | awk '/^[[:space:]]*DYNAMIC/{print "y"}')"
if [ -n "$DYN" ]; then
  die "不是静态产物" "有 PT_DYNAMIC —— 底座/工具件不该依赖任何共享库"
fi

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