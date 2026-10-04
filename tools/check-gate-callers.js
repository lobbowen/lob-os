#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..');
const SCRIPTS = path.join(ROOT, 'scripts');
const TOOLS = path.join(ROOT, 'tools');
const WF = path.join(ROOT, '.github', 'workflows');

const problems = [];
const bad = (m) => problems.push(m);

const GATE_SHAPE = /^(verify|check)-.*\.(sh|js|py)$|^(comment|do|debt|doc|delivery)-gate\.js$/;
const SELF_PROOF = /^(verify|check)-.*\.(sh|js|py)$/;
if (!SELF_PROOF.test('check-anything.js')) {
  console.log('FAIL 判据自证失败：形状匹配器抓不住样本');
  process.exit(1);
}
if (SELF_PROOF.test('build-userland-curl.sh')) {
  console.log('FAIL 判据自证失败：形状匹配器把构建脚本当门禁');
  process.exit(1);
}

const isGate = (name) => GATE_SHAPE.test(name);

function readAll(p) {
  try { return fs.readFileSync(p, 'utf8'); } catch (_e) { return null; }
}

const workflowFiles = fs.existsSync(WF) ? fs.readdirSync(WF).filter((f) => /\.ya?ml$/.test(f)) : [];
if (!workflowFiles.length) bad('.github/workflows/ 下没有 workflow —— 无处可谈调用点');
const wfText = workflowFiles.map((f) => readAll(path.join(WF, f)) || '').join('\n');

const localGates = [];
for (const dir of [SCRIPTS, TOOLS]) {
  if (!fs.existsSync(dir)) continue;
  for (const name of fs.readdirSync(dir)) {
    if (!isGate(name)) continue;
    localGates.push({ dir: path.basename(dir), name, rel: path.join(path.basename(dir), name) });
  }
}

const CALLED_BY_ANY = localGates
  .map((g) => g.name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))
  .join('|');

for (const g of localGates) {
  const nameRe = new RegExp('(?:scripts|tools)/' + g.name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'));
  if (nameRe.test(wfText)) continue;
  const calledByOtherGate = localGates.some(
    (o) => o.name !== g.name && new RegExp('(?:scripts|tools)/' + o.name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).test(readAll(path.join(ROOT, o.dir, o.name)) || '')
  );
  if (calledByOtherGate) continue;
  bad('仓内门禁 ' + g.rel + ' 没有任何调用点（workflow 里没跑，也没被别的门禁调）—— 它是一个永不执行的口子；要么接进 workflow，要么移走');
}

if (problems.length) {
  console.log('FAIL 门禁调用点门禁：' + problems.length + ' 处');
  for (const p of problems) console.log('  · ' + p);
  process.exit(1);
}
console.log('PASS 门禁调用点门禁：' + localGates.length + ' 件仓内门禁都有调用点（无永不执行的口子）');
process.exit(0);
