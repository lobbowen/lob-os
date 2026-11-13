#!/usr/bin/env bash

set -uo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
CRITERIA="$HERE/scripts/piece-criteria.json"
OUT="$HERE/docs/m0prime-readings.json"
ADB="${LOBOS_ADB:-/home/bowen/runtimes/platform-tools/adb}"
DEV="${LOBOS_DEV:-f0942f56}"
PKG="${LOBOS_PKG:-lobos.os}"

TARGET_PREFIX="${LOBOS_TARGET_PREFIX:-/data/user/0/$PKG/files/usr/bin}"
TIMEOUT="${LOBOS_CRITERIA_TIMEOUT:-120}"

SELF_ROOT="${LOBOS_SELF_ROOT:-$HERE/dist}"
SELF_PREFIX="${LOBOS_SELF_PREFIX:-$SELF_ROOT/bin}"
SELF_LIB="${LOBOS_SELF_LIB:-$SELF_ROOT/lib:$SELF_ROOT/usr/lib}"
SELF_SYSROOT="${LOBOS_SELF_SYSROOT:-$SELF_ROOT/sysroot}"

HOST_NODE_DIR="${LOBOS_HOST_NODE_DIR:-$(dirname "$(command -v node 2>/dev/null || echo /usr/bin/node)")}"
SELF_MODE="${LOBOS_SELF_MODE:-0}"
if [ "$SELF_MODE" = "1" ]; then
  [ -d "$SELF_PREFIX" ] || { echo "那我们自己那台的产物不在: $SELF_PREFIX"; exit 2; }
  SELF_TMP="${LOBOS_SELF_TMP:-$SELF_ROOT/../work/self-tmp}"
  mkdir -p "$SELF_TMP"
fi
if [ ! -f "$CRITERIA" ]; then
  echo "判据表不在: $CRITERIA"
  exit 2
fi
if [ ! -x "$ADB" ]; then
  echo "adb 不在那路径: $ADB"
  exit 2
fi
if ! command -v node >/dev/null 2>&1; then
  echo "报缺：那宿主上逐字**没有**那个 node（判据逐字**要**它**才**跑得动）"
  exit 2
fi

if ! "$ADB" -s "$DEV" shell "run-as $PKG echo ok" >/dev/null 2>&1; then
  echo "报缺：那设备逐字**不**通（那**装**与**那**取件**都**是**那设备侧**的**实施**、本脚本逐字**不**动**那设备**）"
  exit 2
fi

DENOM="$HERE/docs/m0prime-denominator.json"

python3 - "$CRITERIA" "$OUT" "$ADB" "$DEV" "$PKG" "$TARGET_PREFIX" "$TIMEOUT" "$DENOM" "$SELF_MODE" "$SELF_PREFIX" "$SELF_LIB" "$SELF_SYSROOT" "$HOST_NODE_DIR" "$@" << 'PYEOF'
import base64, io, json, os, subprocess, sys, time
from datetime import datetime, timezone

criteria_path, out_path, adb, dev, pkg, target_prefix, timeout = sys.argv[1:8]
self_mode = sys.argv[9] == '1'
self_prefix = sys.argv[10]
self_lib = sys.argv[11]
self_sysroot = sys.argv[12]
host_node_dir = sys.argv[13]
self_tmp = os.environ.get('LOBOS_SELF_TMP') or os.path.join(os.path.dirname(out_path), '..', 'work', 'self-tmp')
lib_prefix = os.path.join(os.path.dirname(target_prefix), 'lib')
denom = json.load(io.open(sys.argv[8], encoding='utf-8'))
_dg = {g['key']: g for g in denom.get('denominators') or []}
m0p_set = list(_dg.get('bareName', {}).get('set') or [])
lib_set = list(_dg.get('symbolLoad', {}).get('set') or [])
wanted = sys.argv[14:]
d = json.load(io.open(criteria_path, encoding='utf-8'))

pieces = [k for k in sorted(d.keys())
          if k != '$note' and isinstance(d[k], dict) and d[k].get('node')]
if wanted:
    pieces = [k for k in pieces if k in wanted]

    _partial = True
else:
    _partial = False
now = datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')
_now_partial = now

try:
    _prev = json.load(io.open(out_path, encoding='utf-8'))
