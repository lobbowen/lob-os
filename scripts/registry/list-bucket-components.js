#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..', '..');
const SRC = path.join(ROOT, 'scripts', 'toolchain', 'cache-key.sh');

let src;
try {
  src = fs.readFileSync(SRC, 'utf8');
} catch (e) {
  console.error('::error title=读不到分类真相::' + SRC + ': ' + e.message);
  process.exit(1);
}

const body = /bucket_for\(\)\s*\{[\s\S]*?\n?\}/.exec(src);
if (!body) {
  console.error('::error title=分类真相里没有 bucket_for()::' + SRC);
  process.exit(1);
}

const branches = body[0].split(';;');

const out = [];
for (const br of branches) {
  const m = /([a-z0-9_|-]+)\)\s*echo\s+"(base|rt|tool|lib)"\s*$/.exec(br.trim());
  if (m) { out.push({ name: m[1], bucket: m[2] }); continue; }

  const bad = /([a-z0-9_|-]+)\)\s*echo\s+"([^"]*)"\s*$/.exec(br.trim());
  if (bad) {
    console.error('::error title=筐名不在四筐里::bucket_for() 的 ' + bad[1] + ' 落到了筐 "'
      + bad[2] + '" —— 四筐只有 base / rt / tool / lib');
    process.exit(1);
  }
}

if (!out.length) {
  console.error('::error title=没解析出任何件::' + SRC + ' 的 bucket_for() 里没匹配到 echo "筐名" 的 case 分支');
  process.exit(1);
}

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

const withBucket = process.argv.includes('--buckets');
if (withBucket) {
  for (const [name, bucket] of seen) process.stdout.write(name + '\t' + bucket + '\n');
} else {
  for (const [name] of seen) process.stdout.write(name + '\n');
}