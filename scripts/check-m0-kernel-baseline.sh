#!/usr/bin/env bash
# M0 内核可信起点清单的核对器 —— 逐条重算那份清单，逐条对出处。
#
# 用法：
#   check-m0-kernel-baseline.sh
#
# 判据：
#   一、那份清单逐字与那仓内那份 gki_defconfig 逐字对得上。
#   二、那清单里没有一条的出处是猜的（=> 而那每一行号都要能读出那一条原文）。
#   三、那内核版本号那一格没有就报缺（=> 而不猜填）。
#
# 素材逐字在本仓内：docs/kernel-baseline/gki_defconfig 与 docs/kernel-baseline/kconfig。
# 而那两份逐字**不是**那构建的输入（=> 而那构建逐字**不**读它们、=> 而那**判据**逐字**只**读它们）。
#
# 退出码：0 = 逐条对得上；1 = 有对不上或缺出处；2 = 素材不在或读不出。

set -uo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
DATA="$HERE/docs/m0-kernel-baseline.json"
KB="$HERE/docs/kernel-baseline"

if [ ! -f "$DATA" ]; then
  echo "清单数据文件不在: $DATA"
  exit 2
fi

DEFCONF="$KB/gki_defconfig"
KCONFIG="$KB/kconfig"
for f in "$DEFCONF" "$KCONFIG"; do
  if [ ! -f "$f" ]; then
    echo "素材不在: $f"
    exit 2
  fi
done

sha_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    python3 -c 'import hashlib,sys;print(hashlib.sha256(open(sys.argv[1],"rb").read()).hexdigest())' "$1"
  fi
}

DEF_SHA="$(sha_of "$DEFCONF")"
KCFG_SHA="$(sha_of "$KCONFIG")"

python3 - "$DATA" "$DEFCONF" "$KCONFIG" "$DEF_SHA" "$KCFG_SHA" "$KB" << 'PYEOF'
import hashlib, io, json, os, sys

data_path, defc, kcfg, def_sha, kcfg_sha, kb = sys.argv[1:7]
d = json.load(io.open(data_path, encoding='utf-8'))

dl = io.open(defc, encoding='utf-8', errors='replace').read().split(chr(10))
kl = io.open(kcfg, encoding='utf-8', errors='replace').read().split(chr(10))

bad = []
missing = []

for key, path, got in (('defconfig', defc, def_sha), ('kconfig', kcfg, kcfg_sha)):
    want = d['source'][key]['sha256']
    if want != got:
        bad.append('%s 的 sha256 逐字对不上（清单记 %s、实际 %s）' % (key, want[:16], got[:16]))

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

t = d['kconfigTail']
n = t['line']
if n < 1 or n > len(kl):
    bad.append('kconfigTail 行号越界: %d' % n)
elif kl[n - 1].rstrip() != t['text']:
    bad.append('kconfigTail :%d 逐字对不上（清单记 %s、实际 %s）' % (n, t['text'], kl[n - 1].rstrip()))

# 那引用的被核对的那文件逐字得在**那仓内**（=> 而那 init/Kconfig.gki 逐字**已经**在**那仓内**了）。
for m in d.get('missingReferences') or []:
    p = os.path.join(kb, m['ref'])
    if not os.path.isfile(p):
        missing.append('%s 逐字**不**在**那仓内**（=> 而那 %s 逐字引它、=> 而按 C-105 报缺）' % (m['ref'], m['from']))

# 那引用的那几件逐字**要**在**那仓内**、=> 而而那**出处**逐字**要**能**核**。
ROOTDIR = os.path.dirname(os.path.dirname(os.path.abspath(data_path)))
for m in d.get('referencedFiles') or []:
    p = os.path.join(ROOTDIR, m['path'])
    if not os.path.isfile(p):
        missing.append('%s 逐字**不**在**那仓内**（=> 而那 %s 逐字引它、=> 而按 C-105 报缺）' % (m['ref'], m['from']))
        continue
    got = hashlib.sha256(open(p, 'rb').read()).hexdigest()
    if m.get('sha256') and got != m['sha256']:
        bad.append('%s 的 sha256 逐字对不上（清单记 %s、实际 %s）' % (m['ref'], m['sha256'][:16], got[:16]))

# 那**上游**那一族：=> 而那**差集**逐字**要**机械**核过**。
up = d.get('upstream') or {}
def diff_lines(a, b):
    la = io.open(a, encoding='utf-8', errors='replace').read().split(chr(10))
    lb = io.open(b, encoding='utf-8', errors='replace').read().split(chr(10))
    import difflib
    n = 0
    for line in difflib.unified_diff(la, lb, lineterm='', n=0):
        if line[:1] in ('+', '-') and line[:3] not in ('+++', '---'):
            n += 1
    return n
for key in ('initKconfig', 'gkiDefconfig'):
    e = up.get(key)
    if not e:
        continue
    la = os.path.join(ROOTDIR, e['localPath'])
    lb = os.path.join(ROOTDIR, e['upstreamPath'])
    for f in (la, lb):
        if not os.path.isfile(f):
            missing.append('%s 逐字**不**在**那仓内**' % f)
            break
    else:
        got = diff_lines(la, lb)
        if got != e['diffLines']:
            bad.append('%s 的差集逐字对不上（清单记 %d、实际 %d）' % (key, e['diffLines'], got))

kv = d.get('kernelVersion') or {}
if not kv.get('value'):
    missing.append('那内核版本号逐字还没有（=> 而按 C-105 不猜填、=> 而那取逐字不归 01、=> 而那取逐字归 06）')

# 那**网页**那一族（=> 而那 kmi.html 与那 lkm.html）逐字**不**是**那内核**的**配置**、
# => 而而那**真正**是**那内核**的**数据**的**那一件**（=> 而那 abi.stg）逐字**要**标**缺**（=> 而它**还**没**进**那仓**内**）。
for r in d.get('referenceDocs') or []:
    if r.get('isKernelData'):
        if r.get('status') == '未核':
            missing.append('%s 逐字**是**那内核的**数据**、=> 而它**还**没**进**那仓**内**、=> 而按 C-105 报缺' % r['file'])
        else:
            p2 = os.path.join(ROOTDIR, 'docs/kernel-baseline', r['file'])
            if not os.path.isfile(p2):
                missing.append('%s 逐字**不**在**那仓内**' % r['file'])
    elif r.get('file', '').endswith('.html'):
        #  那网页那一族逐字**不**是那内核的**配置**、=> 而而那清单**不**把它**算**成**条目**
        if r.get('isKernelData'):
            bad.append('网页材料 %s 逐字被标成内核数据' % r['file'])

n_def = len(d['defconfigEntries'])
n_cfg = len(d['kconfigSections'])
n_menu = len(d['kconfigMenus'])
print('核对：defconfig 条目 %d 条、kconfig config 段 %d 个、kconfig menu 段 %d 个' % (n_def, n_cfg, n_menu))
for m in missing:
    print('报缺：' + m)
if bad:
    print('对不上 %d 处：' % len(bad))
    for b in bad[:20]:
        print('  ' + b)
    sys.exit(1)
print('逐条对得上：每一行的出处都能读出那一条原文')
sys.exit(0)
PYEOF
