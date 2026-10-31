#!/usr/bin/env node
/**
 * 缺件对账：那一清单 vs 那缺件表。
 *
 * 缺陷名（C-102）**那清单与那缺件表逐字对不上账、=> 而而没人核** ——
 *   而那旧版逐字没有这一格：=> 而那缺的几件逐字**不在**那清单里、
 *   => 而那**没人核**、=> 而那事就过去了（=> 而 run 41189310138 的 step 3
 *   逐字缺了 python / sqlite / pkgconf 三件、=> 而那 [declared] 那一行逐字不含那三件）。
 *   => 而按 C-05「缺字段就报缺、不许猜填」：=> 而那缺的几件逐字**必须**在某一格里**看得见**、
 *     => 而而那「看得见」的那一格逐字**能对账**。
 *
 * 而「清单里要不要留一条占位（missing 状态 + 成因）」那一问**归 01 裁**
 * （=> 而本脚本**不**拍板、=> 而只把事实**逐件**摆出来）。
 *
 * 用法：
 *   node scripts/verify/reconcile-missing-pieces.js <缺件表.json> <清单.json>
 *
 * 退出码：
 *   0 = 对得上（=> 而那清单逐字**不含**那缺件表里的任何一件）
 *   1 = 对不上（=> 而那清单里逐字**有**那缺件表里的某一件、=> 而那是矛盾）
 *   2 = 那缺件表读不到（=> 而那按 C-05 报缺）
 */
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
  //  => 而那按 C-05 报缺（=> 而不猜填、=> 而不把它当成「没有缺件」）。
  console.error('::error title=缺件表读不到::' + missPath + ' 不在或读不出：' + e.message);
  console.error('::error:: 处置：先跑 scripts/toolchain/fetch-component-artifacts.sh，=> 而那一档逐字落那缺件表。');
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
  console.log('[reconcile] 缺件表逐字是空的 => 这一轮**没有**缺件（=> 而那清单逐字是全的）。');
  process.exit(0);
}

//  => 而那缺件逐字**逐条**摆出来（=> 而那一件、那一档、那一档的原话）。
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

//  => 而那**矛盾**那一格：=> 而那清单里逐字**有**那缺件表里的某一件、
//    => 而而那两格逐字**互相矛盾**（=> 而那一件逐字既说「缺」又逐字在清单里）。
let conflict = 0;
for (const m of missing) {
  const piece = String(m && m.piece || '');
  if (piece && inManifest.has(piece)) {
    console.error('::error title=缺件表与清单矛盾::' + piece + ' 逐字在缺件表里、=> 而它逐字**又**在那清单里。');
    conflict++;
  }
}
if (conflict) {
  console.error('::error:: 处置：核那两格哪一格错了 —— 而那**不**能靠猜填（按 C-05）。');
  process.exit(1);
}

//  => 而那清单逐字**不含**那缺件表里的任何一件、=> 而那两格逐字**对得上**。
console.log('[reconcile] 对得上：那清单逐字**不含**那缺件表里的任何一件（=> 而那缺的几件逐字**看得见**了）。');
console.log('[reconcile] 而那清单逐字只含**取到**的那些件 —— 而那缺的几件逐字在 ' + missPath + ' 里。');
process.exit(0);
