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
const problems = [];

const BIG = [
  { re: /unzipInto\(bytes\b/, why: 'unzipInto(bytes, …) 把整包当 ByteArray 传，node 等大件解压必 OOM' },
  { re: /val bytes = runCatching \{ SupplyProvisioner\.httpGet\(/, why: '先用 httpGet 把整包读成 ByteArray 再解压' },
  { re: /val bytes = httpGet\(url\)/, why: '先用 httpGet 把整包读成 ByteArray 再解压' },
];

for (const f of files) {
  const rel = f.replace(SRC + '/', '');
  const lines = fs.readFileSync(f, 'utf8').split('\n');
  lines.forEach((line, i) => {
    if (BIG.some((b) => b.re.test(line))) {
      problems.push(rel + ':' + (i + 1) + '  ' + BIG.find((b) => b.re.test(line)).why);
    }
  });
  if (/httpGet\(/.test(fs.readFileSync(f, 'utf8')) && /zipTmp|\.zip\.part/.test(fs.readFileSync(f, 'utf8'))) {
    const src = fs.readFileSync(f, 'utf8');
    if (/httpGet\([^)]*\)[\s\S]{0,120}?FileOutputStream/.test(src)) {
      problems.push(rel + '  把 httpGet 的整包 ByteArray 再写进临时文件（等于没省内存）');
    }
  }
}

const supply = fs.readFileSync(path.join(SRC, 'lobos/runtime/SupplyProvisioner.kt'), 'utf8');
for (const fn of ['httpGetToFile', 'sha256HexFile', 'unzipFromFile']) {
  if (!supply.includes('fun ' + fn)) {
    problems.push('SupplyProvisioner 缺 ' + fn + '（流式下载/校验/解压）');
  }
}

const manifest = fs.readFileSync(path.join(SRC, '../AndroidManifest.xml'), 'utf8');
if (!/android:largeHeap="true"/.test(manifest)) {
  problems.push('AndroidManifest 没开 largeHeap：应用默认堆 256MB，node 等大件落位时不够');
}

if (problems.length) {
  console.log('FAIL 大件落位门禁：' + problems.length + ' 处');
  for (const p of problems) console.log('  · ' + p);
  process.exit(1);
}

console.log('PASS 大件落位门禁：下载/校验/解压全部流式（不把整包读进 ByteArray）；' +
  'largeHeap 已开。真机实测过：整包进 ByteArray 时 node 报 ' +
  '「Failed to allocate a 16777232 byte allocation … until OOM」');
