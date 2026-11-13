#!/usr/bin/env bash

set -euo pipefail
export LC_ALL=C

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

command -v node >/dev/null 2>&1 || { echo "报缺：那宿主上逐字**没有**那个 node（判据逐字**要**它**才**跑得动）"; exit 2; }

export LOBOS_ROOT="$ROOT"
export M2_ROOTFS_DIR="${1:-$ROOT/work/rootfs}"
export M2_OUT="${M2_OUT:-$ROOT/work/m2-readings.json}"

exec node "$HERE/run-m2-criteria.js"
