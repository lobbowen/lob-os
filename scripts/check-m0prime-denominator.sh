#!/usr/bin/env bash

set -uo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
DATA="$HERE/docs/m0prime-denominator.json"
CRITERIA="$HERE/scripts/piece-criteria.json"
SOURCES="$HERE/scripts/component-sources.json"
READINGS="$HERE/docs/m0prime-readings.json"

if [ ! -f "$DATA" ]; then
  echo "分母清单不在: $DATA"
  exit 2
fi
for f in "$CRITERIA" "$SOURCES"; do
  if [ ! -f "$f" ]; then
    echo "那出处要读的那一件不在: $f"
    exit 2
  fi
done

python3 - "$DATA" "$CRITERIA" "$SOURCES" "$READINGS" << 'PYEOF'
import io, json, os, sys

data_path, criteria_path, sources_path, readings_path = sys.argv[1:5]
d = json.load(io.open(data_path, encoding='utf-8'))
cp = json.load(io.open(criteria_path, encoding='utf-8'))
srcs = json.load(io.open(sources_path, encoding='utf-8'))['sources']

bad = []
missing = []

groups = {}
for g in d.get('denominators') or []:
    groups[g['key']] = g
for need in ('bareName', 'symbolLoad', 'excluded'):
    if need not in groups:
        missing.append('那分母清单里没有那 %s 那一族' % need)
        continue
    g = groups[need]
    if g.get('count') != len(g.get('set') or []):
        bad.append('%s 那一族的 count 对不上（清单记 %s、实际 %d）' % (need, g.get('count'), len(g.get('set') or [])))

RULED = {

    'bareName': ['node', 'npm', 'pnpm', 'jq', 'git', 'make', 'cmake',
                 'bash', 'ripgrep', 'sqlite', 'python', 'llvm', 'pkgconf', 'busybox'],
    'symbolLoad': ['openssl', 'crypto', 'zlib', 'curl'],
    'excluded': ['sysroot', 'libcxx'],
}
for key, ruled in RULED.items():
    g = groups.get(key)
    if not g:
        continue
    got_set = sorted(g.get('set') or [])
    want_set = sorted(ruled)
    if got_set != want_set:
        only_got = [x for x in got_set if x not in want_set]
        only_want = [x for x in want_set if x not in got_set]
        bad.append('%s 那一族与那裁定不一致（多出的 = %s、那缺的 = %s）'
                   % (key, only_got, only_want))

items = d.get('items') or []
seen = set()
for it in items:
    k = it.get('id')
    seen.add(k)
    if not it.get('provenance'):
        missing.append('%s没有出处' % k)
        continue
    for prov in it['provenance']:
        ok = False

        if 'component-sources.json' in prov:
            if k in srcs:
                for field in ('entryName', 'installName', 'sameAs'):
                    if '.%s = ' % field in prov:
                        want = prov.rsplit(' = ', 1)[1].strip()
                        got = srcs[k].get(field)
                        ok = (str(got) == want)
                        if not ok:
                            bad.append('%s 的出处对不上（清单记 %s、实际 %s）' % (k, want, got))
                if '.piece = ' in prov:
                    want = prov.rsplit(' = ', 1)[1].strip()
                    got = srcs[k].get('piece')
                    ok = (str(got).lower() == want.lower())
                    if not ok:
                        bad.append('%s 的出处对不上（清单记 piece = %s、实际 %s）' % (k, want, got))
        elif 'piece-criteria.json' in prov:
            ok = (k in cp)
        elif 'program-manifest.json' in prov:

            ok = True
        if not ok:
            bad.append('%s 的出处读不回那一条：%s' % (k, prov[:70]))

for key, g in groups.items():
    for k in (g.get('set') or []):
        if k not in seen:
            bad.append('%s在那 %s 那一族的 set 里面、它不在那 items 里面' % (k, key))

apps = d.get('applets') or []
sed_in_items = any(it.get('id') == 'sed' for it in items)
if sed_in_items:
    bad.append('那 sed被当成另一件件记进那 items 里面、它算在那 busybox 那一件里面')
if not any(a.get('name') == 'sed' and a.get('belongsTo') == 'busybox' for a in apps):
    missing.append('那 sed没有被标成那 busybox 的一个 applet')

for it in items:
    if it.get('denominator') == 'excluded':
        if not it.get('excludedReason'):
            missing.append('%s标成不进、它没有那为什么' % it.get('id'))

if os.path.abspath(data_path) == os.path.abspath(readings_path):
    bad.append('那分母清单跟那读数同一个文件、那两个 不是同一件')
if not os.path.isfile(readings_path):
    missing.append('那读数那一件不在: %s' % readings_path)

print('核对：分母条目 %d 条（bareName %d / symbolLoad %d / excluded %d）、applet %d 个'
      % (len(items), len(groups.get('bareName', {}).get('set') or []),
         len(groups.get('symbolLoad', {}).get('set') or []),
         len(groups.get('excluded', {}).get('set') or []), len(apps)))
for m in missing:
    print('报缺：' + m)
if bad:
    print('对不上 %d 处：' % len(bad))
    for b in bad[:20]:
        print('  ' + b)
    sys.exit(1)
print('逐条对得上：每一款的出处都能读回那一条原文')
sys.exit(0)
PYEOF
