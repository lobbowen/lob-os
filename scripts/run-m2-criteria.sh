#!/usr/bin/env bash
# 那 M2 那一族（=> 而那 rootfs **那一层**）的**采集层** —— 逐条把
# scripts/m2-criteria.json 里面的机器可执行判据跑一遍。
#
# 按 C-103：=> 而那**两边**逐字**各**答**一件**事**、=> 而而那**处置**逐字**不合并**。
#   => 而那 rootfsReadonly 那一格逐字**判**那**系统层**只读**、
#   => 而那 rootfsWritable 那一格逐字**判**那**工作区**可写**。
#
# 按 C-104：=> 而那**两**格逐字**不能由同一次读取**一起答**、=> 而而那**处置**逐字**不合并**。
# 按 C-105：那**缺**的逐字**报缺**，不猜填。
# 用法：run-m2-criteria.sh [那 rootfs **那一件**的落点]
set -euo pipefail
export LC_ALL=C

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

command -v node >/dev/null 2>&1 || { echo "报缺：那宿主上逐字**没有**那个 node（=> 而那判据逐字**要**它**才**跑得动）"; exit 2; }

export LOBOS_ROOT="$ROOT"
export M2_ROOTFS_DIR="${1:-$ROOT/work/rootfs}"
export M2_OUT="${M2_OUT:-$ROOT/work/m2-readings.json}"

exec node "$HERE/run-m2-criteria.js"
