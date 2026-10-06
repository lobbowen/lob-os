#!/usr/bin/env node
'use strict';

// 运行记录体系审计：找出所有往磁盘写"运行记录"的地方。
//
// 判据来源：用户指出 KillAudit 是碎片——"我们整个系统运行日志并没有一个
// 规范的标准化的模块…它只是一个莫名其妙的东西搭在这里，位置也不对"。
// 所以这里先回答：到底有几套在写记录？各写哪？格式是什么？有出口吗？

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

// 写记录的形态：StateFiles.write* / File(...).writeText / appendText / FileWriter
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

  // 该文件声明的落盘路径常量
  const paths = new Set();
  for (const m of txt.matchAll(/(?:const\s+)?(?:DIR|FILE|STRUCT_FILE|STRUCT_DIR|NODE_ERR|CURSOR_FILE|LOG_FILE)\s*=\s*"([^"]+)"/g)) {
    paths.add(m[1]);
  }

  lines.forEach((line, i) => {
    for (const { re, how } of WRITE) {
      re.lastIndex = 0;
      if (!re.test(line)) continue;
      // 排除纯读取（readText 后面接 write 才算）
      writers.push({
        rel, line: i + 1, how,
        snippet: line.trim().slice(0, 90),
        paths: [...paths],
      });
      break;
    }
  });
}

// 按"落盘目标文件"归组：一个写手若声明了路径常量，就算一路
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

// 有没有"读出来给人看/给反馈"的出口
const READERS = /(Journal|ProbeJournal|ResidencyAudit|RuntimeDiagnostics|SelfCheckReport)\./g;
const readers = new Set();
for (const f of files) {
  const rel = f.replace(SRC + '/', '').replace(/^lobos\//, '');
  const txt = fs.readFileSync(f, 'utf8');
  for (const m of txt.matchAll(READERS)) {
    // 排除定义文件自身
    if (!rel.startsWith(m[1] + '.kt')) readers.add(rel + ' → ' + m[1]);
  }
}
console.log('');
console.log('=== 谁在读（消费端）===');
console.log('读的地方 ' + readers.size + ' 处：');
for (const r of [...readers].sort()) console.log('  ' + r);