#!/usr/bin/env node
'use strict';
// 生成件的说明 —— 一件一份，装配器只读包里的这份，不查外部清单（照抄 .deb 的 control）。
//
// 用法：
//   node gen-component-meta.js <件名> <版本> <entry> <输出.json>
//
// 四个参数都是必需的：
//   版本从参数拿（不是从钉值表猜 —— 打包时才知道确切版本）
//   entry 从参数拿（形状是事实，由调用方从落位形状看）
//   其余从 scripts/component-sources.json 取

const fs = require('fs');
const path = require('path');
const cp = require('child_process');

const ROOT = path.resolve(__dirname, '../..');
const [, , id, version, entry, outPath] = process.argv;

if (!id || !version || !entry || !outPath) {
  console.error('用法: node gen-component-meta.js <件名> <版本> <entry> <输出.json>');
  process.exit(1);
}

function readJson(p) {
  return JSON.parse(fs.readFileSync(p, 'utf8'));
}

const sources = readJson(path.join(ROOT, 'scripts/component-sources.json')).sources || {};
const src = sources[id] || {};

if (!/^(bin|lib)\//.test(entry)) {
  console.error('::error title=' + id + ' entry 形状不对::' + entry + ' —— 应为 bin/<名> 或 lib/<名>.so');
  process.exit(1);
}

// 依赖：唯一真相源是 cache-key.sh 的 deps_for。
// 用它的 deps-list 子命令 —— 不能用 deps 子命令，那个返回的是缓存键
//（uw-curl-openssl-…）而不是件名。
const deps = (() => {
  try {
    const out = cp.execSync(
      "bash scripts/toolchain/cache-key.sh deps-list " + JSON.stringify(id),
      { cwd: ROOT, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] },
    );
    return out.trim().split(/\s+/).filter(Boolean);
  } catch (e) {
    return [];
  }
})();

const ABI = process.env.ABI || 'arm64-v8a';
const arch = ABI.split('-')[0];

const form = entry.indexOf('lib/') === 0 ? 'lib' : 'exec';

const kind = (process.env.PIECE_KIND || '').trim();

const meta = {
  schema: 1,
  id: id,
  version: version,
  arch: arch,
  entry: entry,
  form: form,
  requires: deps,
  roles: Array.isArray(src.roles) ? src.roles.filter(function (r) { return typeof r === "string" && r !== ""; }) : [],
  essential: src.essential === true,
  source: Array.isArray(src.urls) && src.urls.length ? src.urls[0] : '',
  sourceSha256: src.sha256 || '',
};
if (kind) meta.kind = kind;
if (src.sameAs) meta.sameAs = src.sameAs;

fs.mkdirSync(path.dirname(outPath), { recursive: true });
fs.writeFileSync(outPath, JSON.stringify(meta, null, 1) + '\n');
console.log('[meta] ' + id + ' → ' + path.relative(ROOT, outPath)
  + '（version=' + meta.version + ' entry=' + meta.entry
  + ' form=' + meta.form + (deps.length ? ' requires=' + deps.join(',') : '') + '）');
