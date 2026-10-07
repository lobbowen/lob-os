const fs = require('fs');
const path = require('path');

const REPO = path.resolve(__dirname, '..');
const BUNDLE = process.env.LOBE_BUNDLE_DIR
  || path.join(REPO, '..', 'ss-bundle', 'dist');
const ISO_PKG = process.env.LOBE_ISO_PKG || 'lobos.app.verify';
const iso = '/data/user/0/' + ISO_PKG + '/files';
const SNAP = path.join(BUNDLE, '..', 'verify-snapshot.json');

const log = [];
const p = (s) => { log.push(s); console.log(s); };

const facts = {};
function record(k, v) { facts[k] = v; }

p('══ 自签供给验证：' + (fs.existsSync(iso) ? '装后' : '装前') + '基线 ══');
p('');

try {
  const m = JSON.parse(fs.readFileSync(BUNDLE + '/component-manifest-2.json', 'utf8'));
  p('① 供给清单（预签，7 件）:');
  p('   revision=' + m.revision + '  tools=' + m.tools.length + '  过期=' +
    new Date(Number(m.expiresEpochMs)).toISOString().slice(0, 10));
  p('   baseUrl=' + ((m.tools[0] || {}).url || '').replace(/\/component\/.*/, ''));
  p('   件: ' + m.tools.map((t) => t.name + '@' + t.version).join(' '));
  record('清单.revision', m.revision);
  record('清单.件', m.tools.map((x) => x.name).sort().join(' '));
} catch (e) {
  p('① 读不到自签清单: ' + e.message);
}
p('');

p('② 隔离包状态:');
if (fs.existsSync(iso)) {
  p('   /data/user/0/lobos.app.verify 已存在 —— 装过了');
  record('隔离包.已装', true);
  const diag = iso + '/os/diag.jsonl';
  if (fs.existsSync(diag)) {
    const lines = fs.readFileSync(diag, 'utf8').trim().split('\n').filter(Boolean);
    const sup = lines.filter((l) => l.includes('"stage":"supply"'));
    p('   诊断里 supply 相关 ' + sup.length + ' 条：');
    record('诊断.supply条数', sup.length);
    const last = sup.length ? JSON.parse(sup[sup.length - 1]) : null;
    if (last) { record('诊断.supply级', last.level); record('诊断.supply说', last.message); }
    for (const l of sup.slice(-3)) {
      try {
        const o = JSON.parse(l);
        p('     [' + o.level + '] ' + o.message + '  ' + (o.detail || ''));
      } catch (e) { p('     （解析失败）'); }
    }
    const tc = iso + '/usr/lib/toolchain';
    if (fs.existsSync(tc)) {
      const got = fs.readdirSync(tc).filter((n) => !n.startsWith('.')).sort().join(' ');
      p('   装出来的件: ' + got);
      record('装出来的件', got);
    } else {
      p('   还没有 toolchain 目录（供给还没产出件）');
    }
    const nb = iso + '/usr/bin/node';
    if (fs.existsSync(nb)) {
      const r = require('child_process').spawnSync(nb, ['-v'], { encoding: 'utf8' });
      const v = (r.stdout || '').trim();
      p('   node -v → ' + v + (r.status === 0 ? '' : '（跑失败）'));
      record('node.版本', v);
      record('node.能跑', r.status === 0);
      const tgt = fs.lstatSync(nb).isSymbolicLink() ? fs.readlinkSync(nb) : '（不是符号链接）';
      p('   node 链指向: ' + tgt);
      record('node.链指向', tgt);
      record('node.来自商店', tgt.indexOf('usr/lib/toolchain/node') >= 0);
    } else {
      p('   还没有 usr/bin/node');
    }
  } else {
    p('   还没有 diag.jsonl（应用没起过，或供给还没写诊断）');
  }
} else {
  p('   还没装 —— 这是装前基线');
  record('隔离包.已装', false);
}
p('');

let prev = null;
try { prev = JSON.parse(fs.readFileSync(SNAP, 'utf8')); } catch (e) { prev = null; }
if (prev) {
  p('── 与上次跑相比 ──');
  const keys = Object.keys(facts).filter((k) => k in (prev.facts || {}));
  const changed = keys.filter((k) => JSON.stringify(facts[k]) !== JSON.stringify(prev.facts[k]));
  if (!changed.length) {
    p('   无变化（' + keys.length + ' 项已记录，全部一致）');
  } else {
    for (const k of changed) {
      p('   ' + k + ': ' + JSON.stringify(prev.facts[k]) + '  →  ' + JSON.stringify(facts[k]));
    }
  }
  const newly = Object.keys(facts).filter((k) => !(k in (prev.facts || {})));
  if (newly.length) p('   本次新增项: ' + newly.join(', '));
  const gone = Object.keys(prev.facts || {}).filter((k) => !(k in facts));
  if (gone.length) p('   本次没记录到（上次有）: ' + gone.join(', ') + ' —— 装完后再跑，这些项才出现');
} else {
  p('（这是第一次跑，已记下快照；装完再跑一次就会显示差异）');
}
try { fs.writeFileSync(SNAP, JSON.stringify({ at: new Date().toISOString(), facts }, null, 2)); } catch (e) { }
p('');
p('完整判据（6 条）见 components/VERIFY-PREFLIGHT.txt');