except Exception:
    _prev = None
results = []

def child_env_value(key):

    p = '/data/user/0/%s/files/run/proc/runtime.json' % pkg
    rr = subprocess.run([adb, '-s', dev, 'shell', 'run-as', pkg, 'cat', p],
                        capture_output=True, text=True, timeout=90)
    txt = (rr.stdout or '').strip()
    if not txt or 'No such' in txt or 'Not found' in txt or 'Permission denied' in txt:
        return ''
    try:
        j = json.loads(txt)
        env = j.get('env')
        if isinstance(env, str):
            env = json.loads(env)
        return str((env or {}).get(key, ''))
    except Exception:
        return ''

def child_ld_path():

    p = '/data/user/0/%s/files/run/proc/runtime.json' % pkg
    rr = subprocess.run([adb, '-s', dev, 'shell', 'run-as', pkg, 'cat', p],
                        capture_output=True, text=True, timeout=90)
    txt = (rr.stdout or '').strip()
    if not txt or 'No such' in txt or 'Not found' in txt:
        return ''
    try:
        j = json.loads(txt)
        env = j.get('env')
        if isinstance(env, str):
            env = json.loads(env)
        return str((env or {}).get(key, ''))
    except Exception:
        return ''

child_ld = child_env_value('LD_LIBRARY_PATH')
child_path = child_env_value('PATH')

child_ssl_cert_file = child_env_value('SSL_CERT_FILE')
child_curl_ca_bundle = child_env_value('CURL_CA_BUNDLE')
child_git_ssl_cainfo = child_env_value('GIT_SSL_CAINFO')

child_prefix = child_env_value('PREFIX')
if os.environ.get('LOBOS_DEBUG'):
    print('[debug] ld=' + str(len(child_ld)) + ' path=' + str(len(child_path)))
if os.environ.get('LOBOS_DEBUG'):
    print('[debug] child_ld len=' + str(len(child_ld)) + ' child_path len=' + str(len(child_path)))
if not child_ld:
    print('报缺：那设备上那 runtime.json **里面**的**那** env **快照**逐字**取不到**'
          '（那**应用**给**它**的**孩子**的**那**一条**路径**就**取不到**、**不猜填**）')

