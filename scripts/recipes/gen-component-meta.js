// 生成件自带的说明 —— 打进件目录，系统靠它知道「这件是什么、什么版本」。
//
// 照抄 deb-control(5)（man7.org / Debian）：
//   「Each Debian binary package contains a control file in its control member」
//   官方标 required 的只有三个：Package / Version / Architecture
//   其余（Section / Priority / Essential / Depends / Provides / …）全是 optional。
//
// 所以我们的说明也只写必需的那几样 + 源码出处：
//   id / version  必填
//   arch         从 ABI 推（arm64-v8a → arm64）
//   essential    对应 Debian 的 Essential: yes（缺了系统起不来）
//   source       对应 Source:
//
// **不写的**（Debian 里也没有，或那个意思与我们无关）：
//   entry / form / installName —— 落位形状自带：有 bin/ 是命令 · 只有 .so 是库
//   applets                   —— busybox 自己 make install 建软链
//   provides                  —— Debian 的 Provides 是「虚拟包名」，
//                                给别人 Depends 用，不是「我能干什么」
//   requiredDeps              —— ELF 头的 DT_NEEDED 就是
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

// 版本：verify 里写了就用（自写件从 1.0.0 起），否则从钉值表的上游版本
const src = sources[id] || {};
const v = verify[id] || {};

// Architecture：Debian 要求必填。我们从 ABI 推（arm64-v8a → arm64）
const ABI = process.env.ABI || 'arm64-v8a';
const arch = ABI.split('-')[0];

const meta = {
  schema: 1,
  id: id,
  version: v.version || src.version || '',
  arch: arch,
  essential: v.required === true,
  source: Array.isArray(src.urls) && src.urls.length ? src.urls[0] : '',
  sourceSha256: src.sha256 || '',
};

if (!meta.version) {
  console.error('::error title=' + id + ' 缺版本::'
    + ' deb-control(5) 把 Version 标为 required —— '
    + '见 scripts/component-sources.json 与 scripts/component-verify.json');
  process.exit(1);
}

fs.mkdirSync(path.dirname(outPath), { recursive: true });
fs.writeFileSync(outPath, JSON.stringify(meta, null, 1) + '\n');
console.log('[meta] ' + id + ' → ' + path.relative(ROOT, outPath)
  + '（version=' + meta.version + ' arch=' + meta.arch
  + (meta.essential ? ' essential' : '') + '）');
