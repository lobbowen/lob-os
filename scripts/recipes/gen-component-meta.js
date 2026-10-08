// 生成件自带的说明 —— 打进件目录，系统靠它知道「这件是什么」。
//
// 照抄 deb-control(5)：「Each Debian binary package contains a control file in
// its control member」—— 每个包自带说明，内核一个都不知道。
//
// 数据全部来自构建期表（component-sources.json 取版本与源码校验值，
// component-verify.json 取落位形状与是否必需），这里不硬编码任何一件的信息。
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '../..');

function readJson(p) {
  try { return JSON.parse(fs.readFileSync(p, 'utf8')); } catch (_) { return {}; }
}

const [, , id, outPath] = process.argv;
if (!id || !outPath) {
  console.error('用法: node gen-component-meta.js <件名> <输出.json>');
  process.exit(1);
}

const sources = readJson(path.join(ROOT, 'scripts/component-sources.json')).sources || {};
const verify = readJson(path.join(ROOT, 'scripts/component-verify.json')).criteria || {};

// 版本：verify 里写了就用它（自写件从 1.0.0 起），否则从钉值表的上游版本
const src = sources[id] || {};
const v = verify[id] || {};
const version = v.version || src.version || '';

const meta = {
  schema: 1,
  id: id,
  version: version,
  entry: v.entry || '',
  form: v.form || '',
  required: v.required === true,
  installName: v.installName || '',
  applets: Array.isArray(v.applets) ? v.applets : [],
  provides: Array.isArray(v.provides) ? v.provides : [],
  source: Array.isArray(src.urls) && src.urls.length ? src.urls[0] : '',
  sourceSha256: src.sha256 || '',
};

if (!meta.version || !meta.entry || !meta.form) {
  console.error('::error title=' + id + ' 的说明数据不齐::'
    + ' 需要 version/entry/form —— 见 scripts/component-verify.json 的 criteria.' + id);
  process.exit(1);
}

fs.mkdirSync(path.dirname(outPath), { recursive: true });
fs.writeFileSync(outPath, JSON.stringify(meta, null, 1) + '\n');
console.log('[meta] ' + id + ' → ' + path.relative(ROOT, outPath)
  + '（version=' + meta.version + ' entry=' + meta.entry + ' form=' + meta.form + '）');
