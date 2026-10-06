#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..');
const FIXTURE = path.join(ROOT, 'tools/quickapp-fixture');

const problems = [];
const add = (m) => problems.push(m);

const pagesDir = path.join(FIXTURE, 'pages');
if (!fs.existsSync(pagesDir)) {
  console.log('FAIL 页面注册门禁：找不到 ' + pagesDir);
  process.exit(1);
}

const pages = fs.readdirSync(pagesDir).filter((d) =>
  fs.statSync(path.join(pagesDir, d)).isDirectory(),
);

for (const p of pages) {
  const jsPath = path.join(pagesDir, p, 'index.js');
  const jsonPath = path.join(pagesDir, p, 'index.json');
  const wxmlPath = path.join(pagesDir, p, 'index.wxml');

  if (!fs.existsSync(jsPath)) { add(p + '/index.js 不存在'); continue; }
  const js = fs.readFileSync(jsPath, 'utf8');

  if (/export\s+default/.test(js)) {
    add(p + '/index.js 用了 export default —— 编译器会编成 ES module（__esModule + exports），' +
      'modDefine 里没有页面注册，容器报 module not found（真机灰屏十轮的根因）');
  }
  if (!/\b(Page|Component)\s*\(/.test(js)) {
    add(p + '/index.js 没有全局 Page()/Component() 调用');
  }

  if (/\bapp\.[a-zA-Z]/.test(js)) {
    add(p + '/index.js 用了 app.' + (js.match(/\bapp\.[a-zA-Z]+/) || [''])[0].slice(4) +
      ' —— dimina JSSDK 没有注册 app 对象，官方示例也从未用过 app.xxx()');
  }

  if (!fs.existsSync(jsonPath)) {
    add(p + '/index.json 不存在');
  } else {
    const j = JSON.parse(fs.readFileSync(jsonPath, 'utf8'));
    if (!('usingComponents' in j)) {
      add(p + '/index.json 缺 usingComponents —— 运行时靠它决定加载视图模块，' +
        '缺了报 module not found（真机验证过：补上就显示）');
    }
    if ('componentFramework' in j) {
      add(p + '/index.json 有 componentFramework —— 官方 index.json 只有 usingComponents 与 window');
    }
  }

  if (!fs.existsSync(wxmlPath)) {
    add(p + '/index.wxml 不存在（编译器只认 .wxml / .ddml，不认 .ux）');
  }
  if (fs.existsSync(path.join(pagesDir, p, 'index.ux'))) {
    add(p + '/index.ux 不会被编译（DEFAULT_TEMPLATE_EXTS = [".wxml", ".ddml"]）');
  }
}

if (problems.length) {
  console.log('FAIL 页面注册门禁：' + problems.length + ' 处');
  for (const p of problems) console.log('  · ' + p);
  process.exit(1);
}

console.log('PASS 页面注册门禁：' + pages.length + ' 个页面 —— 全局 Page()/Component() 注册（不用 export default）、' +
  'index.json 有 usingComponents、模板用 .wxml、无臆造 app.xxx()');
