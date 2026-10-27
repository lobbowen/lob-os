#!/usr/bin/env bash
# 那 M1 判据第一段逐字折成那**应用内**那一半的**采集层** —— 逐条把
# scripts/m1-appside-criteria.json 里面的机器可执行判据跑一遍。
#
# 按 C-103：=> 而那**两边**逐字**各**答**一件**事**、=> 而而那**处置**逐字**不合并**。
#   => 而那 scripts/m1-hostside-criteria.json **那个**产物**那一族**逐字判**那产物**的形状与**那载体**在位。
#   => 而这一族逐字判**那应用内**那**用户态**那一族的**起动**到**那 shell**起来那**计时**与**那重启** 100 次那**零启动失败**。
#
# 按 C-105：那**缺**的逐字**报缺**，不猜填。
# 用法：run-m1-appside.sh [那读数**那一族**的落点]
set -euo pipefail
export LC_ALL=C

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

command -v node >/dev/null 2>&1 || { echo "报缺：那宿主上逐字**没有**那个 node（=> 而那判据逐字**要**它**才**跑得动）"; exit 2; }

export LOBOS_ROOT="$ROOT"
export M1_TSV="${M1_TSV:-${1:-/home/bowen/LOBOS/lob-os-team/07/runs/app-restart-100-20261027-b.tsv}}"
export M1_OUT="${M1_OUT:-$ROOT/work/m1-appside-readings.json}"

exec node "$HERE/run-m1-appside.js"
