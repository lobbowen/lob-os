#!/usr/bin/env bash
# 那 M0 那一族（=> 而那启动链）的**采集层** —— 逐条把
# scripts/m0-criteria.json 里面的机器可执行判据跑一遍。
#
# 按 C-103：=> 而那**两边**逐字**各**答**一件**事**、=> 而而那**处置**逐字**不合并**。
#   => 而那 M0 那一族逐字判**那启动链**（=> 而那内核镜像、=> 而那 initramfs 那一层、=> 而那 /init 那一层）。
#   => 而那 M1 判据本体那用户态那一族逐字判**那计时基准**与**那零启动失败**。
#
# 按 C-104：=> 而这一族与那主机侧那四格逐字**不是**那同一条内容的两条取法、=> 而而那**处置**逐字**不合并**。
# 按 C-105：那**缺**的逐字**报缺**，不猜填。
# 用法：run-m0-criteria.sh [那产物**那一族**的落点]
set -euo pipefail
export LC_ALL=C

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

command -v node >/dev/null 2>&1 || { echo "报缺：那宿主上逐字**没有**那个 node（=> 而那判据逐字**要**它**才**跑得动）"; exit 2; }
command -v cpio >/dev/null 2>&1 || { echo "报缺：那宿主上逐字**没有**那个 cpio（=> 而那归档**那一族**逐字**要**它**才**读得动**）"; exit 2; }

export LOBOS_ROOT="$ROOT"
export M0_IMAGE_DIR="${1:-$ROOT/work/kernel-image}"
export M0_OUT="${M0_OUT:-$ROOT/work/m0-readings.json}"

exec node "$HERE/run-m0-criteria.js"
