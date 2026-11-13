#!/usr/bin/env bash

set -euo pipefail
export LC_ALL=C

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

command -v node >/dev/null 2>&1 || { echo "报缺：那宿主上逐字**没有**那个 node（判据逐字**要**它**才**跑得动）"; exit 2; }
command -v cpio >/dev/null 2>&1 || { echo "报缺：那宿主上逐字**没有**那个 cpio（归档**那一族**逐字**要**它**才**读得动**）"; exit 2; }

export LOBOS_ROOT="$ROOT"
export LOBOS_KERNEL_IMAGE_DIR="${1:-$ROOT/work/kernel-image}"
export M1_OUT="${M1_OUT:-$LOBOS_KERNEL_IMAGE_DIR/m1-hostside-readings.json}"

exec node "$HERE/run-m1-hostside.js"
