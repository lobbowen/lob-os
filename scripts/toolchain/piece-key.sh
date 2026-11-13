#!/usr/bin/env bash

set -euo pipefail

HERE="$(dirname "$0")"
ROOT_DIR="$(cd "$HERE/../.." && pwd)"

id="${1:?usage: piece-key.sh <件名>  （component-sources.json 里的 id）}"
work="${2:-$(pwd)}"

run() {
  local k v
  k="$(bash "$ROOT_DIR/scripts/toolchain/cache-key.sh" "$1" "$id")" || {
    echo "::error title=$id 算不出 $1::cache-key.sh 报：$k"
    exit 1
  }
  echo "$k"
}

{
  echo "key=$(run key)"
  echo "tag=$(run tag)"
  echo "asset=$(run asset)"
} | tee -a "$GITHUB_OUTPUT"

echo "[key] $id → tag=$(run tag)  asset=$(run asset)"