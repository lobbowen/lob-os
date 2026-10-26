#!/usr/bin/env bash
# M0′ 真判据的采集那一层 —— 逐条把那 piece-criteria.json 里面的机器可执行判据跑一遍。
#
# 用法：
#   run-piece-criteria.sh [件名 ...]
# 不给件名就逐条跑那表里全部的。
#
# 它做什么：
#   一、逐条读那 scripts/piece-criteria.json，把那带 node 字段的判据逐个取出来。
#   二、逐条把那 node 字段（那可直接跑的验收代码）逐个跑在那设备上。
#   三、逐条记下：过 / 不过 / 未核，加那退出码、那 stderr 尾部、那取数时刻。
#   四、把那结果逐条**重算**成 M 与 N，写进那读数的那一件。
#
# 那 M 与 N 逐字**不**是手写的结论、=> 而它们逐字**是**那逐条**跑**出来的**结果**。
# 那阈值那一格逐字**没有**就**报缺**（=> 而按 C-105 不猜填）。
#
# 退出码：0 = 逐条跑完（不论过不过）；1 = 有件跑不起来；2 = 配置或环境不对。

set -uo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
CRITERIA="$HERE/scripts/piece-criteria.json"
OUT="$HERE/docs/m0prime-readings.json"
ADB="${LOBOS_ADB:-/home/bowen/runtimes/platform-tools/adb}"
DEV="${LOBOS_DEV:-f0942f56}"
PKG="${LOBOS_PKG:-lobos.os}"
BINDIR="/data/user/0/$PKG/files/usr/bin"
TIMEOUT="${LOBOS_CRITERIA_TIMEOUT:-120}"

if [ ! -f "$CRITERIA" ]; then
  echo "判据表不在: $CRITERIA"
  exit 2
fi
if [ ! -x "$ADB" ]; then
  echo "adb 不在那路径: $ADB（=> 而那设备侧逐字**要**那 adb、=> 而那**装**与**跑**都**不**在本脚本**里**）"
  exit 2
fi

# 那**设备**上逐字**没有**那个 node、=> 而那 node 字段逐字**要**它**才**跑得动。
if ! "$ADB" -s "$DEV" shell "run-as $PKG test -x $BINDIR/node" >/dev/null 2>&1; then
  echo "报缺：那设备上逐字**没有**那个 node（=> 而那 node 字段逐字**要**它**才**跑得动、=> 而那 node 那一件逐字**不在**那设备**上**）"
  echo "报缺：那六件（node / npm / pnpm / git / curl / make）逐字**不在**那设备**上**、=> 而它们逐个的 asset 逐字**都**在**那 GitHub 上**、=> 而那**装**是**那设备侧**的**实施**（=> 而本脚本逐字**不**动**那设备**）"
  exit 2
fi

python3 - "$CRITERIA" "$OUT" "$ADB" "$DEV" "$PKG" "$BINDIR" "$TIMEOUT" "$@" << 'PYEOF'
import io, json, os, subprocess, sys, time
from datetime import datetime, timezone

criteria_path, out_path, adb, dev, pkg, bindir, timeout = sys.argv[1:8]
wanted = sys.argv[8:]
d = json.load(io.open(criteria_path, encoding='utf-8'))

pieces = [k for k in sorted(d.keys())
          if k != '$note' and isinstance(d[k], dict) and d[k].get('node')]
if wanted:
    pieces = [k for k in pieces if k in wanted]

now = datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')
results = []

def run_on_device(code):
    # 那 node 那一段逐字**是**那可执行代码、=> 而它逐字**在**那设备**上**跑**、=> 而那 PATH 逐字**要**带那 usr/bin。
    tmp = '/data/user/0/%s/files/tmp/criteria-run-%d.js' % (pkg, os.getpid())
    payload = code
    # 用 base64 搬过去（=> 而那代码里逐字**有**引号**也**不**会**被**拆**）。
    import base64
    b64 = base64.b64encode(payload.encode('utf-8')).decode('ascii')
    inner = ('mkdir -p /data/user/0/%s/files/tmp && '
             'echo %s | base64 -d > %s && '
             'export PATH=$PATH:%s && '
             'node %s 2>&1; echo "__RC__$?"') % (pkg, b64, tmp, bindir, tmp)
    rr = subprocess.run([adb, '-s', dev, 'shell', 'run-as', pkg, 'sh', '-c', inner],
                        capture_output=True, text=True, timeout=int(timeout) + 60)
    out = rr.stdout or ''
    rc = None
    if '__RC__' in out:
        head, _, tail = out.rpartition('__RC__')
        try:
            rc = int(tail.strip().split(chr(10))[0])
        except ValueError:
            rc = None
        out = head
    subprocess.run([adb, '-s', dev, 'shell', 'run-as', pkg, 'rm', '-f', tmp],
                   capture_output=True, text=True, timeout=60)
    return rc, out.strip()

for k in pieces:
    rc, out = run_on_device(d[k]['node'])
    if rc is None:
        status = '未核'
    elif rc == 0:
        status = '过'
    else:
        status = '不过'
    results.append({
        'id': k,
        'status': status,
        'exitCode': rc,
        'outputTail': out[-600:] if out else '',
        'criterion': d[k].get('criterion', ''),
    })

passed = sum(1 for r in results if r['status'] == '过')
failed = sum(1 for r in results if r['status'] == '不过')
unknown = sum(1 for r in results if r['status'] == '未核')

doc = {
    '$note': 'M0′ 真判据的读数。逐件的过/不过/未核逐字**是**那逐条跑出来的结果、=> 而那 M 与 N 逐字**是**那重算出来的、=> 而那手写的结论**不**算。',
    'family': 'M0′ 真判据（用户态件那一族）',
    'notFamily': '那内核可信起点那一族（=> 而那是 docs/m0-kernel-baseline.json 与那 check-m0-kernel-baseline.sh、=> 而那两者逐字**不**是同一件、=> 而那两者逐字**不**能合成一件）',
    'takenAt': now,
    'threshold': {'value': None, 'status': '未核',
                  'note': '那阈值逐字**没有**出处（=> 而那 03 那边逐字**按 C-122 标**过**一句**「这个阈值是经验值，它没有出处」**）、=> 而按 C-105 报缺、=> 而而那 M/N **逐字**是**那读数**、=> 而那阈值逐字**不是**那读数'},
    'pieces': results,
    'M': passed,
    'N': len(results),
    'failed': failed,
    'unknown': unknown,
}
io.open(out_path, 'w', encoding='utf-8').write(json.dumps(doc, ensure_ascii=False, indent=2) + chr(10))
print('M=%d N=%d（不过 %d、未核 %d）、取数时刻 %s' % (passed, len(results), failed, unknown, now))
for r in results:
    print('  %-10s %s' % (r['id'], r['status']))
PYEOF
