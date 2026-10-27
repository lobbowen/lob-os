#!/usr/bin/env bash
# 那 M1 判据第一段逐字折成那主机侧那一半的**采集层**的**入口**。
#
# 按 C-103：=> 而那**两边**逐字**各**答**一件**事**、=> 而而那**处置**逐字**不合并**。
#   => 而那 scripts/piece-criteria.json **那个**模块**那一族**逐字判**那用户态模块**按裸名调起并读回真实输出。
#   => 而这一族逐字判**那产物**的形状与**那载体**在位。
#   => 而而那**它们**逐字**不合并**（=> 而按 C-104）。
#
# 按 C-105：那**缺**的逐字**报缺**，不猜填。
# 用法：run-m1-hostside.sh [那产物**那一族**的落点]
set -euo pipefail
export LC_ALL=C

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

command -v node >/dev/null 2>&1 || { echo "报缺：那宿主上逐字**没有**那个 node（=> 而那判据逐字**要**它**才**跑得动）"; exit 2; }
command -v cpio >/dev/null 2>&1 || { echo "报缺：那宿主上逐字**没有**那个 cpio（=> 而那归档**那一族**逐字**要**它**才**读得动**）"; exit 2; }

export LOBOS_ROOT="$ROOT"
export LOBOS_KERNEL_IMAGE_DIR="${1:-$ROOT/work/kernel-image}"
export M1_OUT="${M1_OUT:-$LOBOS_KERNEL_IMAGE_DIR/m1-hostside-readings.json}"

exec node "$HERE/run-m1-hostside.js"