def run_criterion(code, criterion_env=None):

    _dq = chr(34)
    _h = 'process.on(' + _dq + 'uncaughtException' + _dq
    _h += ', (e) => { console.log(' + _dq + 'LOBOS_CRIT_ERROR: ' + _dq
    _h += ' + (e && e.stack || e)); process.exit(1); });' + chr(10)
    _h += 'process.on(' + _dq + 'unhandledRejection' + _dq
    _h += ', (e) => { console.log(' + _dq + 'LOBOS_CRIT_ERROR: ' + _dq
    _h += ' + (e && e.stack || e)); process.exit(1); });' + chr(10)
    child_tmpdir = child_env_value('TMPDIR') or ('/data/user/0/%s/files/tmp' % pkg)

    env_prefix = ''

    if child_tmpdir:
        env_prefix += 'export TMPDIR=' + child_tmpdir + '; export LOBOS_ROOT=/data/user/0/' + pkg + '; '

    for _ck, _cv in (('SSL_CERT_FILE', child_ssl_cert_file),
                     ('CURL_CA_BUNDLE', child_curl_ca_bundle),
                     ('GIT_SSL_CAINFO', child_git_ssl_cainfo),
                     ('PREFIX', child_prefix)):
        if _cv:
            env_prefix += 'export ' + _ck + '=' + chr(39) + _cv + chr(39) + '; '
    for _k, _v in sorted((criterion_env or {}).items()):
        if _v is None:
            continue
        env_prefix += 'export ' + _k + '=' + chr(39) + str(_v) + chr(39) + '; '
    tmp_name = 'lobos-crit-%d-%d.js' % (os.getpid(), int(time.time() * 1000) % 1000000)
    tmp_path = '/data/user/0/%s/files/tmp/%s' % (pkg, tmp_name)
    b64 = base64.b64encode((_h + code).encode('utf-8')).decode('ascii')

    inner = ('mkdir -p /data/user/0/%s/files/tmp %s && echo %s | base64 -d > %s && '
             'export LOBOS_LIB_PREFIX=%s; export LD_LIBRARY_PATH=%s; export PATH=%s; '
             'node %s 2>&1; echo "__LOBOS_RC__$?"; rm -f %s'
             % (pkg, child_tmpdir, b64, tmp_path, lib_prefix, child_ld, child_path, tmp_path, tmp_path))

    one = 'run-as ' + pkg + ' sh -c ' + chr(39) + env_prefix + inner + chr(39)

    if self_mode:
        self_inner = ('mkdir -p ' + self_tmp + ' && echo ' + b64 + ' | base64 -d > ' + self_tmp + '/probe.js && '

                       'export TMPDIR=' + self_tmp + '; export LOBOS_LIB_PREFIX=' + self_lib + '; export LD_LIBRARY_PATH=' + self_lib + '; export PATH=' + host_node_dir + ':' + os.environ.get('PATH','') + ':' + self_prefix + '; '
                       'node ' + self_tmp + '/probe.js 2>&1; echo "__LOBOS_RC__$?"; rm -f ' + self_tmp + '/probe.js')
        rr = subprocess.run(['/bin/sh', '-c', self_inner], capture_output=True, text=True, timeout=int(timeout) + 180, cwd=self_tmp)
    else:
        rr = subprocess.run([adb, '-s', dev, 'shell', one], capture_output=True, text=True, timeout=int(timeout) + 180)
    out = rr.stdout or ''
    rc = None
    if '__LOBOS_RC__' in out:
        head, _, tail = out.rpartition('__LOBOS_RC__')
        try:
            rc = int(tail.strip().split(chr(10))[0])
        except ValueError:
            rc = None
        out = head
    if rc is None:
        detail = (rr.stderr or '').strip()
        if 'inaccessible or not found' in detail or 'not found' in detail:
            detail = '那设备上逐字**没有**那个 node（判据逐字**要**设备**上的**那** node）'
        return None, (out + detail).strip()
    return rc, out.replace(chr(13) + chr(10), chr(10)).strip()

for k in pieces:
    rc, out = run_criterion(d[k]['node'], d[k].get('env') or {})
    status = '未核' if rc is None else ('过' if rc == 0 else '不过')
    results.append({
        'id': k,
        'status': status,
        'exitCode': rc,
        'outputTail': out[-600:] if out else '',
        'criterion': d[k].get('criterion', ''),
    })

if _partial and isinstance(_prev, dict) and _prev.get("pieces"):
    _old = {p.get("id"): p for p in (_prev.get("pieces") or [])}
    for p in results:
        _old[p["id"]] = p
    _merged = [_old[k] for k in sorted(_old.keys())]
    results = _merged

    now = _prev.get("takenAt") or now

    _partialAt = _now_partial
m0p_results = [r for r in results if r['id'] in m0p_set]
m0p_passed = sum(1 for r in m0p_results if r['status'] == '过')
m0p_failed = sum(1 for r in m0p_results if r['status'] == '不过')

passed = sum(1 for r in results if r['status'] == '过')
failed = sum(1 for r in results if r['status'] == '不过')

