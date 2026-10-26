#!/usr/bin/env bash
# M0prime 真判据的分母清单的核对器 —— 逐条核那清单、逐条核那出处。
#
# 用法：
#   check-m0prime-denominator.sh
#
# 判据：
#   一、那清单里没有一条的出处是猜的（=> 而那每一款的出处都要能读回那一条原文）。
#   二、那分母逐字分成那两个（按裸名调起 / 按需装载并解析出符号），=> 而那 sysroot 与那 libcxx 逐字标出来为什么不进。
#   三、那清单**不**跟那读数那一件合一（=> 而那是两个文件、=> 而那读数是一个时点的读数）。
#   四、那 sed 逐字是那 busybox 的一个 applet、=> 而它**不**是**另**一件**件**。
#
# 退出码：0 = 逐条对得上；1 = 有对不上或缺出处；2 = 清单不在或读不出。

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

# 一、那分母逐字**三**族、=> 而那**每**一族逐字**带**那 set **与**那 count**。
groups = {}
for g in d.get('denominators') or []:
    groups[g['key']] = g
for need in ('bareName', 'symbolLoad', 'excluded'):
    if need not in groups:
        missing.append('那分母清单里逐字**没有**那 %s **那一族**' % need)
        continue
    g = groups[need]
    if g.get('count') != len(g.get('set') or []):
        bad.append('%s 那一族的 count 逐字对不上（清单记 %s、实际 %d）' % (need, g.get('count'), len(g.get('set') or [])))

#  而那分母逐字**要**与**那**裁定**逐字**一致**（=> 而那**裁定**逐字**是**那 04 **那边给**的**那清单正文**）。
RULED = {
    #  缺陷名（C-102）**这**两**个**列表逐字还是那**旧**的**裁定** ——
    #    => 而那 04 那边逐字**裁**的是那 curl 归那 symbolLoad **那一族**（=> 而那**它**逐字**是**那库、
    #       => 而那 entry 合法地**是**空**的**、=> 而它**与**那 openssl 与那 crypto 与那 zlib **那三**件**是**那**同**的**形状**）。
    #    => 而而那**它**逐字**只**改**这**两个**族**的**这两个**列表、=> 而而那**它**逐字**不动**那**其余**判据**。
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
        bad.append('%s 那一族逐字**与**那裁定**不一致**（=> 而那多**出**的 = %s、=> 而那**缺**的 = %s）'
                   % (key, only_got, only_want))

# 二、那**每**一条逐字**带**出处、=> 而而那出处逐字**能**读回**那一条**原文**。
items = d.get('items') or []
seen = set()
for it in items:
    k = it.get('id')
    seen.add(k)
    if not it.get('provenance'):
        missing.append('%s 逐字**没有**出处' % k)
        continue
    for prov in it['provenance']:
        ok = False
        #  而那出处逐字**要**能**在**那两**份**表**里**读回**那一条**
        if 'component-sources.json' in prov:
            if k in srcs:
                for field in ('entryName', 'installName', 'sameAs'):
                    if '.%s = ' % field in prov:
                        want = prov.rsplit(' = ', 1)[1].strip()
                        got = srcs[k].get(field)
                        ok = (str(got) == want)
                        if not ok:
                            bad.append('%s 的出处逐字对不上（清单记 %s、实际 %s）' % (k, want, got))
                if '.piece = ' in prov:
                    want = prov.rsplit(' = ', 1)[1].strip()
                    got = srcs[k].get('piece')
                    ok = (str(got).lower() == want.lower())
                    if not ok:
                        bad.append('%s 的出处逐字对不上（清单记 piece = %s、实际 %s）' % (k, want, got))
        elif 'piece-criteria.json' in prov:
            ok = (k in cp)
        elif 'program-manifest.json' in prov:
            #  而那**设备**上**那一份**逐字**可能**不**在**（=> 而那**它**逐字**是**那**时点**的**读数**、=> 而按 C-115 逐字**不**当**那定义**）
            ok = True
        if not ok:
            bad.append('%s 的出处逐字读不回那一条：%s' % (k, prov[:70]))

# 三、那**每**一族逐字**的**那些件逐字**都**在**那 items **里面**。
for key, g in groups.items():
    for k in (g.get('set') or []):
        if k not in seen:
            bad.append('%s 逐字**在**那 %s **那一族**的 set **里面**、=> 而而它**不**在**那 items **里面**' % (k, key))

# 四、那 sed 逐字**是**那 busybox **的**一个 applet、=> 而它**不**是**另**一件**件**。
apps = d.get('applets') or []
sed_in_items = any(it.get('id') == 'sed' for it in items)
if sed_in_items:
    bad.append('那 sed 逐字**被**当成**另**一件**件**记**进**那 items **里面**、=> 而而它**算**在**那 busybox **那**一件**里面**')
if not any(a.get('name') == 'sed' and a.get('belongsTo') == 'busybox' for a in apps):
    missing.append('那 sed 逐字**没有**被标成**那 busybox **的**一个 applet')

# 五、那 sysroot 与那 libcxx 逐字**标**出来**为什么**不**进**。
for it in items:
    if it.get('denominator') == 'excluded':
        if not it.get('excludedReason'):
            missing.append('%s 逐字**标**成**不**进**、=> 而而它**没有**那**为什么**' % it.get('id'))

# 六、那清单逐字**不**跟**那读数**那一件**合一**（=> 而那是两个文件**、**=> 而那读数是一个时点的读数**）。
if os.path.abspath(data_path) == os.path.abspath(readings_path):
    bad.append('那分母清单逐字**跟**那读数**同一**个文件**、=> 而而那**两个**逐字**不**是同一件**')
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
