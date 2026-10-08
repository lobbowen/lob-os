#!/usr/bin/env bash
set -euo pipefail

HERE=$(dirname "$0")
cd "$HERE/../.."
ROOT_DIR=$(pwd)

NPM_VERSION=$(bash scripts/registry/read-node-versions.sh npm)
TARBALL_SHA512_B64=SDd/hHg3KqHE5Ht2NHWxNYNtqCQ2pXAPLl6OtQhPyED5PHsRfrOtO199MZTIG2cQoQ1ZRI9t28shrD+2cr3AAw==
ENTRY_SHA256=8e5f6f3429f8cdbe693cdc29904e9d5a7b127a494bd15c804bd54c7403bfcbe7
ENTRY_SIZE=54
TARBALL=https://registry.npmjs.org/npm/-/npm-${NPM_VERSION}.tgz

if [ "${#TARBALL_SHA512_B64}" != 88 ]; then
  echo "::error title=钉本身不合法::sha512 的 base64 应为 88 字，实为 ${#TARBALL_SHA512_B64} 字"
  exit 1
fi
case "$TARBALL_SHA512_B64" in
  *==) : ;;
  *) echo "::error title=钉本身不合法::sha512 的 base64 应以 == 结尾（64 字节 %3==1），实为 ${TARBALL_SHA512_B64: -2}"; exit 1 ;;
esac
if [ "${#ENTRY_SHA256}" != 64 ]; then
  echo "::error title=钉本身不合法::sha256 十六进制应为 64 字，实为 ${#ENTRY_SHA256} 字"
  exit 1
fi

OUT="${OUT:-dist}"
mkdir -p "$ROOT_DIR/$OUT" "$ROOT_DIR/work"

echo "[npm] 取上游 tarball $TARBALL"
if ! curl -fsSL "$TARBALL" -o "$ROOT_DIR/work/npm-${NPM_VERSION}.tgz"; then
  echo "::error title=取不到上游件::$TARBALL"
  exit 1
fi

GOT512=$(openssl dgst -sha512 -binary "$ROOT_DIR/work/npm-${NPM_VERSION}.tgz" | openssl base64 -A)
if [ "$GOT512" != "$TARBALL_SHA512_B64" ]; then
  echo "::error title=tarball sha512 不符::实取 $GOT512 ≠ 钉住的 $TARBALL_SHA512_B64（上游内容变了，升级要主动改这里）"
  exit 1
fi
TB_BYTES=$(stat -c%s "$ROOT_DIR/work/npm-${NPM_VERSION}.tgz")
echo "[npm] tarball $TB_BYTES 字节，sha512 与上游 packument 一致"

rm -rf "$ROOT_DIR/work/npm" && mkdir -p "$ROOT_DIR/work/npm"
tar xzf "$ROOT_DIR/work/npm-${NPM_VERSION}.tgz" -C "$ROOT_DIR/work/npm" --strip-components=1

rm -f "$ROOT_DIR/work/npm/bin/npm" "$ROOT_DIR/work/npm/bin/npx"
find "$ROOT_DIR/work/npm" -type f \( -name '*.cmd' -o -name '*.ps1' \) -delete

SRC="$ROOT_DIR/work/npm/bin/npm-cli.js"
if [ ! -f "$SRC" ]; then
  echo "::error title=件里找不到入口::期望 bin/npm-cli.js（上游改包结构了？）；bin 下实际内容如下"
  ls -la "$ROOT_DIR/work/npm/bin" || true
  exit 1
fi
GOT256=$(sha256sum "$SRC" | cut -d' ' -f1)
if [ "$GOT256" != "$ENTRY_SHA256" ]; then
  echo "::error title=入口 sha256 不符::实取 $GOT256 ≠ 钉住的 $ENTRY_SHA256"
  exit 1
fi
ESIZE=$(stat -c%s "$SRC")
if [ "$ESIZE" != "$ENTRY_SIZE" ]; then
  echo "::error title=入口尺寸不符::$ESIZE ≠ $ENTRY_SIZE"
  exit 1
fi
HEAD2=$(head -c 2 "$SRC")
if [ "$HEAD2" != "#!" ]; then
  echo "::error title=入口没有 shebang::前二字节不是 #!，按裸名调用不可能被解释器接住"
  exit 1
fi

cp -a "$ROOT_DIR/work/npm/." "$ROOT_DIR/$OUT/"
chmod 0755 "$ROOT_DIR/$OUT/bin/npm-cli.js" "$ROOT_DIR/$OUT/bin/npx-cli.js" "$ROOT_DIR/$OUT/bin/npm-prefix.js"
printf '%s\n' "$NPM_VERSION" > "$ROOT_DIR/$OUT/npm.version"
TREE_BYTES=$(du -sb "$ROOT_DIR/$OUT" | cut -f1)
echo "[npm] 产出 $TREE_BYTES 字节的树，入口 $ESIZE 字节 sha256 $GOT256"
