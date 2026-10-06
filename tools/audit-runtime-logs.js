#!/usr/bin/env node
'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const SRC = path.join(ROOT, 'container/app/src/main/java');

function walk(dir, out) {
  out = out || [];
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (p.endsWith('.kt')) out.push(p);
  }
  return out;
}

const files = walk(SRC);

const WRITE = [
  { re: /StateFiles\.write(?:Atomic|Json)\s*\(/g, how: 'StateFiles' },
  { re: /writeAtomic\s*\(/g, how: 'writeAtomic' },
  { re: /writeText\s*\(/g, how: 'writeText' },
  { re: /appendText\s*\(/g, how: 'appendText' },
  { re: /FileWriter\s*\(|FileOutputStream\s*\(|bufferedWriter\s*\(\s*\)\.(?:write|append)/g, how: 'Writer' },
];

const writers = [];

for (const f of files) {
  const rel = f.replace(SRC + '/', '').replace(/^lobos\//, '');
  const txt = fs.readFileSync(f, 'utf8');
  const lines = txt.split('\n');

  const paths = new Set();
  for (const m of txt.matchAll(/(?:const\s+)?(?:DIR|FILE|STRUCT_FILE|STRUCT_DIR|NODE_ERR|CURSOR_FILE|LOG_FILE)\s*=\s*"([^"]+)"/g)) {
    paths.add(m[1]);
  }

  lines.forEach((line, i) => {
    for (const { re, how } of WRITE) {
      re.lastIndex = 0;
      if (!re.test(line)) continue;
      writers.push({
        rel, line: i + 1, how,
        snippet: line.trim().slice(0, 90),
        paths: [...paths],
      });
      break;
    }
  });
}

const byTarget = new Map();
for (const w of writers) {
  const key = w.paths.length ? w.paths.join(' + ') : '(动态路径，未声明常量)';
  if (!byTarget.has(key)) byTarget.set(key, { paths: w.paths, writers: new Set(), lines: 0 });
  const g = byTarget.get(key);
  g.writers.add(w.rel);
  g.lines += 1;
}

console.log('');
console.log('=== 往磁盘写运行记录的地方 ===');
console.log('（判据：StateFiles.write* / writeAtomic / writeText / appendText / FileWriter）');
console.log('');

let n = 0;
for (const [target, g] of [...byTarget.entries()].sort((a, b) => b[1].writers.size - a[1].writers.size)) {
  n += 1;
  console.log('落盘目标：' + target);
  console.log('  写手 ' + g.writers.size + ' 个，调用点 ' + g.lines + ' 处');
  for (const w of [...g.writers].sort()) console.log('    · ' + w);
  console.log('');
}

console.log('共 ' + n + ' 套落盘目标，' + new Set(writers.map((w) => w.rel)).size + ' 个文件在写。');

const ALSO_JOURNAL = /Journal\.(append|note)\s*\(/;
const dual = [];
for (const f of files) {
  const rel = f.replace(SRC + '/', '').replace(/^lobos\//, '');
  if (rel === 'RuntimeDiagnostics.kt') continue;
  const txt = fs.readFileSync(f, 'utf8');
  const hasJournal = ALSO_JOURNAL.test(txt);
  const RECORD_PATH = /(journal|events\.jsonl|diag\.jsonl|diagnostics\.txt|probe-journal|residency\.txt|node-stderr|kill-audit-cursor)/;
  const declaresOwn = [...txt.matchAll(/(?:const\s+val|DIR|FILE)\s*[\w]*\s*=\s*"([^"]+)"/g)]
    .some((m) => RECORD_PATH.test(m[1]));
  const hasOwn = declaresOwn;
  if (hasJournal && hasOwn) dual.push(rel + '  ← 声明了自己的记录文件 + 也写 Journal');
}
console.log('');
console.log('=== 既自己落盘、又写 Journal 的（数据源外的残留）===');
console.log(dual.length + ' 个文件：');
for (const d of dual.sort()) console.log('  ' + d);
console.log('');

const READERS = /(Journal|ProbeJournal|ResidencyAudit|RuntimeDiagnostics|SelfCheckReport)\./g;
const readers = new Set();
for (const f of files) {
  const rel = f.replace(SRC + '/', '').replace(/^lobos\//, '');
  const txt = fs.readFileSync(f, 'utf8');
  for (const m of txt.matchAll(READERS)) {
    if (!rel.startsWith(m[1] + '.kt')) readers.add(rel + ' → ' + m[1]);
  }
}
console.log('');
console.log('=== 谁在读（消费端）===');
console.log('读的地方 ' + readers.size + ' 处：');
for (const r of [...readers].sort()) console.log('  ' + r);