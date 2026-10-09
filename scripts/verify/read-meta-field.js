#!/usr/bin/env node
// 从一份 component-meta.json 里读一个字段。
//
//   用法: read-meta-field.js <meta文件> <字段名>
//
// 独立成文件而不是在 shell 里 node -e —— 那样的多层引号在 CI 上很容易
// 悄悄退 1，报错还看不出是哪一层坏了。
//
// 退出码：0=取到了（值可能为空串）；1=文件读不了/JSON 坏/字段不存在
'use strict';
const fs = require('fs');

const [file, field] = process.argv.slice(2);
if (!file || !field) {
  console.error('用法: read-meta-field.js <meta文件> <字段名>');
  process.exit(2);
}

let obj;
try {
  obj = JSON.parse(fs.readFileSync(file, 'utf8'));
} catch (e) {
  process.exit(1);
}
if (!obj || typeof obj !== 'object' || !(field in obj)) process.exit(1);
const v = obj[field];
process.stdout.write(v === null || v === undefined ? '' : String(v));
