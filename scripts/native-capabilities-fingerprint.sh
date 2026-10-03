#!/usr/bin/env bash
set -euo pipefail
cd "$(cd "$(dirname "$0")/.." && pwd)"

{
  find container/native -type f | sort
  echo scripts/build-native-capabilities.sh
} | while read -r f; do
  [ -f "$f" ] && sha256sum "$f"
done | sha256sum | cut -d' ' -f1
