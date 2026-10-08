#!/usr/bin/env bash
set -uo pipefail
cd "$(cd "$(dirname "$0")/../.." && pwd)"
ABI="${ABI:-arm64-v8a}"
PIN=".github/native-capabilities-pin.json"
CAPS=".github/native-capabilities.txt"
JNI="container/app/src/main/jniLibs/$ABI"

# 逐件编译 —— **一件一个脚本**，各自产 component-meta.json + 落到 usr/lib/<id>/<版本>/。
#
# 此前这里是 exec build-native-capabilities.sh：一个脚本编 11 件，
# 按 CAPS 里的 self-c/upstream/soft 三档分别处理。那是「能力件」时代的做法
# （三档是我加的，Linux 里对应 Essential: yes/no）—— 那个概念早删了，
# 必需与否现在在 component-meta.json 的 required 字段里。
# 现在按件走，与 ldconfig 扫目录一样，一件一件来。
build_each() {
  local failed=0
  for s in \
      build-piece-flock.sh \
      build-piece-posix.sh \
      build-piece-ptyprobe.sh \
      build-piece-ptysession.sh \
      build-piece-zlib.sh \
      build-piece-openssl.sh \
      build-piece-curl.sh \
      build-piece-jq.sh \
      build-piece-bash.sh \
      build-piece-rg.sh \
      build-native-busybox.sh
  do
    # crypto 不在此列 —— 它与 libssl.so 一并编出（OpenSSL 的一部分，见
    # component-sources.json的 crypto.sameAs），由 build-piece-openssl.sh 落位。
    echo "── $s"
    if ! bash "scripts/recipes/$s"; then
      echo "[caps] ★ $s 失败"
      failed=1
    fi
  done
  [ "$failed" = 0 ] || { echo "::error title=有件编不出来::见上方各脚本的取证输出"; exit 1; }
  echo "[caps] 逐件编译完成"
}

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
  echo "[caps] 该指纹未固化 —— 回退逐件编译（跑 Pin native capabilities 可固化它）"
  rm -f "$ENVF"
build_each
exit $?
fi
# shellcheck disable=SC1090
set -a; . "$ENVF"; set +a
rm -f "$ENVF"
echo "[caps] 命中固化：$TAG  ($ZIP)"

[ -n "${GITHUB_REPOSITORY:-}" ] || {
  echo "[error] 环境里没有 GITHUB_REPOSITORY，不知道该去哪取 —— 回退逐件编译。" >&2
  build_each
  exit $?
}
D="$(mktemp -d)"
if ! gh release download "$TAG" -p "$ZIP" -D "$D" --repo "$GITHUB_REPOSITORY" --clobber; then
  echo "[warn] 下载 $TAG/$ZIP 失败 —— 回退逐件编译。"
build_each
exit $?
fi
if ! echo "$SHA  $D/$ZIP" | sha256sum -c - >/dev/null 2>&1; then
  echo "[warn] $ZIP 的 sha256 与固化记录不符 —— 回退逐件编译（不取用可疑产物）。"
build_each
exit $?
fi
echo "[caps] sha256 校验通过"

if ! unzip -o -q "$D/$ZIP" -d "$D/x"; then
  echo "[warn] 解包失败 —— 回退逐件编译。"
  build_each
  exit $?
fi
mkdir -p "$JNI"
MISSING=""
N=0
while read -r TIER LIB _ID; do
  case "$TIER" in ''|'#'*) continue ;; esac
  if [ -f "$D/x/$LIB" ]; then cp "$D/x/$LIB" "$JNI/$LIB"; N=$((N + 1)); else MISSING="$MISSING $LIB"; fi
  # 说明随件取用 —— 内核靠 *.meta.json 知道这是什么（deb-control 的做法）
  [ -f "$D/x/$LIB.meta.json" ] && cp "$D/x/$LIB.meta.json" "$JNI/"
done < "$CAPS"
if [ -n "$MISSING" ]; then
  echo "[warn] 固化包里缺件:$MISSING —— 回退逐件编译（不取用不完整的固化）。"
build_each
exit $?
fi
echo "[ok] 已取用固化产物 $N 件（**未重新编译**）"
