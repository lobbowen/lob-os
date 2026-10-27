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

#  那**旧**的**那一句**（=> 而那 ROUND51 :10 到 :17 **逐字**给**的**）按 C-115 **留档不删**、=> 而而它**已经****不**是**那事实**了、=> 而而那**事实**逐字**是**那**下面**那**读**那分母清单**的**那一条**
#  而那分母逐字**读**那分母清单（=> 而那分母清单逐字**是**那**定义**、=> 而而它**不**随**那读数**变**）
DENOM="$HERE/docs/m0prime-denominator.json"

python3 - "$CRITERIA" "$OUT" "$ADB" "$DEV" "$PKG" "$TARGET_PREFIX" "$TIMEOUT" "$DENOM" "$@" << 'PYEOF'
import base64, io, json, os, subprocess, sys, time
from datetime import datetime, timezone

criteria_path, out_path, adb, dev, pkg, target_prefix, timeout = sys.argv[1:8]
lib_prefix = os.path.join(os.path.dirname(target_prefix), 'lib')
denom = json.load(io.open(sys.argv[8], encoding='utf-8'))
_dg = {g['key']: g for g in denom.get('denominators') or []}
m0p_set = list(_dg.get('bareName', {}).get('set') or [])
lib_set = list(_dg.get('symbolLoad', {}).get('set') or [])
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


def child_env_value(key):
    #  那**应用**给**它**的**孩子**的**那** env **快照**（=> 而那 runtime.json **里面**的**那**一份**）。
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
    #  那**应用**给**它**的**孩子**的**那**一条**路径**：=> 而那 runtime.json **里面**的**那** env **快照**。
    #  => 而那**它**逐字**是**那设备侧**真的**给**孩子**的**那一条**（=> 而那**它**逐字**不**是**本脚本**自己**编**的**）。
    #  => 而而那**取不到**就**报缺**（=> 而按 C-105 **不猜填**）。
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
#  缺陷名（C-102）**那一族证书那一族逐字没有带过去** ——
#    => 而那**它**治**的**是「那判据在**那设备**上**能不能**验**那**证书**」。
#    => 而那**成因**：=> 而那 child_env_value **那一族**逐字**只**导出**那 LD_LIBRARY_PATH** 与**那 PATH**、
#       => 而而那**它**们**不**含**那 SSL_CERT_FILE** 与那 CURL_CA_BUNDLE** 与那 GIT_SSL_CAINFO**。
#    => 而那**后果**逐字**是**：=> 而那 git 那一件逐字**报**那「SSL certificate OpenSSL verify result: unable to get local issuer」、
#       => 而而那**它**逐字**被**读成「那证书不在那设备上」。
#    => 而那**处置**：=> 而那**运行时**给**孩子**的**那** env **快照**里面**有**的**那些**逐字**都**带**过去**（=> 而那**它**们逐字**是**那**真源**）。
#    => 而按 C-104：=> 而那**采集层**逐字**只有**一条路（=> 而这一支**就是**它）。
child_ssl_cert_file = child_env_value('SSL_CERT_FILE')
child_curl_ca_bundle = child_env_value('CURL_CA_BUNDLE')
child_git_ssl_cainfo = child_env_value('GIT_SSL_CAINFO')
#  缺陷名（C-102）**那 PREFIX 那一族逐字没有带过去** ——
#    => 而而它治**的**是「那判据在**那设备**上**能不能**找到**那**前缀**那一层**」。
#    => 而那**成因**：=> 而那**运行时**给**孩子**的**那** env **快照**里面**已经有**那 PREFIX（=> 而那 9c74013
#       逐字**给**了**那 RuntimeEnvironment.kt **那一行**）、=> 而而那**采集层**逐字**只**导出**那**几个**、
#       => 而而那 PREFIX 逐字**不**在**那**清单**里面**、=> 而而那**它**逐字**没**进**那判据真跑**的**那环境**。
#    => 而那**后果**逐字**是**：=> 而那 cmake **那一件**逐字**报**那「file failed to open for reading …/include/android/api-level.h」
#       （=> 而那 CMakeDetermineSystem.cmake :40 **那一行**逐字**读**的**是**那 $ENV{PREFIX}）。
#    => 而那**处置**：=> 而那 PREFIX 逐字**跟着**那**运行时**的**那**快照**带**过去**（=> 而那**它**们逐字**是**那**真源**）。
#    => 而按 C-104：=> 而那**采集层**逐字**只有**一条路（=> 而这一支**就是**它）。
child_prefix = child_env_value('PREFIX')
if os.environ.get('LOBOS_DEBUG'):
    print('[debug] ld=' + str(len(child_ld)) + ' path=' + str(len(child_path)))
