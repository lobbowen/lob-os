#!/usr/bin/env bash
set -uo pipefail
cd "$(cd "$(dirname "$0")/../.." && pwd)"
ABI="${ABI:-arm64-v8a}"
PIN=".github/native-capabilities-pin.json"
CAPS=".github/native-capabilities.txt"
JNI="container/app/src/main/jniLibs/$ABI"

FP="$(bash scripts/verify/native-capabilities-fingerprint.sh)"
echo "[caps] 源指纹 = $FP"

ENVF="$(mktemp)"
if ! node -e '
  const fs = require("fs");
  const [pin, fp, out] = process.argv.slice(1);
  let j = {};
  try { j = JSON.parse(fs.readFileSync(pin, "utf8")); } catch { process.exit(3); }
  const e = (j.pins || {})[fp];
  if (!e || !e.tag || !e.zip || !e.sha256) process.exit(3);
  fs.appendFileSync(out, "TAG=" + e.tag + String.fromCharCode(10) + "ZIP=" + e.zip + String.fromCharCode(10) + "SHA=" + e.sha256 + String.fromCharCode(10));
' "$PIN" "$FP" "$ENVF"; then
  echo "[caps] 该指纹未固化 —— 回退现场编译（跑 Pin native capabilities 可固化它）"
  rm -f "$ENVF"
  exec bash scripts/recipes/build-native-capabilities.sh
fi
# shellcheck disable=SC1090
set -a; . "$ENVF"; set +a
rm -f "$ENVF"
echo "[caps] 命中固化：$TAG  ($ZIP)"

[ -n "${GITHUB_REPOSITORY:-}" ] || { echo "[error] 环境里没有 GITHUB_REPOSITORY，不知道该去哪取 —— 回退现场编译。" >&2; exec bash scripts/recipes/build-native-capabilities.sh; }
D="$(mktemp -d)"
if ! gh release download "$TAG" -p "$ZIP" -D "$D" --repo "$GITHUB_REPOSITORY" --clobber; then
  echo "[warn] 下载 $TAG/$ZIP 失败 —— 回退现场编译。"
  exec bash scripts/recipes/build-native-capabilities.sh
fi
if ! echo "$SHA  $D/$ZIP" | sha256sum -c - >/dev/null 2>&1; then
  echo "[warn] $ZIP 的 sha256 与固化记录不符 —— 回退现场编译（不取用可疑产物）。"
  exec bash scripts/recipes/build-native-capabilities.sh
fi
echo "[caps] sha256 校验通过"

unzip -o -q "$D/$ZIP" -d "$D/x" || { echo "[warn] 解包失败 —— 回退现场编译。"; exec bash scripts/recipes/build-native-capabilities.sh; }
mkdir -p "$JNI"
MISSING=""
N=0
while read -r TIER LIB _ID; do
  case "$TIER" in ''|'#'*) continue ;; esac
  if [ -f "$D/x/$LIB" ]; then cp "$D/x/$LIB" "$JNI/$LIB"; N=$((N + 1)); else MISSING="$MISSING $LIB"; fi
done < "$CAPS"
if [ -n "$MISSING" ]; then
  echo "[warn] 固化包里缺件:$MISSING —— 回退现场编译（不取用不完整的固化）。"
  exec bash scripts/recipes/build-native-capabilities.sh
fi
echo "[ok] 已取用固化产物 $N 件（**未重新编译**）"
