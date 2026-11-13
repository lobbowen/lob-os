#!/usr/bin/env node





















'use strict';
const fs = require('node:fs');

function readJson(p) {
  return JSON.parse(fs.readFileSync(p, 'utf8'));
}

const missPath = process.argv[2];
const manifestPath = process.argv[3];

if (!missPath || !manifestPath) {
  console.error('用法: node scripts/verify/reconcile-missing-pieces.js <缺件表.json> <清单.json>');
  process.exit(2);
}

let missing;
try {
  missing = readJson(missPath);
} catch (e) {
  
  console.error('::error title=缺件表读不到::' + missPath + ' 不在或读不出：' + e.message);
  console.error('::error:: 处置：先跑 scripts/toolchain/fetch-component-artifacts.sh，一档逐字落那缺件表。');
  process.exit(2);
}
if (!Array.isArray(missing)) {
  console.error('::error title=缺件表形状不对::' + missPath + ' 逐字不是数组 —— 报缺，不猜');
  process.exit(2);
}

let manifest;
try {
  manifest = readJson(manifestPath);
} catch (e) {
  console.error('::error title=清单读不到::' + manifestPath + ' 不在或读不出：' + e.message);
  process.exit(2);
}
if (!Array.isArray(manifest)) {
  console.error('::error title=清单形状不对::' + manifestPath + ' 逐字不是数组 —— 报缺，不猜');
  process.exit(2);
}

const inManifest = new Set(manifest.map((t) => String(t && t.name || '')));
console.log('[reconcile] 清单里逐字 ' + manifest.length + ' 件；缺件表里逐字 ' + missing.length + ' 条');

if (!missing.length) {
  console.log('[reconcile] 缺件表逐字是空的 => 这一轮**没有**缺件（清单逐字是全的）。');
  process.exit(0);
}

const byKind = new Map();
for (const m of missing) {
  const kind = String(m && m.kind || 'unknown');
  if (!byKind.has(kind)) byKind.set(kind, []);
  byKind.get(kind).push(m);
}
for (const [kind, list] of [...byKind.entries()].sort()) {
  console.log('[reconcile] 那一档=' + kind + ' 逐字 ' + list.length + ' 条');
  for (const m of list) {
    console.log('  · ' + String(m.piece || '') + '  [' + kind + '] ' + String(m.detail || ''));
  }
}

let conflict = 0;
for (const m of missing) {
  const piece = String(m && m.piece || '');
  if (piece && inManifest.has(piece)) {
    console.error('::error title=缺件表与清单矛盾::' + piece + ' 逐字在缺件表里、它逐字**又**在那清单里。');
    conflict++;
  }
}
if (conflict) {
  console.error('::error:: 处置：核那两格哪一格错了 —— 而那**不**能靠猜填（按 C-05）。');
  process.exit(1);
}

console.log('[reconcile] 对得上：那清单逐字**不含**那缺件表里的任何一件（缺的几件逐字**看得见**了）。');
console.log('[reconcile] 而那清单逐字只含**取到**的那些件 —— 而那缺的几件逐字在 ' + missPath + ' 里。');
process.exit(0);
