// 那 M1 判据第一段逐字折成那**应用内**那一半的**采集层** —— 逐条把
// scripts/m2-criteria.json 里面的机器可执行判据跑一遍。
//
// 按 C-103：=> 而那**两边**逐字**各**答**一件**事**、=> 而而那**处置**逐字**不合并**。
// 按 C-105：那**缺**的逐字**报缺**，不猜填。
// 按 C-108：那**零命中**逐字**要**配**那阳性对照**。
const fs = require('node:fs');
const cp = require('node:child_process');
const path = require('node:path');

const ROOT = process.env.LOBOS_ROOT || path.resolve(__dirname, '..');
const OUT = process.env.M2_OUT || path.join(ROOT, 'work/m2-criteria-readings.json');
process.env.LOBOS_ROOT = ROOT;

const cpath = path.join(ROOT, 'scripts/m2-criteria.json');
if (!fs.existsSync(cpath)) { console.error('报缺：那判据库逐字不在 ' + cpath); process.exit(2); }
const d = JSON.parse(fs.readFileSync(cpath, 'utf8'));
const keys = Object.keys(d).filter(k => k !== '$note' && d[k] && d[k].node);

const out = {};
for (const k of keys) {
  const r = cp.spawnSync(process.execPath, ['-e', d[k].node], { encoding: 'utf8', timeout: 180000 });
  const ok = (r.status === 0);
  out[k] = {
    id: k,
    passed: ok,
    status: r.status,
    kind: d[k].kind || '',
    criterion: d[k].criterion,
    quote: d[k].quote || '',
    stdout: String(r.stdout || '').trim(),
    stderr: String(r.stderr || '').trim(),
  };
  const first = String(r.stderr || '').trim().split('\n')[0];
  console.log((ok ? '[ok] ' : '[不过] ') + k + (ok ? '' : ': ' + first));
}
const N = keys.length;
const P = keys.filter(k => out[k].passed).length;
out['$summary'] = { passed: P, total: N, at: new Date().toISOString() };
fs.mkdirSync(path.dirname(OUT), { recursive: true });
fs.writeFileSync(OUT, JSON.stringify(out, null, 2) + '\n');
console.log('[m2-criteria] 判据 ' + P + '/' + N + '　读数：' + OUT);
process.exit(P === N ? 0 : 1);