doc = {
    '$note': 'M0′ 真判据的读数。逐件的过/不过逐字**是**那逐条跑出来的结果、那 M 与 N 逐字**是**那重算出来的、手写的结论**不**算。',
    'family': 'M0′ 真判据（用户态件那一族）',
    'notFamily': '那内核可信起点那一族（是 docs/m0-kernel-baseline.json 与那 check-m0-kernel-baseline.sh、两者逐字**不**是同一件、两者逐字**不**能合成一件）',
    'takenAt': now,
    'runner': '那宿主跑那个 node、目标经 adb 在那设备上跑（判据的 node 字段**不**用改）',

    'threshold': {'value': 0.55, 'status': '已核',
                  'kind': 'M/N 大于等于 0.55',
                  'version': '那仓内那 version.json 逐字 = 那 versionName 0.0.6 / 那 versionCode 6',
                  'reason': '那 M0prime 真判据逐字是那 M/N 比率、那**它**逐字**是**那**下限**（那**它**逐字**不**是**那**目标**）、那**它**逐字**随版本递增**（那**它**逐字**只**能**往**上**走**不能**往**下**走**）',
                  'baseline': '那取数时刻 = 那 takenAt、那 M/N 逐字 = 那 M0prime 那一格的 M 与 N（那**它**逐字**是**那**重算**出来**的**）、按 C-274 与按 C-203 那一族',
                  'provenance': '那 **0.55** 那一档逐字**是**那**设计选择**那一族**的**选择**、那**它**逐字**由**那 lead 与那 04 **一起**定**（那**它**逐字**不**在**那**上游官方流程**里**）、那**它**逐字**不能**冒充**那**上游**的**（那 C-519 那一族逐字**仍**未过**、按 C-105 **报缺**）',
                  'note': '那阈值逐字**不**是**那读数**（那 M/N **逐字**是**那读数**）、那**旧**的**那一句**逐字 = 「那阈值逐字**没有**出处（那 03 那边逐字**按 C-122 标**过**一句**「这个阈值是经验值，它没有出处」**—— 而那**那一句**逐字**是**那**另一个**阈值**、**不**是**这**一个**）、按 C-105 报缺」——那**旧**的**那一句**按 **留档不删**、它**在**那**上面**那一句**里面**'},
    'M0prime': {
        'set': m0p_set,

        'setSource': '那 docs/m0prime-denominator.json（分母清单逐字**是**那**定义**、读数逐字**跟着**它**走**）',
        'rule': '那有可执行入口的用户态件（判据对象逐字是 (件, entry) 那一对）',
        'M': m0p_passed,
        'N': len(m0p_results),
        'failed': m0p_failed,
        'note': '那 M0prime 真判据的分母（八件里跑起来几个）'
    },
    'pieces': results,
    'library': {
        'set': lib_set,
        'rule': '那库类件（那 entry 合法地是空）逐字不进那按裸名调起的分母',
        'M': sum(1 for r in results if r['id'] in lib_set and r['status'] == '过'),
        'N': len(lib_set),
        'note': '那按需装载并解析出符号的那一族'
    },
    'collected': {
        'M': passed,
        'N': len(results),
        'failed': failed,
        'note': '那采集那一层跑的那全部（分母逐字是**那表**里带那 node 字段的件数、它逐字**不**是**那 M0prime 的**分母**）'
    },
    'M': m0p_passed,
    'N': len(m0p_results),
    'failed': m0p_failed,
}
if _partial:
    doc['partialAt'] = _partialAt

_full_expected = len(m0p_set)
_prev_N = _prev.get('N') if isinstance(_prev, dict) else None
if isinstance(_prev, dict) and _prev.get('takenAt') and _prev.get('takenAt') != doc.get('takenAt'):
    if _prev_N == _full_expected:
        _hist = _prev.get('history') or []
        _hist = [h for h in _hist if h.get('takenAt') != _prev.get('takenAt')]
        _hist.append({
            'takenAt': _prev.get('takenAt'),
            'M': _prev.get('M'),
            'N': _prev.get('N'),
            'failed': _prev.get('failed'),
            'collectedM': (_prev.get('collected') or {}).get('M'),
            'collectedN': (_prev.get('collected') or {}).get('N'),
            'notPassed': [p.get('id') for p in (_prev.get('pieces') or []) if p.get('status') != '过'],
            'note': '那**前**一**版**的**读数**（它逐字**是**那**那一个**时点**的**事实**、它逐字**不**覆盖**那**这一版**）',
        })
        doc['history'] = _hist
    else:

        _hist = (_prev.get('history') or []) if isinstance(_prev, dict) else []
        if _hist:
            doc['history'] = _hist
        print('[warn] 而那**前**一**版**逐字 N=' + str(_prev_N) + '、那**它**逐字**不**等于**那分母**的**条数**（=' + str(_full_expected) + '）、那**它**逐字**不**进**那历史**')
io.open(out_path, 'w', encoding='utf-8').write(json.dumps(doc, ensure_ascii=False, indent=2) + chr(10))
print('M0prime M=%d N=%d（不过 %d）、采集那一族 M=%d N=%d、取数时刻 %s'
      % (m0p_passed, len(m0p_results), m0p_failed, passed, len(results), now))
for r in results:
    print('  %-10s %s rc=%s' % (r['id'], r['status'], r['exitCode']))
PYEOF
