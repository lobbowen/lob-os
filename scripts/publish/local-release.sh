#!/usr/bin/env bash
# 本地发布：构建件 -> 打包 -> 签名清单 -> 上传七牛。不经 GitHub。
# 用法: bash scripts/publish/local-release.sh [--revision N] [--all]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

QINIU_ENV="${HOME}/.config/lobos-qiniu.env"
[ -f "$QINIU_ENV" ] && source "$QINIU_ENV"

NDK="${NDK:-/home/bowen/ndk-acquire/android-ndk-r30}"
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
export CC="$CC:-$TC/aarch64-linux-android35-clang"
export CXX="${CXX:-$TC/aarch64-linux-android35-clang++}"

PRIVATE_KEY="${LOBOS_PRIVATE_KEY:-/home/bowen/LOBOS/keys-offline-backup/ota-private.pem}"
PYTHON_311="${PYTHON_311:-/home/bowen/.local/share/uv/python/cpython-3.11-linux-x86_64-gnu/bin/python3.11}"
PYTHON_312="${PYTHON_312:-/home/bowen/.local/share/uv/python/cpython-3.12-linux-x86_64-gnu/bin/python3.12}"

QINIU_AK="${QINIU_AK:-}"
QINIU_SK="${QINIU_SK:-}"
QINIU_BUCKET="${QINIU_BUCKET:-lobos}"
QINIU_PUBLIC_BASE="${QINIU_PUBLIC_BASE:-https://lobcdn.zll.ink}"
CHANNEL="${CHANNEL:-canary}"
BASE_URL="${QINIU_PUBLIC_BASE}"

REV_FILE="work/local-revision"
ALL=0
REV=""
while [ $# -gt 0 ]; do
  case "$1" in
    --revision) REV="$2"; shift 2 ;;
    --all) ALL=1; shift ;;
    *) echo "未知参数: $1"; exit 2 ;;
  esac
done

die() { echo "::error title=$1::${2:-}"; exit 1; }
note() { echo "[local-release] $*"; }

for f in "$PRIVATE_KEY" "$CC" "$PYTHON_311" "$PYTHON_312"; do
  [ -e "$f" ] || die "前置缺失" "$f 不在"
done
[ -f "$PRIVATE_KEY" ] || die "私钥缺失" "$PRIVATE_KEY"
[ -n "$QINIU_AK" ] && [ -n "$QINIU_SK" ] || die "七牛凭证缺失" "先 export QINIU_AK / QINIU_SK（或写入 ~/.config/lobos-qiniu.env 并 source）"

if [ -z "$REV" ]; then
  REV=$(cat "$REV_FILE" 2>/dev/null || echo 20)
  REV=$((REV + 1))
fi
echo "$REV" > "$REV_FILE"
note "本轮 revision=$REV channel=$CHANNEL"

mkdir -p release work

build_one() {
  local tool="$1" recipe="$2"
  note "构建 $tool (recipe=$recipe)"
  bash "scripts/recipes/$recipe"
  note "打包 $tool (PKG_NAME=$tool)"
  PKG_NAME="$tool" bash scripts/recipes/package-component.sh "$tool"
}

build_python_versions() {
  note "构建 python 3.14.8"
  OUT=dist bash scripts/recipes/build-component-python3.sh
  PKG_NAME=python OUT=dist bash scripts/recipes/package-component.sh python

  note "构建 python 3.12.10"
  PY_SLOT=3.12 PY_VERSION=3.12.10 BUILD_PYTHON="$PYTHON_312" OUT=dist \
    bash scripts/recipes/build-component-python3.sh
  PKG_NAME=python OUT=dist bash scripts/recipes/package-component.sh python-3.12

  note "构建 python 3.11.13"
  PY_SLOT=3.11 PY_VERSION=3.11.13 BUILD_PYTHON="$PYTHON_311" OUT=dist \
    bash scripts/recipes/build-component-python3.sh
  PKG_NAME=python OUT=dist bash scripts/recipes/package-component.sh python-3.11
}

note "清理 dist"
rm -f dist/component-*.zip

fetch_rest_from_cdn() {
  note "增量模式：从 CDN 拉取现有件补齐 dist/（本地 14ms）"
  local man="work/online-manifest.json"
  curl -sS -m 60 "$BASE_URL/component-$CHANNEL/component-manifest.json?t=$(date +%s)" -o "$man" \
    || die "拉线上清单失败" "$BASE_URL"
  python3 - "$man" <<'PY'
import json, sys, urllib.request, os
d = json.load(open(sys.argv[1]))
base = "https://lobcdn.zll.ink/component/"
for t in d.get("tools", []):
    for v in (t.get("versions") or [t]):
        url = v.get("url", "")
        name = url.rsplit("/", 1)[-1]
        if not name: continue
        dest = os.path.join("dist", name)
        if os.path.exists(dest):
            continue
        urllib.request.urlretrieve(url, dest)
        print("[local-release] 取回 " + name)
PY
}

if [ "$ALL" = "1" ]; then
  note "全量构建所有组件件"
  build_one cmake build-component-cmake.sh
  build_one git build-component-git.sh
  build_one make build-component-make.sh
  build_one node build-component-node.sh
  build_one npm build-component-npm.sh
  build_one pnpm build-component-pnpm.sh
  build_one pkgconf build-component-pkg-config.sh
  build_one sqlite build-component-sqlite3.sh
  build_python_versions
else
  fetch_rest_from_cdn
  note "增量构建 python 三版本（含 3.14，其他件沿用 CDN 字节）"
  build_python_versions
fi

note "清单投影与对账"
node scripts/publish/publish-component-manifest.js dist --project > work/declared.json
node scripts/publish/publish-component-manifest.js dist --declared-only > work/declared-side.json

note "签名清单 revision=$REV"
LOBOS_COMPONENT_REVISION="$REV" \
  node scripts/publish/publish-component-manifest.js dist release "$PRIVATE_KEY" "$CHANNEL"
LOBOS_COMPONENT_REVISION="$REV" node scripts/publish/publish-native-manifest.js "" release "$PRIVATE_KEY" "$CHANNEL"

note "上传件包到七牛"
export QINIU_AK QINIU_SK QINIU_BUCKET QINIU_PUBLIC_BASE
for f in dist/component-*.zip; do
  [ -f "$f" ] || continue
  note "上传 $(basename "$f")"
  node scripts/publish/upload-qiniu.js "$f" "component/$(basename "$f")" --cache-control=31536000
done

note "上传清单到七牛"
node scripts/publish/upload-qiniu.js release/component-manifest.json \
  "component-$CHANNEL/component-manifest.json" --cache-control=60
node scripts/publish/upload-qiniu.js release/component-manifest.json.sig \
  "component-$CHANNEL/component-manifest.json.sig" --cache-control=60
node scripts/publish/upload-qiniu.js release/native-manifest.json \
  "component-$CHANNEL/native-manifest.json" --cache-control=60
node scripts/publish/upload-qiniu.js release/native-manifest.json.sig \
  "component-$CHANNEL/native-manifest.json.sig" --cache-control=60

note "完成：revision=$REV 已发布到 $BASE_URL/component-$CHANNEL/"
