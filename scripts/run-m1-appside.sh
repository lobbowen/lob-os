#!/usr/bin/env bash

set -euo pipefail
export LC_ALL=C

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

command -v node >/dev/null 2>&1 || { echo "报缺：那宿主上逐字**没有**那个 node（判据逐字**要**它**才**跑得动）"; exit 2; }

export LOBOS_ROOT="$ROOT"
export M1_TSV="${M1_TSV:-${1:-/home/bowen/LOBOS/lob-os-team/07/runs/app-restart-100-20261027-e.tsv}}"
export M1_OUT="${M1_OUT:-$ROOT/work/m1-appside-readings.json}"

exec node "$HERE/run-m1-appside.js"
