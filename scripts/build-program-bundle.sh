#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="${1:?用法: build-program-bundle.sh <program-src-dir> <version> [abi] [url-base]}"
VER="${2:?缺少 version 参数}"
ABI="${3:-node24-arm64-android35}"
URL_BASE="${4:-}"

if [ ! -f "$ROOT/keys/ota-private.pem" ]; then
  echo "[build-program-bundle] 私钥缺失: $ROOT/keys/ota-private.pem（先跑 ./scripts/keygen.sh 或注入 CI secret）" >&2
  exit 1
fi

bash "$ROOT/scripts/verify-ota-anchor.sh" --private "$ROOT/keys/ota-private.pem"

exec node "$ROOT/scripts/build-program-bundle.js" "$SRC" "$VER" "$ABI" "$URL_BASE"
