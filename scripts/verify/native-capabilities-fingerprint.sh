#!/usr/bin/env bash
set -euo pipefail
cd "$(cd "$(dirname "$0")/.." && pwd)"

{
  find container/native -type f | sort
  echo scripts/recipes/build-native-capabilities.sh
  echo scripts/toolchain/build-base-libs.sh
  echo scripts/bionic-compat.c
  echo scripts/verify/verify-component-build-date.sh
  echo scripts/component-sources.json
} | while read -r f; do
  if [ ! -f "$f" ]; then
    echo "[error] 指纹输入缺失: $f —— 该文件被删/改名会让固化悄悄沿用旧产物。" >&2
    exit 1
  fi
  sha256sum "$f"
done | sha256sum | cut -d' ' -f1