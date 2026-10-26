#!/usr/bin/env bash
# M0′ 真判据的采集那一层 —— 逐条把那 piece-criteria.json 里面的机器可执行判据跑一遍。
#
# 用法：
#   run-piece-criteria.sh [件名 ...]
# 不给件名就逐条跑那表里全部的。
#
# ★ 那判据**在宿主**上跑、=> 而那**目标**在**设备**上跑。
#   => 而那宿主**有**那个 node（=> 而那设备上**没有**）、
#   => 而那判据**自己**去**spawn**那目标、
#   => 而那目标**在**那设备**上**、
#   => 而那**裸名**逐字**怎么**解析**到**那设备**上**那个**文件** = 那 **LOBOS_TARGET_PREFIX**（=> 而那默认值逐字是那设备上**那**usr/bin **那一族**）。
#   => 而那**spawn**逐字**变成**「那 node 逐字**去**adb **那设备**上**跑**那目标」，
#   => 而而那**判据**的**那** node 字段**不**用**改**（=> 而它逐字**只**管**spawn**那个**名字**与**读**那退出码**与**那 stderr**）。
#
# 它做什么：
#   一、逐条读那 scripts/piece-criteria.json，把那带 node 字段的判据逐个取出来。
#   二、逐条把那 node 字段（那可直接跑的验收代码）逐个跑在那宿主上，=> 而那目标**经**adb **在**那设备**上**跑**。
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
# 那设备上那**目标**逐字**在**哪（=> 而那判据逐字**按裸名**去**spawn**、=> 而而那**这**个前缀逐字**要**被**换成**那设备上**那**路径**）
TARGET_PREFIX="${LOBOS_TARGET_PREFIX:-/data/user/0/$PKG/files/usr/bin}"
TIMEOUT="${LOBOS_CRITERIA_TIMEOUT:-120}"

if [ ! -f "$CRITERIA" ]; then
  echo "判据表不在: $CRITERIA"
  exit 2
fi
if [ ! -x "$ADB" ]; then
  echo "adb 不在那路径: $ADB"
  exit 2
fi
if ! command -v node >/dev/null 2>&1; then
  echo "报缺：那宿主上逐字**没有**那个 node（=> 而那判据逐字**要**它**才**跑得动）"
  exit 2
fi

# 那设备**通不通**——=> 而那**通**是**那设备侧**的**实施**要**保证**的**。
if ! "$ADB" -s "$DEV" shell "run-as $PKG echo ok" >/dev/null 2>&1; then
  echo "报缺：那设备逐字**不**通（=> 而那**装**与**那**取件**都**是**那设备侧**的**实施**、=> 而本脚本逐字**不**动**那设备**）"
  exit 2
fi

#  那 M0prime 那一族逐字**那八**件（=> 而那 ROUND51 :10 到 :17 **逐字**给**的**）
M0P_SET="bash ripgrep jq openssl busybox crypto curl zlib"

python3 - "$CRITERIA" "$OUT" "$ADB" "$DEV" "$PKG" "$TARGET_PREFIX" "$TIMEOUT" "$M0P_SET" "$@" << 'PYEOF'
import io, json, os, subprocess, sys, time
from datetime import datetime, timezone

criteria_path, out_path, adb, dev, pkg, target_prefix, timeout = sys.argv[1:8]
lib_prefix = os.path.join(os.path.dirname(target_prefix), 'lib')
m0p_set = sys.argv[8].split()
wanted = sys.argv[9:]
d = json.load(io.open(criteria_path, encoding='utf-8'))

pieces = [k for k in sorted(d.keys())
          if k != '$note' and isinstance(d[k], dict) and d[k].get('node')]
if wanted:
    pieces = [k for k in pieces if k in wanted]

now = datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')
results = []

# 那判据逐字**自己**去**spawn**那目标、=> 而那**目标**逐字**在**那设备**上**。
# 而那宿主上逐字**没有**那目标、=> 而那 spawnSync 逐字**要**改成**经**adb **去**跑**它**。
# 而那**改法**逐字**不**动**那判据**的**那** node 字段**（=> 而它逐字**只**管**spawn**那个**名字**与**读**那退出码**与**那 stderr**）。
SHIM = r"""
const __cp = require('node:child_process');
const __adb = process.env.LOBOS_ADB || 'adb';
const __dev = process.env.LOBOS_DEV || '';
const __pkg = process.env.LOBOS_PKG || '';
const __prefix = process.env.LOBOS_TARGET_PREFIX || '';
const __orig = __cp.spawnSync;
__cp.spawnSync = function (cmd, args, opts) {
  opts = opts || {};
  const isTarget = typeof cmd === 'string' && !cmd.startsWith('/') && cmd !== 'sh' && cmd !== 'bash';
  if (!isTarget) return __orig.apply(this, arguments);
  const remote = __prefix + '/' + cmd;
  const q = (s) => String(s).replace(/'/g, "'\\\\''");
  const cmdline = q(remote) + (args && args.length ? ' ' + args.map(q).join(' ') : '');
  const inner = cmdline;
  const one = 'run-as ' + __pkg + ' sh -c ' + String.fromCharCode(39) + inner + String.fromCharCode(39);
  const r = __orig.call(this, __adb, ['-s', __dev, 'shell', one], {
    input: opts.input,
    encoding: 'utf8',
    timeout: opts.timeout
  });
  if (r.stdout && typeof r.stdout === 'string') {
    const i = r.stdout.indexOf('__LOBOS_RC__');
    if (i >= 0) {
      r.status = parseInt(r.stdout.slice(i + 13).trim().split('\\n')[0], 10);
      r.stdout = r.stdout.slice(0, i).replace(/\r\n/g, '\n');
      r.stderr = '';
    }
  }
  return r;
};
"""


