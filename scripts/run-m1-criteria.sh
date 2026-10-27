#!/usr/bin/env bash
# 那 M1 判据第一段逐字折成那两半的**统一采集入口** —— 把那**两个**采集逐字**并**成**一次**。
#
# 按 C-103：=> 而那**两边**逐字**各**答**一件**事**、=> 而而那**处置**逐字**不合并**。
#   => 而那主机侧那四格逐字判**那产物**的形状与**那载体**在位。
#   => 而那应用内那两格逐字判**那用户态**那一族的**计时**与**那重启**。
#   => 而而那**它们**逐字**是**那**两**件**事**、=> 而按 C-104 逐字**不合并**。
#
# 按 C-115：=> 而那**两个**采集逐字**各**记**那自己的**时刻**、=> 而而那**它们**逐字**不互相顶替**。
#   => 而而那**这一趟**逐字**记**一个**共同**的**时刻**（=> 而那**两边**的**读数**逐字**对**那**一个**时刻**）。
#
# 按 C-105：那**缺**的逐字**报缺**，不猜填。
# 用法：run-m1-criteria.sh [那产物**那一族**的落点] [那设备侧**读数**的落点]
set -euo pipefail
export LC_ALL=C

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

DIR="${1:-$ROOT/work/kernel-image}"
TSV="${2:-/home/bowen/LOBOS/lob-os-team/07/runs/app-restart-100-20261027-e.tsv}"
OUT="${M1_OUT:-$ROOT/work/m1-readings.json}"

command -v node >/dev/null 2>&1 || { echo "报缺：那宿主上逐字**没有**那个 node（=> 而那判据逐字**要**它**才**跑得动）"; exit 2; }

export LOBOS_ROOT="$ROOT"
export LOBOS_KERNEL_IMAGE_DIR="$DIR"
export M1_TSV="$TSV"

#  => 而那**这一趟**的**时刻**逐字**先**记**下来**（=> 而那**两边**的**读数**逐字**对**它**）。
TAKEN_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "[m1] 这一趟的时刻：$TAKEN_AT"

HOST_OUT="$(mktemp)"
APP_OUT="$(mktemp)"
trap 'rm -f "$HOST_OUT" "$APP_OUT"' EXIT

echo "[m1] 主机侧那四格（=> 而那产物**那一族**的形状与那载体在位）"
M1_OUT="$HOST_OUT" node "$HERE/run-m1-hostside.js" || true
echo "[m1] 应用内那两格（=> 而那用户态**那一族**的计时与那重启）"
M1_OUT="$APP_OUT" node "$HERE/run-m1-appside.js" || true

echo "[m1] 合并两边的读数"
M1_OUT="$OUT" M1_HOST_OUT="$HOST_OUT" M1_APP_OUT="$APP_OUT" M1_TAKEN_AT="$TAKEN_AT" node -e "
const fs=require('node:fs');
const h=JSON.parse(fs.readFileSync(process.env.M1_HOST_OUT,'utf8'));
const a=JSON.parse(fs.readFileSync(process.env.M1_APP_OUT,'utf8'));
const out={};
for(const k of Object.keys(h)) if(k!=='\$summary') out['hostside:'+k]=h[k];
for(const k of Object.keys(a)) if(k!=='\$summary') out['appside:'+k]=a[k];
const hp=h['\$summary']||{},ap=a['\$summary']||{};
out['\$summary']={hostside:{passed:hp.passed||0,total:hp.total||0},appside:{passed:ap.passed||0,total:ap.total||0},takenAt:process.env.M1_TAKEN_AT};
fs.mkdirSync(require('node:path').dirname(process.env.M1_OUT),{recursive:true});
fs.writeFileSync(process.env.M1_OUT,JSON.stringify(out,null,2)+String.fromCharCode(10));
const P=(hp.passed||0)+(ap.passed||0),N=(hp.total||0)+(ap.total||0);
console.log('[m1] 判据 '+P+'/'+N+'　取数时刻 '+process.env.M1_TAKEN_AT);
process.exit(P===N?0:1);
"
