'use strict';
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..', '..');
const WF = path.join(ROOT, '.github/workflows/build-userland.yml');
const KEY = path.join(ROOT, 'scripts/cache-key.sh');
const PLAN = path.join(ROOT, 'docs/ENV-EXECUTION-PLAN.md');

if (!fs.existsSync(WF) || !fs.existsSync(KEY)) {
  console.error(`FAIL 文件不存在（ROOT=${ROOT}）：${WF} / ${KEY}`);
  process.exit(2);
}

const keySrc = fs.readFileSync(KEY, 'utf8');
const m = keySrc.match(/bucket_for\(\)\s*\{[\s\S]*?\n\}/);
if (!m) {
  console.error('FAIL cache-key.sh 里找不到 bucket_for()');
  process.exit(2);
}
const CLASS = {};
for (const line of m[0].split('\n')) {
  const g = /^\s*([a-z|0-9_-]+)\)\s+echo "(\w+)"/.exec(line);
  if (g) for (const t of g[1].split('|')) CLASS[t] = g[2];
}
if (Object.keys(CLASS).length < 10) {
  console.error(`FAIL bucket_for 只解析出 ${Object.keys(CLASS).length} 件，正则没匹配上`);
  process.exit(2);
}

const BUCKET_DESC = {
  base: '基础环境（不依赖运行时，随系统必备）',
  rt: '运行时（本身是环境，商店分发）',
  tool: '工具（用户可选装，商店分发）',
};

const byBucket = {};
for (const [t, b] of Object.entries(CLASS)) {
  (byBucket[b] = byBucket[b] || []).push(t);
}

const wf = fs.readFileSync(WF, 'utf8');
const mm = /^\s*tool:\s*\[([^\]]*)\]/m.exec(wf);
let bad = 0;

if (!mm) {
  console.error('FAIL build-userland.yml 里找不到矩阵 tool: [...]');
  process.exit(2);
}
const matrix = mm[1].split(',').map((s) => s.trim()).filter(Boolean);

const unknown = matrix.filter((t) => !CLASS[t]);
if (unknown.length) {
  for (const t of unknown) console.log(`[筐外] ${t} —— 矩阵里有，但 bucket_for 没给它分类`);
  bad += unknown.length;
}

const bucketsInMatrix = {};
for (const t of matrix) {
  const b = CLASS[t] || '（未分类）';
  (bucketsInMatrix[b] = bucketsInMatrix[b] || []).push(t);
}

console.log('== 三筐分类（唯一真相：scripts/cache-key.sh 的 bucket_for）==');
for (const b of ['base', 'rt', 'tool']) {
  const items = (byBucket[b] || []).sort();
  console.log(`  ${b} 筐 · ${BUCKET_DESC[b] || ''}`);
  console.log(`    全部 ${items.length} 件：${items.join(' · ')}`);
  const inWf = (bucketsInMatrix[b] || []).sort();
  console.log(`    build-userland.yml 矩阵里 ${inWf.length} 件：${inWf.join(' · ') || '（无）'}`);
}

const notBuilt = [];
for (const b of ['base', 'rt', 'tool']) {
  for (const t of byBucket[b] || []) {
    const hasJob = t === 'llvmtoolchain' || t === 'node';
    const isNative = ['bash', 'rg', 'busybox'].includes(t);
    if (!matrix.includes(t) && !hasJob && !isNative) {
      notBuilt.push(`${b}/${t}`);
    }
  }
}
if (notBuilt.length) {
  console.log('');
  console.log('[未构建] 以下已分类但不在任何构建路径里：');
  for (const x of notBuilt) console.log('  ' + x + '  —— 确认它由谁产出（build-apk.yml 的原生件？还是暂缓？）');
}

if (fs.existsSync(PLAN)) {
  const plan = fs.readFileSync(PLAN, 'utf8');
  const rows = [];
  for (const line of plan.split('\n')) {
    if (!line.startsWith('|')) continue;
    if (!/底座|运行时|工具/.test(line)) continue;
    for (const t of Object.keys(CLASS)) {
      if (line.includes('`' + t + '`') && !rows.some((r) => r.includes(t))) rows.push(line.trim());
    }
  }
  if (rows.length) {
    console.log('');
    console.log('== ENV-EXECUTION-PLAN §2.3.1 里对应的行 ==');
    for (const r of rows) console.log('  ' + r.slice(0, 118));
  }
}

if (bad) {
  console.log('');
  console.log(`FAIL 三筐分类：${bad} 件在矩阵里但没有筐`);
  process.exit(1);
}
console.log('');
console.log(`PASS 三筐分类：矩阵 ${matrix.length} 件全部有筐，与 bucket_for 一致`);