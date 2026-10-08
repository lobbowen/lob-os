// 生成件的自描述元信息 —— 打进包内，让安装器只读包。
//
// 之前元信息在包外的发布清单里，于是「它是什么」由一份可被替换的外部文件决定；
// 现在包里自带，安装器装完只信包。
//
// 数据来源（都是构建期钉值，不手写）：
//   component-verify.json    entry / requires —— 入口与它需要哪些我们提供的库
//   component-sources.json   version / sha256 / urls —— 版本与源码出处
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '../..');

function readJson(p) {
  try { return JSON.parse(fs.readFileSync(p, 'utf8')); } catch (_) { return {}; }
}

const args = process.argv.slice(2);
const id = args[0];
const version = args[1];
const entry = args[2];
const verifyPath = args[3];
const outPath = args[4];
if (!id || !version || !entry || !verifyPath || !outPath) {
  console.error('用法: node gen-component-meta.js <id> <version> <entry> <verify.json> <out.json>');
  process.exit(1);
}

const verify = readJson(verifyPath);
const sources = readJson(path.join(ROOT, 'scripts/component-sources.json'));
const src = (sources.sources || {})[id] || {};
const v = (verify.criteria || {})[id] || {};

const meta = {
  schema: 1,
  id: id,
  version: src.version ? String(src.version) : String(version),
  entry: entry,
  form: entry.indexOf('lib/') === 0 ? 'lib' : 'exec',
  requires: Array.isArray(v.requires) ? v.requires : [],

  /** 落位后的入口文件名 —— 构建期定，运行期不猜 */
  installName: v.installName || id,

  /**
   * 一个二进制提供多个命令时列出它们（busybox 那种）。
   * 来自 component-verify.json 的 criteria.<件>.applets。
   * 运行期照这份建软链 —— APK 内的 Kotlin 不再列命令名。
   */
  applets: Array.isArray(v.applets) ? v.applets : [],

  /** 这件是不是系统必需的（缺了系统起不来） */
  required: v.required === true,

  source: Array.isArray(src.urls) && src.urls.length ? src.urls[0] : '',
  sourceSha256: src.sha256 || '',
};

fs.mkdirSync(path.dirname(outPath), { recursive: true });
fs.writeFileSync(outPath, JSON.stringify(meta, null, 1) + '\n');
console.log('[meta] ' + id + ' → ' + path.relative(ROOT, outPath) +
  '（version=' + meta.version + ' entry=' + meta.entry + ' form=' + meta.form + '）');
