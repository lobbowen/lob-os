#!/usr/bin/env bash
# M0 内核可信起点清单的核对器 —— 逐条重算那份清单，逐条对出处。
#
# 用法：
#   check-m0-kernel-baseline.sh [素材目录]
# 素材目录默认是 /home/bowen/LOBOS/work（那 gki_defconfig 与那 kconfig 放的地方）。
#
# 判据：
#   一、那份清单逐字与那仓内那份 gki_defconfig 逐字对得上。
#   二、那清单里没有一条的出处是猜的（=> 而那每一行号都要能读出那一条原文）。
#   三、那内核版本号那一格没有就报缺（=> 而不猜填）。
#
# 退出码：0 = 逐条对得上；1 = 有对不上或缺出处；2 = 素材不在或读不出。

set -uo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
DATA="$HERE/docs/m0-kernel-baseline.json"
MATDIR="${1:-/home/bowen/LOBOS/work}"

if [ ! -f "$DATA" ]; then
  echo "清单数据文件不在: $DATA"
  exit 2
fi

DEFCONF="$MATDIR/gki_defconfig"
KCONFIG="$MATDIR/kconfig"
for f in "$DEFCONF" "$KCONFIG"; do
  if [ ! -f "$f" ]; then
    echo "素材不在: $f"
    exit 2
  fi
done

# 那两**条**的核对逐字**要** sha256 —— 而 sha256sum **不在**就**换** python3（=> 而那**别处**逐字**有**）。
sha_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    python3 -c 'import hashlib,sys;print(hashlib.sha256(open(sys.argv[1],"rb").read()).hexdigest())' "$1"
  fi
}

DEF_SHA="$(sha_of "$DEFCONF")"
KCFG_SHA="$(sha_of "$KCONFIG")"

# 那一大段核对逐字**用** python3 —— 而它逐字**能**读那 JSON 也能读那素材。
python3 - "$DATA" "$DEFCONF" "$KCONFIG" "$DEF_SHA" "$KCFG_SHA" << 'PYEOF'
import io, json, os, sys

data_path, defc, kcfg, def_sha, kcfg_sha = sys.argv[1:6]
d = json.load(io.open(data_path, encoding='utf-8'))

dl = io.open(defc, encoding='utf-8', errors='replace').read().split(chr(10))
kl = io.open(kcfg, encoding='utf-8', errors='replace').read().split(chr(10))

bad = []

# 一、那素材的 sha256 逐字要对得上（=> 而那素材**变了**就**要**重出**那**清单**）。
for key, path, got in (('defconfig', defc, def_sha), ('kconfig', kcfg, kcfg_sha)):
    want = d['source'][key]['sha256']
    if want != got:
        bad.append('%s 的 sha256 逐字对不上（清单记 %s、实际 %s）' % (key, want[:16], got[:16]))

# 二、那 defconfig **每一条**逐字要能按行号读出那一条原文（=> 而那出处**不**许是**猜**的）。
for e in d['defconfigEntries']:
    n = e['line']
    if n < 1 or n > len(dl):
        bad.append('defconfig 条目行号越界: %d' % n)
        continue
    actual = dl[n - 1].strip()
    if actual.startswith('# '):
        actual = actual[2:]
    if actual != e['text']:
        bad.append('defconfig :%d 逐字对不上（清单记 %s、实际 %s）' % (n, e['text'][:60], actual[:60]))

# 三、那 Kconfig **每一个**段逐字要能按行号读出那一条原文。
for e in d['kconfigSections']:
    n = e['line']
    if n < 1 or n > len(kl):
        bad.append('kconfig 段行号越界: %d' % n)
        continue
    if kl[n - 1].rstrip() != e['text']:
        bad.append('kconfig :%d 逐字对不上（清单记 %s、实际 %s）' % (n, e['text'][:60], kl[n - 1].rstrip()[:60]))

for e in d['kconfigMenus']:
    n = e['line']
    if n < 1 or n > len(kl):
        bad.append('kconfig menu 段行号越界: %d' % n)
        continue
    if kl[n - 1].rstrip() != e['text']:
        bad.append('kconfig menu :%d 逐字对不上' % n)

# 四、那尾部 source 那一行逐字要能读出那一条原文。
t = d['kconfigTail']
n = t['line']
if n < 1 or n > len(kl):
    bad.append('kconfigTail 行号越界: %d' % n)
elif kl[n - 1].rstrip() != t['text']:
    bad.append('kconfigTail :%d 逐字对不上（清单记 %s、实际 %s）' % (n, t['text'], kl[n - 1].rstrip()))

# 五、那版本号那一格逐字**没有**就**报缺**（=> 而**不猜填**）。
kv = d.get('kernelVersion') or {}
if not kv.get('value'):
    print('报缺：那内核版本号逐字还没有（=> 而按 C-105 不猜填、=> 而那取逐字不归 01）')

# 六、那两件参考材料逐字**不**是**那内核**的**配置**（=> 而那清单**不**把它们**算**成**条目**）。
for r in d.get('referenceDocs') or []:
    if r.get('isKernelData'):
        bad.append('参考材料 %s 逐字被标成内核数据、=> 而那清单**不**该**算**它' % r['file'])

n_def = len(d['defconfigEntries'])
n_cfg = len(d['kconfigSections'])
n_menu = len(d['kconfigMenus'])
print('核对：defconfig 条目 %d 条、kconfig config 段 %d 个、kconfig menu 段 %d 个' % (n_def, n_cfg, n_menu))
if bad:
    print('对不上 %d 处：' % len(bad))
    for b in bad[:20]:
        print('  ' + b)
    sys.exit(1)
print('逐条对得上：每一行的出处都能读出那一条原文')
sys.exit(0)
PYEOF
