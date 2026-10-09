#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

HERE="$(dirname "$0")"
cd "$HERE/../.."
ROOT_DIR="$(pwd)"

API="${ANDROID_API:-35}"
JOBS="${JOBS:-4}"
SRC_KEY="pkgconf"
TOOL="pkg-config"
OUT="${OUT:-dist}"
case "$OUT" in /*) ;; *) OUT="$ROOT_DIR/$OUT" ;; esac

die() {
  local title="$1"; shift
  echo "::error title=$title::$(printf '%s\n' "$@")"
  exit 1
}
note() { echo "[$TOOL] $*"; }

[ -n "${CC:-}" ] || die "缺 CC" "需要 NDK 的 clang（build-component.yml 的「定位 NDK」步会注入）"
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

SRC_VER="$(bash "$ROOT_DIR/scripts/toolchain/fetch-pinned.sh" --src-version $SRC_KEY)"
SRC="$WORK/$SRC_KEY-src"
if [ ! -d "$SRC" ]; then
  TGZ="$WORK/$SRC_KEY.tar.gz"
  note "取 $SRC_KEY $SRC_VER 源码（仓内唯一入口，sha256 逐字节校验）"
  bash "$ROOT_DIR/scripts/toolchain/fetch-pinned.sh" --pin $SRC_KEY "$TGZ" || die "取源码失败" "钉值见 component-sources.json"
  rm -rf "$SRC" && mkdir -p "$SRC"
  tar xzf "$TGZ" -C "$SRC" --strip-components=1 || die "解包失败" "$TGZ"
fi
[ -x "$SRC/configure" ] || die "源码树异常" \
  "缺 configure —— pkgconf 的 release tarball 自带它（连 aclocal.m4、Makefile.in 都在）。" \
  "没有就得装 autotools 先 autoreconf，那是另一条更重的路。"
GOT_VER="$(sed -n 's/^AC_INIT(\[pkgconf\],\[\([0-9.]*\)\].*/\1/p' "$SRC/configure.ac" | head -1)"
[ "$GOT_VER" = "$SRC_VER" ] || die "版本不符" "钉的是 $SRC_VER，configure.ac 里是 $GOT_VER"
note "源码 $GOT_VER 就位（自带 configure，零外部依赖）"

BUILD="$WORK/build"
INST="$WORK/_inst"
rm -rf "$BUILD" "$INST" && mkdir -p "$BUILD" "$INST"

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
    > "$WORK/configure.log" 2>&1 \
    || { echo "=== configure 失败取证（末 40 行）==="; tail -40 "$WORK/configure.log"; exit 1; }
)
note "configure 通过"

make -C "$BUILD" -j"$JOBS" > "$WORK/build.log" 2>&1 \
  || { echo "=== 编译失败取证（error 行 + 末 40 行）==="; \
       grep -nE "error:|Error [0-9]+$|undefined (symbol|reference)" "$WORK/build.log" | head -25 || true; \
       tail -40 "$WORK/build.log"; exit 1; }
make -C "$BUILD" install > "$WORK/install.log" 2>&1 \
  || { echo "=== install 失败（末 30 行）==="; tail -30 "$WORK/install.log"; exit 1; }

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

INFO=$(file -b "$OUT/bin/$TOOL")
case "$INFO" in *aarch64*|*arm64*|*ARM64*) : ;; *) die "架构不对" "$INFO" ;; esac
"$LLVM_STRIP" --strip-unneeded "$OUT/bin/$TOOL" 2>/dev/null || true

bash "$ROOT_DIR/scripts/verify/check-elf-deps.sh" "$OUT/bin/$TOOL" "$TOOL"

BAD="$("$LLVM_READELF" -W -l "$OUT/bin/$TOOL" 2>/dev/null | awk '/^[[:space:]]*LOAD/{print $NF}' \
      | while read -r a; do
          case "$a" in 0x[0-9a-fA-F]*) ;; *) continue ;; esac
          d=$((a))
          if [ "$d" -eq 0 ]; then continue; fi
          if [ $(( d % 16384 )) -ne 0 ]; then printf ' %s' "$a"; fi
        done)"
[ -z "$BAD" ] || die "16KB 对齐不合格" "这些 LOAD 段：$BAD"

printf '%s' "$SRC_VER" > "$OUT/$TOOL.version"
echo "[ok] $OUT/bin/$TOOL $(stat -c%s "$OUT/bin/$TOOL") 字节（动态、依赖闭环、16KB 对齐、aarch64）"
echo "[$TOOL] 落位：组件通道 → files/programs/$TOOL/<版本>/bin/$TOOL"
echo "[$TOOL] 判据要给它一个 .pc 文件真查一次（起得来不等于能解析 .pc）"