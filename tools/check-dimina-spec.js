#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..');
const P = (rel) => path.join(ROOT, rel);

const FILES = {
  host: P('container/app/src/main/java/lobos/quickapp/QuickAppHost.kt'),
  pkg: P('container/app/src/main/java/lobos/quickapp/QuickAppPackage.kt'),
  schema: P('container/app/src/main/java/lobos/os/ManifestSchema.kt'),
  installer: P('container/app/src/main/java/lobos/ota/ProgramInstaller.kt'),
  app: P('container/app/src/main/java/lobos/OsApplication.kt'),
  doc: P('docs/DIMINA-SPEC.md'),
};

const problems = [];
const add = (m) => problems.push(m);

for (const [k, p] of Object.entries(FILES)) {
  if (!fs.existsSync(p)) {
    console.log('FAIL dimina 规范门禁：缺文件 ' + k + ' → ' + p);
    process.exit(1);
  }
}

const s = {};
for (const [k, p] of Object.entries(FILES)) s[k] = fs.readFileSync(p, 'utf8');

// 官方规范（android/README.md）：
//   config.json 必含 path（入口路径）；zip 文件名 = appId；MiniProgram(path = 入口路径)
if (!/const val UI_ENTRY = "entry"/.test(s.schema)) {
  add('清单没有 ui.entry —— dimina 靠它决定打开哪一页，缺了容器会起来但没有入口（灰屏）');
}
if (!s.schema.includes('UI_ENTRY') || !s.schema.includes('不能为空')) add('ui.entry 未做必填校验');
if (!s.schema.includes('必须是件内相对路径')) add('ui.entry 未做越界校验');

if (!/fun withEntry/.test(s.pkg)) add('QuickAppPackage 没有 withEntry（装入时把入口写进 config.json）');
if (!/fun entryOf/.test(s.pkg)) add('QuickAppPackage 没有 entryOf（打开时从 config.json 读入口）');

if (/path = null/.test(s.host)) {
  add('MiniProgram 仍传 path = null：官方规范要求传入口路径，缺了必然灰屏');
}
if (!/path = entryPath/.test(s.host)) add('MiniProgram 没有把 config.json 的 path 传进去');
if (!/resolve\(id \+ "\.zip"\)/.test(s.host)) {
  add('zip 文件名不是 <appId>.zip —— 官方规范要求 zip 名与 appId 一致');
}
if (!/entry: String/.test(s.host)) add('install 没有入口页参数');
if (!/withEntry\(packageDir, entry\)/.test(s.host)) add('install 没有把入口写进 config.json');
if (!/uiEntryOf/.test(s.installer)) add('安装器没有从清单读 ui.entry 传给 install');

if (!/setVirtualFilePrefix/.test(s.app)) {
  add('Dimina.init 没有 setVirtualFilePrefix：官方文档写明「必须在 SDK 初始化时设置，' +
    '原生路径解析与逻辑层会使用同一前缀」；不设则视图层 WebView 解析不了路径、' +
    '渲染进程压根不起（真机实测：逻辑层 appShow/pageShow 正常，就是灰屏）');
}

for (const must of ['入口路径', '与 appId 一致', 'pages_*.js']) {
  if (!s.doc.includes(must)) add('DIMINA-SPEC.md 未记「' + must + '」这条官方约束');
}

if (problems.length) {
  console.log('FAIL dimina 规范门禁：' + problems.length + ' 处');
  for (const p of problems) console.log('  · ' + p);
  process.exit(1);
}

console.log('PASS dimina 规范门禁：按官方 android/README.md 填参数 —— config.json 带 path、' +
  'zip 名 = appId、MiniProgram 传入口路径；清单 ui.entry 必填且限相对路径。' +
  '不做「把 pages_*.js 合并进 logic.js」那类补丁：逻辑与视图分开是官方设计');
