'use strict';
// workflow 里每一段 run: 的 shell 脚本都必须是合法 bash。
//
// 判的是行为事实：GitHub 把 run: 的内容原样交给 /usr/bin/bash -e。
// 语法错（比如 if 少了 fi）要到那一刻才炸 —— run 挂在第一步、后面全跳过，
// 而且报错只是一行 "line 13: syntax error"，看不出是哪个 workflow 的哪一步。
//
// 这不是代码形状门禁：它对应一次真实发生过的失败
// （build-node.yml 的 resolve 步漏了 fi，整条链第一步就死）。
//
// 反例（这个门禁必须能抓到）：
//   run: |
//     if [[ x ]]; then
//       echo hi
//     ← 少了 fi

const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const { spawnSync } = require('node:child_process');

const ROOT = path.resolve(__dirname, '..', '..');
const DIR = path.join(ROOT, '.github', 'workflows');

// 每段 run: 的位置（人可读的门禁信息）
function runsOf(src) {
  const L = src.split('\n');
  const out = [];
  for (let i = 0; i < L.length; i++) {
    const m = /^(\s*)run:\s*\|\s*$/.exec(L[i]);
    if (!m) continue;
    const ind = m[1].length + 2; // run: 的内容比 run: 多缩进
    const body = [];
    let j = i + 1;
    for (; j < L.length; j++) {
      if (!L[j].trim()) { body.push(''); continue; }
      const k = L[j].match(/^ */)[0].length;
      if (k < ind) break;
      body.push(L[j].slice(ind));
    }
    out.push({ line: i + 1, name: nameOf(L, i), body: body.join('\n') });
    i = j - 1;
  }
  return out;
}

function nameOf(L, at) {
  for (let k = at - 1; k >= 0 && k > at - 12; k--) {
    const m = /^\s*- (name|uses):\s*(.*)$/.exec(L[k]);
    if (m) return (m[1] === 'name' ? m[2] : m[2]) + '';
  }
  return '(无名步)';
}

// 门禁自身先过已知反例
const SELF = [
  {
    name: '缺 fi',
    src: 'jobs:\n  a:\n    steps:\n      - name: x\n        run: |\n          if [[ 1 ]]; then\n            echo hi\n',
    bad: true,
  },
  {
    name: '完整 if',
    src: 'jobs:\n  a:\n    steps:\n      - name: x\n        run: |\n          if [[ 1 ]]; then\n            echo hi\n          fi\n',
    bad: false,
  },
  {
    name: '缺 done',
    src: 'jobs:\n  a:\n    steps:\n      - name: x\n        run: |\n          for d in a b; do\n            echo "$d"\n',
    bad: true,
  },
];

function bashN(src) {
  const tmp = path.join(os.tmpdir(), `wfsh-${process.pid}-${Math.random().toString(36).slice(2)}.sh`);
  fs.writeFileSync(tmp, src);
  try {
    return spawnSync('bash', ['-n', tmp], { encoding: 'utf8' }).status === 0
      ? null
      : String(spawnSync('bash', ['-n', tmp], { encoding: 'utf8' }).stderr || '').split('\n').find((l) => /error/.test(l)) || '语法错';
  } finally {
    try { fs.unlinkSync(tmp); } catch (e) { /* 临时文件删不掉不影响判定 */ }
  }
}

let selfBad = 0;
for (const t of SELF) {
  const got = runsOf(t.src);
  const hasBad = got.some((r) => bashN(r.body));
  if (hasBad !== t.bad) {
    console.error(`FAIL 门禁自身失效：反例 ${t.name} 期望 ${t.bad ? '抓到' : '放过'}，实际${hasBad ? '抓到了' : '放过了'}`);
    selfBad++;
  }
}
if (selfBad) {
  console.error('门禁自己的反例没过 —— 下面这些结果不可信，先修门禁');
  process.exit(2);
}

const files = fs.readdirSync(DIR).filter((f) => /\.ya?ml$/.test(f)).sort();
let red = 0;
let checked = 0;
for (const f of files) {
  const src = fs.readFileSync(path.join(DIR, f), 'utf8');
  for (const r of runsOf(src)) {
    checked++;
    const err = bashN(r.body);
    if (err) {
      red++;
      console.log(`FAIL ${f}  第 ${r.line} 行「${r.name}」的 run 块不是合法 bash：${String(err).trim().slice(0, 90)}`);
    }
  }
}

if (red) {
  console.log('');
  console.log(`FAIL run 块语法：${red} 段。GitHub 会在跑到那一步时才炸（后面全跳过），`);
  console.log('     报错只是一行 "line N: syntax error"，看不出是哪个 workflow 的哪一步。');
  process.exit(1);
}
console.log(`PASS run 块语法：${files.length} 个 workflow 里 ${checked} 段 run 都是合法 bash`);