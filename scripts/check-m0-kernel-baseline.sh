#!/usr/bin/env bash

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

for m in d.get('missingReferences') or []:
    p = os.path.join(kb, m['ref'])
    if not os.path.isfile(p):
        missing.append('%s 逐字**不**在**那仓内**（那 %s 逐字引它、按 C-105 报缺）' % (m['ref'], m['from']))

ROOTDIR = os.path.dirname(os.path.dirname(os.path.abspath(data_path)))
for m in d.get('referencedFiles') or []:
    p = os.path.join(ROOTDIR, m['path'])
    if not os.path.isfile(p):
        missing.append('%s 逐字**不**在**那仓内**（那 %s 逐字引它、按 C-105 报缺）' % (m['ref'], m['from']))
        continue
    got = hashlib.sha256(open(p, 'rb').read()).hexdigest()
    if m.get('sha256') and got != m['sha256']:
        bad.append('%s 的 sha256 逐字对不上（清单记 %s、实际 %s）' % (m['ref'], m['sha256'][:16], got[:16]))

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
    missing.append('那内核版本号逐字还没有（按 C-105 不猜填、取逐字不归 01、取逐字归 06）')

for r in d.get('referenceDocs') or []:
    if r.get('isKernelData'):
        if r.get('status') == '未核':
            missing.append('%s 逐字**是**那内核的**数据**、它**还**没**进**那仓**内**、按 C-105 报缺' % r['file'])
        else:

            p2 = os.path.join(ROOTDIR, r.get('path') or ('docs/kernel-baseline/' + r['file']))
            if not os.path.isfile(p2):
                missing.append('%s 逐字**不**在**那仓内**' % r['file'])
            else:
                got2 = hashlib.sha256(open(p2, 'rb').read()).hexdigest()
                if r.get('sha256') and got2 != r['sha256']:
                    bad.append('%s 的 sha256 逐字对不上（清单记 %s、实际 %s）' % (r['file'], r['sha256'][:16], got2[:16]))
    elif r.get('file', '').endswith('.html'):

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
