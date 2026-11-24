#!/usr/bin/env bash
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE/../.."
ROOT_DIR="$(pwd)"

OUT="${OUT:-dist}"
mkdir -p "$ROOT_DIR/$OUT"

die() { echo "::error title=$1::$(printf '%s\n' "${@:2}")" >&2; exit 1; }

TOOL=glibc-runtime
VER=$(node -e 'const t=require("./scripts/component-sources.json");process.stdout.write(String((t.sources["glibc-runtime"]||{}).version||""))')
[ -n "$VER" ] || die "取不到 glibc-runtime 版本" "钉值表 sources.glibc-runtime.version 为空"

# 1. 取已钉值的 Debian aarch64 libc6 包（fetch-pinned 做 sha256 字节校验）
DEB="$ROOT_DIR/work/glibc-runtime/libc6.deb"
mkdir -p "$(dirname "$DEB")"
bash "$ROOT_DIR/scripts/toolchain/fetch-pinned.sh" --pin "$TOOL" "$DEB" \
  || die "取 glibc-runtime 失败" "fetch-pinned --pin $TOOL 没通过（钉值/来源见 component-sources.json）"
echo "[glibc] 已取 libc6 deb $(stat -c%s "$DEB") 字节"

# 2. 解 ar + tar（deb 是 ar 包，data.tar.* 是 payload）
UNPACK="$ROOT_DIR/work/glibc-runtime/unpack"
rm -rf "$UNPACK" && mkdir -p "$UNPACK"
( cd "$UNPACK" && ar x "$DEB" ) || die "解 ar 失败" "$DEB 不是合法 ar/deb"
DT=$(find "$UNPACK" -maxdepth 1 -name 'data.tar.*' | head -1)
[ -n "$DT" ] || die "deb 无 data.tar" "$DEB 解出的 ar 里没有 data.tar.*"
mkdir -p "$UNPACK/root"
tar xf "$DT" -C "$UNPACK/root" || die "解 data.tar 失败" "$DT"
echo "[glibc] payload 已解"

# 3. 取核心库，落位成组件目录
DIST="$ROOT_DIR/$OUT"
rm -rf "$DIST/bin" "$DIST/lib"
mkdir -p "$DIST/bin" "$DIST/lib"
SRC="$UNPACK/root/usr/lib/aarch64-linux-gnu"
[ -d "$SRC" ] || die "payload 结构异常" "找不到 usr/lib/aarch64-linux-gnu"

# loader 是 ET_DYN 共享对象（无 PT_INTERP，自己就是解释器），按形态规范不算程序，
# 不能直接当组件入口。bin/ 放一个 shebang launcher（is_program_form 认 shebang），
# 它也是设计里的统一入口：glibc-run <程序> [args] -> guest loader 启动。
# loader 本身落 lib/，让 guest 的 /lib/ld-linux-aarch64.so.1 经路径 hook 解析到。
LOADER_REL="lib/ld-linux-aarch64.so.1"
cp -f "$SRC/ld-linux-aarch64.so.1" "$DIST/$LOADER_REL"
chmod 0755 "$DIST/$LOADER_REL"
printf '#!/system/bin/sh\nROOT="$(cd "$(dirname "$0")/.." && pwd)"\nexec "$ROOT/%s" --library-path "$ROOT/lib" "$@"\n' "$LOADER_REL" > "$DIST/bin/glibc-run"
chmod 0755 "$DIST/bin/glibc-run"

# 核心动态库：libc/libm/libpthread/libdl + nss/dns 等运行必需的最小集
KEEP="libc.so.6 libm.so.6 libpthread.so.0 libdl.so.2 librt.so.1 libutil.so.1 libresolv.so.2 libnsl.so.1 libmvec.so.1 libnss_files.so.2 libnss_dns.so.2"
for so in $KEEP; do
  if [ -f "$SRC/$so" ]; then cp -f "$SRC/$so" "$DIST/lib/$so"; else echo "  [warn] 缺 $so（跳过）"; fi
done

# C++ 运行库：官方 linux-arm64 Node addon（NARB 等）NEEDED libstdc++.so.6 /
# libgcc_s.so.1。Debian trixie 同源，按钉值表逐字节校验后解出 aarch64 .so。
# 走 --library-path 给 guest loader，不进全局 LD_LIBRARY_PATH（见 RuntimeEnvironment 注释）。
for pin in glibc-libstdcxx glibc-libgcc-s1; do
  DEB_CXX="$ROOT_DIR/work/glibc-runtime/$pin.deb"
  bash "$ROOT_DIR/scripts/toolchain/fetch-pinned.sh" --pin "$pin" "$DEB_CXX" \
    || die "取 $pin 失败" "fetch-pinned --pin $pin 没通过（钉值/来源见 component-sources.json）"
  U="$ROOT_DIR/work/glibc-runtime/u-$pin"
  rm -rf "$U" && mkdir -p "$U"
  ( cd "$U" && ar x "$DEB_CXX" ) || die "$pin 解 ar 失败" "$DEB_CXX"
  DT_CXX=$(find "$U" -maxdepth 1 -name 'data.tar.*' | head -1)
  [ -n "$DT_CXX" ] || die "$pin 无 data.tar" "$DEB_CXX 解出的 ar 里没有 data.tar.*"
  tar xf "$DT_CXX" -C "$U" || die "$pin 解 data.tar 失败" "$DT_CXX"
  for so in libstdc++.so.6 libgcc_s.so.1; do
    FOUND=$(find "$U" -type f -name "$so*" ! -name '*gdb.py' | head -1)
    if [ -n "$FOUND" ]; then cp -f "$FOUND" "$DIST/lib/$so"; echo "  [cxx] $so <- $pin"; fi
  done
done

# 4. 版本文件（package-component.sh 要求）
printf '%s\n' "$VER" > "$DIST/glibc-runtime.version"

# 5. 形态自检：loader 必须是 aarch64 ET_DYN
READELF=""
if [ -n "${CC:-}" ] && [ -x "$(dirname "$CC")/llvm-readelf" ]; then
  READELF="$(dirname "$CC")/llvm-readelf"
elif command -v llvm-readelf >/dev/null 2>&1; then
  READELF=llvm-readelf
fi
if [ -n "$READELF" ]; then
  INFO=$("$READELF" -h "$DIST/lib/ld-linux-aarch64.so.1" 2>/dev/null | grep -E 'Machine|Type' | tr '\n' ' ')
  case "$INFO" in *AArch64*DYN*) : ;; *) die "loader 形态不对" "$INFO" ;; esac
  echo "[glibc] loader 形态: $INFO"
fi

echo "[ok] glibc-runtime $OUT/bin(glibc-run) + $OUT/lib($(ls "$DIST/lib" | wc -l))"
echo "[ok] 版本 $VER"
