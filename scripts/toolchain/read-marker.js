#!/usr/bin/env node
// 读「这一件该有的那个文件」这个声明 —— 而它落在产物那一份里（component-sources.json）。
//
// 为什么在这里：01 今天裁的候选 (a) 是「声明在那一件自己那边」，
//   而 03 今天补的那一句把机制说全了：
//   **声明由产出方给**，而打包那一支手上没有那份信息
//   （而 (b)「打包那一支按形态算」已被读数排除：
//    它手上那份 files[] 只覆盖 bin 与 lib 两个目录，别的位置的标记文件它看不见）。
//
// 用法：node read-marker.js <件名>
//   声明存在 => 打印那个文件名；不存在 => 什么都不打印（调用方据此「不查」）。
//
// 而「没声明就不查」不是「缺」：缺字段报缺（C-05）说的是「要填的那一格没有」，
//   而这里是「这一件压根没声明要查什么」—— 那是另一件事。

const fs = require('node:fs');
const path = require('node:path');

const id = process.argv[2];
if (!id) {
  console.error('用法: read-marker.js <件名>');
  process.exit(2);
}

const root = path.resolve(__dirname, '..', '..');
const table = path.join(root, 'scripts', 'component-sources.json');

let doc;
try {
  doc = JSON.parse(fs.readFileSync(table, 'utf8'));
} catch (e) {
  // 读不到表 => 答「没有声明」，而不是猜一个
  process.exit(0);
}

const sources = doc && doc.sources ? doc.sources : {};
const entry = sources[id] || {};
const marker = entry.marker;

if (typeof marker === 'string' && marker.length > 0) {
  process.stdout.write(marker);
}