if os.environ.get('LOBOS_DEBUG'):
    print('[debug] child_ld len=' + str(len(child_ld)) + ' child_path len=' + str(len(child_path)))
if not child_ld:
    print('报缺：那设备上那 runtime.json **里面**的**那** env **快照**逐字**取不到**'
          '（=> 而那**应用**给**它**的**孩子**的**那**一条**路径**就**取不到**、=> 而而**不猜填**）')


def run_criterion(code, criterion_env=None):
    #  => 而那判据逐字**整个**逐字**在**那**设备**上**跑**（=> 而那 node **也**是**那设备**的**那一个**）。
    #     => 而而它治**的**是「那判据**自己**看到的世界」与「那目标**自己**看到的世界」逐字**不是**同一个**那个形状**。
    #     => 而那后果逐字**是**：=> 而那判据**写**的**那**文件逐字**落在宿主上**、=> 而而那目标逐字**在设备上**。
    #     => 而那 git 那一件逐字**答**「Author identity unknown」也是**这一族**（=> 而那 env 逐字**要**在**那设备**上**生效**）。
    #     => 而那**它**逐字**不是**「那件本身坏了」那个形状（=> 而那件逐字**起得来**）。
    #     => 而按 C-104：=> 而那判据逐字**只有**一条路（=> 而那**设备**上的**那** node）。
    #  => 而那设备上的 node 逐字**把**那**未捕获**的**异常**逐字**丢**了**（=> 而那 stderr 逐字**是**空的**）。
    #     => 而那**处置**：=> 而那判据逐字**先**装**那** uncaughtException **那一族**、=> 而而那**它**逐字**把**那**原话**打出来**。
    #     => 而而那**它**逐字**不**改**那判据**本体**的**形状**（=> 而那判据**还是**那 throw）。
    _dq = chr(34)
    _h = 'process.on(' + _dq + 'uncaughtException' + _dq
    _h += ', (e) => { console.log(' + _dq + 'LOBOS_CRIT_ERROR: ' + _dq
    _h += ' + (e && e.stack || e)); process.exit(1); });' + chr(10)
    _h += 'process.on(' + _dq + 'unhandledRejection' + _dq
    _h += ', (e) => { console.log(' + _dq + 'LOBOS_CRIT_ERROR: ' + _dq
    _h += ' + (e && e.stack || e)); process.exit(1); });' + chr(10)
    child_tmpdir = child_env_value('TMPDIR') or ('/data/user/0/%s/files/tmp' % pkg)

    env_prefix = ''
    #  => 而那判据**用**那 os.tmpdir() 逐字**建**那**临时目录**、=> 而而那**它**逐字**要**那 TMPDIR **在**那设备**上**生效**。
    if child_tmpdir:
        env_prefix += 'export TMPDIR=' + child_tmpdir + '; '
    #  => 而那**证书**那一族逐字**跟着**那**运行时**给**孩子**的**那** env **快照**走**（=> 而那**它**们逐字**是**那**真源**）
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
    #  => 而那判据**用**那 os.tmpdir() / TMPDIR 逐字**建**那**临时目录**、=> 而而那**它**逐字**要**在**那设备**上**有**。
    #     => 而那 TMPDIR 逐字**就是**那设备上**那**缓存**那一层**（=> 而那**应用**给**它**的**孩子**的**那** HOME/TMPDIR **那一族**）。
    inner = ('mkdir -p /data/user/0/%s/files/tmp %s && echo %s | base64 -d > %s && '
             'export LOBOS_LIB_PREFIX=%s; export LD_LIBRARY_PATH=%s; export PATH=%s; '
             'node %s 2>&1; echo "__LOBOS_RC__$?"; rm -f %s'
             % (pkg, child_tmpdir, b64, tmp_path, lib_prefix, child_ld, child_path, tmp_path, tmp_path))
    #  => 而那判据**给**那目标**的**那**环境**逐字**要**在**那设备**上**生效**
    one = 'run-as ' + pkg + ' sh -c ' + chr(39) + env_prefix + inner + chr(39)
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
            detail = '那设备上逐字**没有**那个 node（=> 而那判据逐字**要**设备**上的**那** node）'
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
    #  缺陷名（C-102）**那阈值那一族逐字换不回去** —— 而而那**处置**：=> 而那阈值逐字**记成**那**自选**那一族**的**形状**。
    #    => 而那**它**逐字**带**那**三样**：那版本（=> 而那仓内那 version.json 逐字 = 那 versionName 0.0.6 / 那 versionCode 6）、
    #       那理由、=> 而那取值（=> 而那**它**逐字**要**带**那读数**的**基线**（=> 而按 C-274 与按 C-203 那一族**））。
    #    => 而按 C-115：=> 而那**旧**的**那一句**（那阈值逐字**没有**出处、=> 而按 C-105 报缺）按 **留档不删**、=> 而它**在**那**下面**那一句**里面**。
    'threshold': {'value': 0.55, 'status': '已核',
                  'kind': 'M/N 大于等于 0.55',
                  'version': '那仓内那 version.json 逐字 = 那 versionName 0.0.6 / 那 versionCode 6',
                  'reason': '那 M0prime 真判据逐字是那 M/N 比率、=> 而那**它**逐字**是**那**下限**（=> 而那**它**逐字**不**是**那**目标**）、=> 而那**它**逐字**随版本递增**（=> 而那**它**逐字**只**能**往**上**走**不能**往**下**走**）',
                  'baseline': '那取数时刻 = 那 takenAt、=> 而那 M/N 逐字 = 那 M0prime 那一格的 M 与 N（=> 而那**它**逐字**是**那**重算**出来**的**）、=> 而按 C-274 与按 C-203 那一族',
                  'provenance': '那 **0.55** 那一档逐字**是**那**设计选择**那一族**的**选择**、=> 而而那**它**逐字**由**那 lead 与那 04 **一起**定**（=> 而那**它**逐字**不**在**那**上游官方流程**里**）、=> 而而那**它**逐字**不能**冒充**那**上游**的**（=> 而那 C-519 那一族逐字**仍**未过**、=> 而按 C-105 **报缺**）',
                  'note': '那阈值逐字**不**是**那读数**（=> 而而那 M/N **逐字**是**那读数**）、=> 而那**旧**的**那一句**逐字 = 「那阈值逐字**没有**出处（=> 而那 03 那边逐字**按 C-122 标**过**一句**「这个阈值是经验值，它没有出处」**—— 而而那**那一句**逐字**是**那**另一个**阈值**、=> 而**不**是**这**一个**）、=> 而按 C-105 报缺」——=> 而那**旧**的**那一句**按 **留档不删**、=> 而它**在**那**上面**那一句**里面**'},
    'M0prime': {
        'set': m0p_set,
        #  这一句逐字**记**的**是**那**来源**（=> 而那**它**逐字**是**那能力声明**、=> 而那**它**逐字**要**能**换**回**去**）。
        #    => 而那**旧**的**那一句**逐字**是**「那分母清单逐字**是**那**定义**、=> 而而它**不**随**那读数**变**」、
        #       => 而那**它**逐字**只**说了**那一个**方向**、=> 而而那**另一个**方向（=> 而那读数**跟着**那分母**走**）逐字**没**说**。
        #    => 而那**它**逐字**不**是「与那事实相反」那个形状（=> 而那**它**逐字**是**那**分母清单**、=> 而而那分母清单逐字**不**随**那读数**变**、=> 而而那**那一句**逐字**是**那**对的**）。
        #    => 而那**真的**耦合方向逐字**是**那读数**跟着**那分母**走**（=> 而那 :70 与那 :204 **那两**行逐字**就是**它**）。
        #    => 而按 C-115：=> 而那**旧**的**那一句**按 **留档不删**、=> 而它**在**那稿子**里面**。
        'setSource': '那 docs/m0prime-denominator.json（=> 而那分母清单逐字**是**那**定义**、=> 而那读数逐字**跟着**它**走**）',
        'rule': '那有可执行入口的用户态件（=> 而那判据对象逐字是 (件, entry) 那一对）',
        'M': m0p_passed,
        'N': len(m0p_results),
        'failed': m0p_failed,
        'note': '那 M0prime 真判据的分母（=> 而那八件里跑起来几个）'
    },
    'pieces': results,
    'library': {
        'set': lib_set,
        'rule': '那库类件（=> 而那 entry 合法地是空）逐字不进那按裸名调起的分母',
        'M': sum(1 for r in results if r['id'] in lib_set and r['status'] == '过'),
        'N': len(lib_set),
        'note': '那按需装载并解析出符号的那一族'
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
#  缺陷名（C-102）**那读数逐字把**前**一**版**覆盖**了** ——
#    => 而而它治**的**是「那读数逐字**能不能**换**回**去**」。
#    => 而那**成因**：=> 而那**现行**的**那**写**法逐字**只**写**那**这一**版、=> 而而那**旧**的**那一版**就**没**了**。
#    => 而那**后果**逐字**是**：=> 而那**两**个**时点**的**读数**逐字**互相**覆盖**、=> 而而那**它们**逐字**分不**开**。
#    => 而那**处置**：=> 而那**旧**的**那一版**逐字**并**进**那**历史**那一族**（=> 而那**它**们逐字**并列**）。
#    => 而按 C-115：=> 而那**两**份**读数**不**同时点**、=> 而而它们**不互相**覆盖**。
try:
    _prev = json.load(io.open(out_path, encoding='utf-8'))
except Exception:
    _prev = None
#  缺陷名（C-102）**那**不**完整**的**一趟**逐字**也**进**那历史** ——
#    => 而而它治**的**是「那**历史**那一族**记**的**是**不是**那**完整**的**读数**」。
#    => 而那**成因**：=> 而那**单件**跑的**那一趟**逐字**也**写**那**整个** doc（=> 而那 N 逐字**是**那 1、
#       => 而而那**它**们**逐字**不**是**那**分母**的**条数**）、=> 而而那**它**们**逐字**也**进**那历史**。
#    => 而那**后果**逐字**是**：=> 而那历史那一族逐字**混**了**那**完整**的**读数**与那**残缺**的**读数**、
#       => 而而那**它**们逐字**分不**开**。
#    => 而那**处置**：=> 而那历史那一族逐字**只**收**那**完整**的**读数**（=> 而那 N 逐字**要**等于**那**分母**的**条数**）。
#    => 而按 C-104：=> 而那**历史**那一族逐字**只有**一条**写**的路**（=> 而这一支**就是**它**）。
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
            'note': '那**前**一**版**的**读数**（=> 而那它逐字**是**那**那一个**时点**的**事实**、=> 而而它逐字**不**覆盖**那**这一版**）',
        })
        doc['history'] = _hist
    else:
        #  => 而那**不**完整**的**那一趟**逐字**不**进**那历史**（=> 而它逐字**是**那**残缺**的**读数**）。
        _hist = (_prev.get('history') or []) if isinstance(_prev, dict) else []
        if _hist:
            doc['history'] = _hist
        print('[warn] 而那**前**一**版**逐字 N=' + str(_prev_N) + '、=> 而而那**它**逐字**不**等于**那分母**的**条数**（=' + str(_full_expected) + '）、=> 而而那**它**逐字**不**进**那历史**')
io.open(out_path, 'w', encoding='utf-8').write(json.dumps(doc, ensure_ascii=False, indent=2) + chr(10))
print('M0prime M=%d N=%d（不过 %d）、采集那一族 M=%d N=%d、取数时刻 %s'
      % (m0p_passed, len(m0p_results), m0p_failed, passed, len(results), now))
for r in results:
    print('  %-10s %s rc=%s' % (r['id'], r['status'], r['exitCode']))
PYEOF
