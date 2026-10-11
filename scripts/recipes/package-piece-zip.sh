#!/usr/bin/env bash
# 打标准件归档 —— 一件一个包，装配器只读包。
#
# 形态（照抄 .deb：包里自带全部信息，装的时候不查外部清单）：
#   component-<id>-<版本>-<sha12>.tar.gz
#     ├── component-meta.json    自描述：id / version / entry / form / requires
#     ├── bin/<名>               命令（entry=bin/… 时）
#     └── lib/<名>.so            库（entry=lib/… 时）
#
# package-component.sh 已转发到这里（单一入口，避免两套打包逻辑漂移）。
# 这个从 OUT_DIR 读、用 tar —— 本机与 CI 都有 tar，zip 不在 base 镜像里。
#
# 用法：package-piece-zip.sh <件名> <OUT_DIR> <版本> [entry]
set -euo pipefail

ID="${1:?需要件名}"
OUT="${2:?需要 OUT_DIR}"
VER="${3:?需要版本}"
ENTRY="${4:-}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
cd "$ROOT"

META_SRC="$ROOT/scripts/component-sources.json"
[ -f "$META_SRC" ] || { echo "::error title=缺钉值表::$META_SRC 不在"; exit 1; }

if [ -z "$ENTRY" ]; then
  if [ -d "$OUT/bin" ] && [ -n "$(ls -A "$OUT/bin" 2>/dev/null)" ]; then
    ENTRY="bin/$(ls "$OUT/bin" | head -1)"
  else
    ENTRY="lib/$(ls "$OUT/lib" | grep '\.so$' | head -1)"
  fi
fi

case "$ENTRY" in
  bin/*|lib/*) ;;
  *) echo "::error title=entry 形状不对::$ENTRY"; exit 1 ;;
esac

[ -e "$OUT/$ENTRY" ] || {
  echo "::error title=缺件::$OUT/$ENTRY 不在（entry=$ENTRY）"
  find "$OUT" -type f 2>/dev/null | head -10 | sed 's/^/::error::    /'
  exit 1
}

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

# 只取 entry 指的那个文件，外加与它同名的版本链 —— **不整个目录搬**。
#
# openssl 的构建产物目录里还有 libcrypto.a、cmake/、engines-3/、ossl-modules/
# 这些不是这个件的运行时所需（openssl 的包实测 7.6MB，而它只该有
# libssl.so 与 libcrypto.so 两个文件）。整个搬进来等于把构建残留也装进系统。
#
# 版本链指共享库的 soname 一族（libz.so → libz.so.1 → libz.so.1.3.2）：
# 它们是同一份字节的三个名字，而 linker 按 DT_NEEDED 找的是带版本的那一层。
mkdir -p "$STAGE/$(dirname "$ENTRY")"
cp -Pf "$OUT/$ENTRY" "$STAGE/$ENTRY" 2>/dev/null || cp -f "$OUT/$ENTRY" "$STAGE/$ENTRY"
eb="$(basename "$ENTRY")"
ed="$(dirname "$ENTRY")"
for extra in "$OUT/$ed/$eb".*; do
  [ -f "$extra" ] || continue
  xb="$(basename "$extra")"
  case "$xb" in *.so|*.so.*) cp -Pf "$extra" "$STAGE/$ed/$xb" 2>/dev/null || cp -f "$extra" "$STAGE/$ed/$xb" ;; esac
done

node "$ROOT/scripts/recipes/gen-component-meta.js" \
  "$ID" "$VER" "$ENTRY" "$STAGE/component-meta.json" \
  || { echo "::error title=$ID 说明生成失败"; exit 1; }

find "$STAGE" -exec touch -h -t 198001010000.00 {} +

DIST="$ROOT/dist"
mkdir -p "$DIST"
TMP_PKG="$DIST/.component-${ID}-${VER}.tar.gz"

tar -czf "$TMP_PKG" -C "$STAGE" . || { echo "::error title=$ID 打包失败"; exit 1; }

SHA="$(sha256sum "$TMP_PKG" | cut -c1-12)"
NAME="component-${ID}-${VER}-${SHA}-android-arm64.tar.gz"
mv -f "$TMP_PKG" "$DIST/$NAME"
sha256sum "$DIST/$NAME" | cut -d' ' -f1 > "$DIST/$NAME.sha256"

echo "[ok] $DIST/$NAME"
echo "[ok] entry=$ENTRY  文件=$(find "$STAGE" -type f | wc -l)"
