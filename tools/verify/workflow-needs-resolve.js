'use strict';

const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..', '..');
const DIR = path.join(ROOT, '.github', 'workflows');

if (!fs.existsSync(DIR)) {
  console.error(`FAIL 找不到 workflow 目录：${DIR}`);
  process.exit(2);
}

const SELF_TEST = [
  {
    name: 'has-resolve',
    src: 'jobs:\n  resolve:\n    steps: []\n  build:\n    needs: resolve\n    steps: []\n',
    bad: false,
  },
  {
    name: 'missing-resolve',
    src: 'jobs:\n  build:\n    steps:\n      - if: $\'{{ needs.resolve.outputs.publish == \'true\' }}\'\n',
    bad: true,
  },
  {
    name: 'list-form',
    src: 'jobs:\n  a:\n    steps: []\n  b:\n    needs: [a]\n    steps: []\n',
    bad: false,
  },
  {
    name: 'list-form-missing',
    src: 'jobs:\n  b:\n    needs: [a, zzz]\n    steps: []\n',
    bad: true,
  },
];

function jobsOf(src) {
  const jobs = new Set();
  let inJobs = false;
  for (const line of src.split('\n')) {
    if (/^jobs:\s*$/.test(line)) { inJobs = true; continue; }
    if (inJobs) {
      if (line.trim() === '') continue;
      if (/^\S/.test(line)) { inJobs = false; continue; }
      const m = /^ {2}([A-Za-z0-9_-]+):\s*$/.exec(line);
      if (m) jobs.add(m[1]);
    }
  }
  return jobs;
}

function missingNeeds(src) {
  const jobs = jobsOf(src);
  const refs = new Set();
  for (const m of src.matchAll(/^[ \t]*needs:\s*\[([^\]]*)\]/gm)) {
    for (const t of m[1].split(',')) { const n = t.trim(); if (n) refs.add(n); }
  }
  for (const m of src.matchAll(/^[ \t]*needs:\s*([A-Za-z0-9_-]+)\s*$/gm)) refs.add(m[1]);
  for (const m of src.matchAll(/needs\.([A-Za-z0-9_-]+)\.outputs\./g)) refs.add(m[1]);
  return [...refs].filter((r) => !jobs.has(r)).sort();
}

let bad = 0;
for (const t of SELF_TEST) {
  const got = missingNeeds(t.src);
  const hit = got.length > 0;
  if (hit !== t.bad) {
    console.error(`FAIL 门禁自身失效：反例 ${t.name} 期望 ${t.bad ? '抓到' : '放过'}，实际${hit ? '抓到了' : '放过了'}`);
    bad++;
  }
}
if (bad) {
  console.error('门禁自己的反例没过 —— 下面这些结果不可信，先修门禁');
  process.exit(2);
}

const files = fs.readdirSync(DIR)
  .filter((f) => /\.(yml|yaml)$/.test(f))
  .sort();

let red = 0;
for (const f of files) {
  const src = fs.readFileSync(path.join(DIR, f), 'utf8');
  const miss = missingNeeds(src);
  if (miss.length) {
    red++;
    console.log(`FAIL ${f}：引用了本文件里没有的 job → ${miss.join(' · ')}`);
    console.log('     这个条件永远求不出真值，那几步会静默跳过（不报错）。');
    console.log('     要么补上那个 job，要么把 needs 指向真实存在的 job。');
  }
}

if (red) {
  console.log('');
  console.log(`FAIL needs 引用：${red} 个 workflow 的条件引用了不存在的 job`);
  process.exit(1);
}
console.log(`PASS needs 引用：${files.length} 个 workflow 的 needs 全部指向本文件里存在的 job`);