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

// 字符串模板里的 $大写标识符 必须是本仓真实声明过的符号，否则编译期报
// Unresolved reference（本仓真实踩过："node-$ORIGIN-libs" 里 $ORIGIN 不是 Kotlin 变量）。
// 已转义的 \$ORIGIN 与注释里的 $ORIGIN 都合法，这里逐项排除。
function stripComments(line) {
  let out = '';
  let inStr = false;
  for (let i = 0; i < line.length; i += 1) {
    const c = line[i];
    if (inStr) {
      if (c === '\\') { out += c + (line[i + 1] || ''); i += 1; continue; }
      if (c === '"') inStr = false;
      out += c;
      continue;
    }
    if (c === '"') { inStr = true; out += c; continue; }
    if (c === '/' && line[i + 1] === '/') break;
    out += c;
  }
  return out;
}

const declaredSymbols = new Set();
for (const f of walk(SRC)) {
  const text = fs.readFileSync(f, 'utf8');
  for (const m of text.matchAll(/\b(?:const\s+val|val|var)\s+([A-Za-z_][A-Za-z0-9_]*)/g)) {
    declaredSymbols.add(m[1]);
  }
}

const problems = [];
for (const f of walk(SRC)) {
  const rel = f.replace(SRC + '/', '');
  const lines = fs.readFileSync(f, 'utf8').split('\n');
  lines.forEach((line, i) => {
    for (const [re, msg] of BAD) {
      if (!re.test(line)) continue;
      if (msg) problems.push(rel + ':' + (i + 1) + '  ' + msg);
    }
    const code = stripComments(line);
    const re = /(^|[^\\$])\$([A-Z][A-Z0-9_]+)/g;
    let m;
    while ((m = re.exec(code)) !== null) {
      if (declaredSymbols.has(m[2])) continue;
      problems.push(
        rel + ':' + (i + 1) + '  字符串模板里的 $' + m[2] +
        ' 不是本仓声明过的符号（编译期报 Unresolved reference）。' +
        '要写字面量 $ 须用反斜杠转义（\\$' + m[2] + '）' +
        '（本仓真实踩过："node-$ORIGIN-libs" 编译炸）',
      );
    }
  });
}

if (problems.length) {
  console.log('FAIL Kotlin 误用门禁：' + problems.length + ' 处');
  for (const p of [...new Set(problems)].slice(0, 20)) console.log('  · ' + p);
  process.exit(1);
}

console.log('PASS Kotlin 误用门禁：无把扩展函数当成员调用的写法（这类错误结构检查看不出来，只有编译才炸）');
