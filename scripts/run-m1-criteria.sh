#!/usr/bin/env bash

set -euo pipefail
export LC_ALL=C

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

DIR="${1:-$ROOT/work/kernel-image}"
TSV="${2:-/home/bowen/LOBOS/lob-os-team/07/runs/app-restart-100-20261027-e.tsv}"
OUT="${M1_OUT:-$ROOT/work/m1-readings.json}"

command -v node >/dev/null 2>&1 || { echo "报缺：那宿主上逐字**没有**那个 node（判据逐字**要**它**才**跑得动）"; exit 2; }

export LOBOS_ROOT="$ROOT"
export LOBOS_KERNEL_IMAGE_DIR="$DIR"
export M1_TSV="$TSV"

TAKEN_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "[m1] 这一趟的时刻：$TAKEN_AT"

HOST_OUT="$(mktemp)"
APP_OUT="$(mktemp)"
trap 'rm -f "$HOST_OUT" "$APP_OUT"' EXIT

echo "[m1] 主机侧那四格（产物**那一族**的形状与那载体在位）"
M1_OUT="$HOST_OUT" node "$HERE/run-m1-hostside.js" || true
echo "[m1] 应用内那两格（用户态**那一族**的计时与那重启）"
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