def run_criterion(code):
    # 那判据逐字**是**那可执行代码、=> 而它逐字**在**那宿主**上**跑、
    # => 而那**目标**逐字**经**adb **在**那设备**上**跑。
    env = dict(os.environ)
    env['LOBOS_ADB'] = adb
    env['LOBOS_DEV'] = dev
    env['LOBOS_PKG'] = pkg
    env['LOBOS_TARGET_PREFIX'] = target_prefix
    env['LOBOS_LIB_PREFIX'] = lib_prefix
    script = SHIM + '\n' + code
    rr = subprocess.run(['node', '-e', script], capture_output=True, text=True,
                        env=env, timeout=int(timeout) + 120)
    return rr.returncode, (rr.stdout or '') + (rr.stderr or '')

for k in pieces:
    rc, out = run_criterion(d[k]['node'])
    status = '过' if rc == 0 else '不过'
    results.append({
        'id': k,
        'status': status,
        'exitCode': rc,
        'outputTail': out[-600:] if out else '',
        'criterion': d[k].get('criterion', ''),
    })

#  那**两个**分母逐字**各**答**一件**事**、=> 而按 C-135 逐字**不合并**（=> 而而那 **M/N** 那个数逐字**是**那 M0prime **那一族**的**）
m0p_results = [r for r in results if r['id'] in m0p_set]
m0p_passed = sum(1 for r in m0p_results if r['status'] == '过')
m0p_failed = sum(1 for r in m0p_results if r['status'] == '不过')

passed = sum(1 for r in results if r['status'] == '过')
failed = sum(1 for r in results if r['status'] == '不过')

doc = {
    '$note': 'M0′ 真判据的读数。逐件的过/不过逐字**是**那逐条跑出来的结果、=> 而那 M 与 N 逐字**是**那重算出来的、=> 而那手写的结论**不**算。',
    'family': 'M0′ 真判据（用户态件那一族）',
    'notFamily': '那内核可信起点那一族（=> 而那是 docs/m0-kernel-baseline.json 与那 check-m0-kernel-baseline.sh、=> 而那两者逐字**不**是同一件、=> 而而那两者逐字**不**能合成一件）',
    'takenAt': now,
    'runner': '那宿主跑那个 node、=> 而那目标经 adb 在那设备上跑（=> 而那判据的 node 字段**不**用改）',
    'threshold': {'value': None, 'status': '未核',
                  'note': '那阈值逐字**没有**出处（=> 而那 03 那边逐字**按 C-122 标**过**一句**「这个阈值是经验值，它没有出处」**—— 而而那**那一句**逐字**是**那**另一个**阈值**、=> 而**不**是**这**一个**）、=> 而按 C-105 报缺、=> 而而那 M/N **逐字**是**那读数**、=> 而那阈值逐字**不是**那读数'},
    'pieces': results,
    'M0prime': {
        'set': m0p_set,
        'setSource': '07 那 ROUND51 :10 到 :17 逐字给的那形态表',
        'M': m0p_passed,
        'N': len(m0p_results),
        'failed': m0p_failed,
        'note': '那 M0prime 真判据的分母（=> 而那八件里跑起来几个）'
    },
    'collected': {
        'M': passed,
        'N': len(results),
        'failed': failed,
        'note': '那采集那一层跑的那全部（=> 而那分母逐字是**那表**里带那 node 字段的件数、=> 而它逐字**不**是**那 M0prime 的**分母**）'
    },
    'M': m0p_passed,
    'N': len(m0p_results),
    'failed': m0p_failed,
}
io.open(out_path, 'w', encoding='utf-8').write(json.dumps(doc, ensure_ascii=False, indent=2) + chr(10))
print('M0prime M=%d N=%d（不过 %d）、采集那一族 M=%d N=%d、取数时刻 %s'
      % (m0p_passed, len(m0p_results), m0p_failed, passed, len(results), now))
for r in results:
    print('  %-10s %s rc=%s' % (r['id'], r['status'], r['exitCode']))
PYEOF
