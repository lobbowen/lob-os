#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..');
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

const BAD = [
  [/\.canonicalFile\.startsWith\(/, 'canonicalFile.startsWith — Kotlin 的 startsWith 是无接收者扩展函数，不能当成员调用（编译期报 Unresolved reference not）'],
  [/\.canonicalFile\.endsWith\(/, 'canonicalFile.endsWith — 同上，Kotlin 的 endsWith 是无接收者扩展函数'],
  [/\.startsWith\(/, null],
  [/\.endsWith\(/, null],
  [/String\.fromCharCode/, 'String.fromCharCode 是 JavaScript 的写法；Kotlin 用 \\n 或 System.lineSeparator()'],
  [/=> \{/, 'JS 箭头函数：Kotlin 用 fun + lambda'],
];

const problems = [];
for (const f of walk(SRC)) {
  const rel = f.replace(SRC + '/', '');
  const lines = fs.readFileSync(f, 'utf8').split('\n');
  lines.forEach((line, i) => {
    for (const [re, msg] of BAD) {
      if (!re.test(line)) continue;
      if (msg) problems.push(rel + ':' + (i + 1) + '  ' + msg);
    }
  });
}

if (problems.length) {
  console.log('FAIL Kotlin 误用门禁：' + problems.length + ' 处');
  for (const p of [...new Set(problems)].slice(0, 20)) console.log('  · ' + p);
  process.exit(1);
}

console.log('PASS Kotlin 误用门禁：无把扩展函数当成员调用的写法（这类错误结构检查看不出来，只有编译才炸）');
