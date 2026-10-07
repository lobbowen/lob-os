#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..');
const problems = [];
const bad = (m) => problems.push(m);

function read(rel) {
  try { return fs.readFileSync(path.join(ROOT, rel), 'utf8'); } catch (_e) { return null; }
}

const jsRel = 'container/engine/src/bridge/protocol.js';
const ktRel = 'container/app/src/main/java/lobos/bridge/CapabilityBroker.kt';
const js = read(jsRel);
const kt = read(ktRel);

if (js === null) {
  console.log('FAIL 桥协议门禁：读不到 ' + jsRel);
  process.exit(1);
}

const jsVer = /const PROTOCOL_VERSION = (\d+);/.exec(js);
if (!jsVer) {
  bad(jsRel + ' 里读不出 `const PROTOCOL_VERSION = <数字>` —— 判据的形状变了，门禁不得当作通过');
} 
let ktMin = null;
if (kt === null) {
  bad('读不到 ' + ktRel + ' —— 壳侧声明的下界无从核对');
} else {
  const m = /const val PROTOCOL_MIN = (\d+)/.exec(kt);
  if (!m) bad(ktRel + ' 里读不出 `const val PROTOCOL_MIN = <数字>`');
  else ktMin = Number(m[1]);
}

let shellProto = null;
const verTxt = read('version.json');
if (verTxt === null) bad('读不到 version.json');
else {
  try {
    shellProto = Number(JSON.parse(verTxt).shell.bridgeProtocol);
    if (!Number.isFinite(shellProto)) bad('version.json 的 shell.bridgeProtocol 不是数字');
  } catch (e) {
    bad('version.json 解析失败：' + e.message);
  }
}

if (jsVer && ktMin !== null && Number(jsVer[1]) !== ktMin) {
  bad('引擎声明 ' + jsVer[1] + ' ≠ 壳侧下界 ' + ktMin +
    ' —— 客户端按壳的下界握手，引擎按自己的版本答，握手会在真机上失败而仓内全绿');
}
if (jsVer && shellProto !== null && Number(jsVer[1]) !== shellProto) {
  bad('引擎 PROTOCOL_VERSION=' + jsVer[1] + ' ≠ version.json 的 shell.bridgeProtocol=' + shellProto +
    ' —— BuildConfig.BRIDGE_PROTOCOL 来自 version.json，program-verify.js 拿它比 requiresProtocol，漂移即程序包装不上');
}
if (ktMin !== null && shellProto !== null && ktMin > shellProto) {
  bad('壳侧下界 ' + ktMin + ' > 上界 ' + shellProto + ' —— 区间为空，所有客户端都会被拒');
}

const agrees = (v, kt) => v === kt;
if (!agrees(1, 1)) {
  console.log('FAIL 判据自证失败：一致的样本被判成不一致');
  process.exit(1);
}
if (agrees(1, 2)) {
  console.log('FAIL 判据自证失败：不一致的样本被判成一致');
  process.exit(1);
}

if (problems.length) {
  console.log('FAIL 桥协议门禁：' + problems.length + ' 处');
  for (const p of problems) console.log('  · ' + p);
  process.exit(1);
}
console.log('PASS 桥协议门禁：引擎 ' + jsVer[1] + ' = 壳侧下界 ' + ktMin + ' = version.json ' + shellProto + '（桥协议三处声明一致）');
process.exit(0);
