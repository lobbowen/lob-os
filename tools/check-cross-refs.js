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

const files = walk(SRC);
if (files.length === 0) {
  console.log('FAIL 跨包符号门禁：没找到 .kt 源文件');
  process.exit(1);
}

const decl = {};
for (const f of files) {
  const s = fs.readFileSync(f, 'utf8');
  const pkg = (s.match(/^package ([\w.]+)/m) || [])[1];
  if (!pkg) continue;
  decl[pkg] = decl[pkg] || { types: new Set(), members: new Set() };
  for (const m of s.matchAll(/^(?:@\w+\s+)*(?:data |sealed |abstract |open )*(?:object|class|interface|enum class)\s+(\w+)/gm)) {
    decl[pkg].types.add(m[1]);
  }
  for (const m of s.matchAll(/^ {4}(?:@\w+(?:\([^)]*\))?\s+)*(?:private |internal |public |suspend |inline |operator )*(?:fun|val|var|const val)\s+(?:<[^>]+>\s+)?(\w+)/gm)) {
    decl[pkg].members.add(m[1]);
  }
}

const problems = [];
let checked = 0;

for (const f of files) {
  const rel = f.replace(SRC + '/', '');
  const s = fs.readFileSync(f, 'utf8');
  const pkg = (s.match(/^package ([\w.]+)/m) || [])[1] || '';
  let body = s.replace(/^import .*$/gm, '').replace(/^\s*package .*$/gm, '');
  body = body.replace(/"(?:[^"\\]|\\.)*"/g, '""');
  for (const m of body.matchAll(/\b(lobos\.[a-z][A-Za-z0-9]*(?:\.[a-z][A-Za-z0-9]*)*)\.([A-Za-z_]\w*)\b/g)) {
    const qual = m[1];
    const leaf = m[2];
    if (qual.startsWith(pkg + '.')) continue;
    if (!decl[qual]) {
      problems.push(rel + '  → 不存在的包 ' + qual + '.' + leaf);
      continue;
    }
    checked += 1;
    if (!decl[qual].types.has(leaf) && !decl[qual].members.has(leaf)) {
      problems.push(rel + '  → ' + qual + ' 里没有 ' + leaf);
    }
  }
}

if (problems.length) {
  const uniq = [...new Set(problems)];
  console.log('FAIL 跨包符号门禁：' + uniq.length + ' 处解析不了');
  for (const p of uniq.slice(0, 25)) console.log('  · ' + p);
  process.exit(1);
}

console.log('PASS 跨包符号门禁：' + checked + ' 处 lobos.* 跨包引用全部能落到真实声明上');
console.log('  范围：只查「写了包限定名但符号不存在」这一类。');
console.log('  查不到：漏写 import / 漏写包前缀（无前缀时无法静态判定），那两类仍靠 CI 编译兜底。');
