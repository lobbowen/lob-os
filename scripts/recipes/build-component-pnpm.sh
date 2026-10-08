#!/usr/bin/env bash
set -euo pipefail

HERE=$(dirname "$0")
cd "$HERE/../.."
ROOT_DIR=$(pwd)

PNPM_VERSION=12.7.0
TARBALL_SHA512_B64=gJTCsUbazAEbIMF9l2t+z3YWHCI0AiThtBsxU6FrxXK0tZsBRZZWpleLs0uY8Dy6V0RcPEusn3UyuAyFn3dkeA==
ELF_SHA256=ce0b5e064552f60ec5b153d767b464f8d64f7659dbc2c58780679ac7e5bdfe78
ELF_SIZE=47033992
TARBALL=https://registry.npmjs.org/@pnpm/exe.android-arm64/-/exe.android-arm64-${PNPM_VERSION}.tgz

if [ "${#TARBALL_SHA512_B64}" != 88 ]; then
  echo "::error title=钉本身不合法::sha512 的 base64 应为 88 字，实为 ${#TARBALL_SHA512_B64} 字"
  exit 1
fi
case "$TARBALL_SHA512_B64" in
  *==) : ;;
  *) echo "::error title=钉本身不合法::sha512 的 base64 应以 == 结尾（64 字节 %3==1），实为 ${TARBALL_SHA512_B64: -2}"; exit 1 ;;
esac
if [ "${#ELF_SHA256}" != 64 ]; then
  echo "::error title=钉本身不合法::sha256 十六进制应为 64 字，实为 ${#ELF_SHA256} 字"
  exit 1
fi

OUT="${OUT:-dist}"
mkdir -p "$ROOT_DIR/$OUT/bin" "$ROOT_DIR/work"

echo "[pnpm] 取上游 android-arm64 变体 $TARBALL"
if ! curl -fsSL "$TARBALL" -o "$ROOT_DIR/work/pnpm-exe.tgz"; then
  echo "::error title=取不到上游件::$TARBALL"
  exit 1
fi

GOT512=$(openssl dgst -sha512 -binary "$ROOT_DIR/work/pnpm-exe.tgz" | openssl base64 -A)
if [ "$GOT512" != "$TARBALL_SHA512_B64" ]; then
  echo "::error title=tarball sha512 不符::实取 $GOT512 ≠ 钉住的 $TARBALL_SHA512_B64（上游内容变了，升级要主动改这里）"
  exit 1
fi
echo "[pnpm] tarball $(stat -c%s "$ROOT_DIR/work/pnpm-exe.tgz") 字节，sha512 与上游 packument 一致"

rm -rf "$ROOT_DIR/work/pnpm" && mkdir -p "$ROOT_DIR/work/pnpm"
tar xzf "$ROOT_DIR/work/pnpm-exe.tgz" -C "$ROOT_DIR/work/pnpm" --strip-components=1
SRC="$ROOT_DIR/work/pnpm/pnpm"
if [ ! -f "$SRC" ]; then
  echo "::error title=件里找不到 ELF 真身::期望 package/pnpm（上游改包结构了？）；实际内容如下"
  ls -la "$ROOT_DIR/work/pnpm" || true
  exit 1
fi

GOT256=$(sha256sum "$SRC" | cut -d' ' -f1)
if [ "$GOT256" != "$ELF_SHA256" ]; then
  echo "::error title=ELF sha256 不符::实取 $GOT256 ≠ 钉住的 $ELF_SHA256"
  exit 1
fi
SIZE=$(stat -c%s "$SRC")
if [ "$SIZE" != "$ELF_SIZE" ]; then
  echo "::error title=ELF 尺寸不符::$SIZE ≠ $ELF_SIZE"
  exit 1
fi
MAGIC=$(head -c 4 "$SRC" | od -An -tx1 | tr -d ' \n')
if [ "$MAGIC" != "7f454c46" ]; then
  echo "::error title=真身不是 ELF::前四字节 $MAGIC"
  exit 1
fi

cp "$SRC" "$ROOT_DIR/$OUT/bin/pnpm"
chmod 0755 "$ROOT_DIR/$OUT/bin/pnpm"
printf '%s\n' "$PNPM_VERSION" > "$ROOT_DIR/$OUT/pnpm.version"
echo "[pnpm] 产出 $ROOT_DIR/$OUT/bin/pnpm（$SIZE 字节，sha256 $GOT256）"
