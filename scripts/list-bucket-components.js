#!/usr/bin/env node
// 三筐里每一件的名字，一行一个。
//
// 唯一真相是 scripts/cache-key.sh 的 bucket_for() —— 那里的 case 表就是分类的
// 唯一定义处。这里不重新抄一份，改成从它解析，避免两处清单漂移。
//
// 判据：读的是分类函数的 case 分支本身，不是它的排列或缩进。
'use strict';

const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..');
const SRC = path.join(ROOT, 'scripts', 'cache-key.sh');

let src;
try {
  src = fs.readFileSync(SRC, 'utf8');
} catch (e) {
  console.error('::error title=读不到分类真相::' + SRC + ': ' + e.message);
  process.exit(1);
}

// 函数体到收尾的 } 为止（允许 } 紧跟同一行）
const body = /bucket_for\(\)\s*\{[\s\S]*?\n?\}/.exec(src);
if (!body) {
  console.error('::error title=分类真相里没有 bucket_for()::' + SRC);
  process.exit(1);
}

// 按 case 分支的终止符 ;; 切，而不是按行切。
// 判据是「一个 case 分支里声明了哪些件、落到哪个筐」这个行为事实，
// 与这个分支写成一行还是几行无关（只认行会把排版差异误报成分类缺失）。
const branches = body[0].split(';;');

const out = [];
for (const br of branches) {
  const m = /([a-z0-9_|-]+)\)\s*echo\s+"(base|rt|tool)"\s*$/.exec(br.trim());
  if (m) { out.push({ name: m[1], bucket: m[2] }); continue; }

  // 这是一个 case 分支（有 件名)），但没落到真筐名上 → 分类被改坏了。
  // 不说清楚的话，下游只会看到「件清单为空」，把分类错误误读成「一件都没有」（纪律 13）。
  const bad = /([a-z0-9_|-]+)\)\s*echo\s+"([^"]*)"\s*$/.exec(br.trim());
  if (bad) {
    console.error('::error title=筐名不在三筐里::bucket_for() 的 ' + bad[1] + ' 落到了筐 "'
      + bad[2] + '" —— 三筐只有 base / rt / tool（见 docs/ENV-EXECUTION-PLAN.md）');
    process.exit(1);
  }
}

if (!out.length) {
  console.error('::error title=没解析出任何件::' + SRC + ' 的 bucket_for() 里没匹配到 echo "筐名" 的 case 分支');
  process.exit(1);
}

// 同一件不许出现两次（那会让下游两个分支去取同一个 artifact）
const seen = new Map();
for (const e of out) {
  for (const t of e.name.split('|')) {
    const n = t.trim();
    if (!n) continue;
    if (seen.has(n)) {
      console.error('::error title=件名重复::' + n + ' 在 bucket_for() 里出现两次（筐 ' + seen.get(n) + ' 与 ' + e.bucket + '）');
      process.exit(1);
    }
    seen.set(n, e.bucket);
  }
}

// 默认只输出件名（一行一个）；--buckets 时输出「件名<TAB>筐名」，
// 给需要按筐分组的读者用（同一套解析，不另开一份实现 —— 纪律 16）。
const withBucket = process.argv.includes('--buckets');
if (withBucket) {
  for (const [name, bucket] of seen) process.stdout.write(name + '\t' + bucket + '\n');
} else {
  for (const [name] of seen) process.stdout.write(name + '\n');
}