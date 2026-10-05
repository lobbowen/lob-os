#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..');
const PORT_BROKER = path.join(ROOT, 'container/app/src/main/java/lobos/os/PortBroker.kt');
const MIN_SIZE = 10000;
const MAX_PORT = 65535;

const problems = [];

if (!fs.existsSync(PORT_BROKER)) {
  console.log('FAIL 端口段门禁：读不到 ' + PORT_BROKER);
  process.exit(1);
}

const src = fs.readFileSync(PORT_BROKER, 'utf8');
const start = /const val RANGE_START\s*=\s*(\d+)/.exec(src);
const end = /const val RANGE_END\s*=\s*(\d+)/.exec(src);

if (!start) problems.push('PortBroker.kt 里读不出 RANGE_START');
if (!end) problems.push('PortBroker.kt 里读不出 RANGE_END');

if (!start || !end) {
  console.log('FAIL 端口段门禁：' + problems.length + ' 处');
  for (const p of problems) console.log('  · ' + p);
  process.exit(1);
}

const s = Number(start[1]);
const e = Number(end[1]);
const size = e - s + 1;

if (size < MIN_SIZE) {
  problems.push('端口段只有 ' + size + ' 个（' + s + '-' + e + '），不足 ' + MIN_SIZE + ' 个');
}
if (s < 1024) problems.push('RANGE_START=' + s + ' 落在特权端口区（<1024）');
if (e > MAX_PORT) problems.push('RANGE_END=' + e + ' 超出合法端口上限 ' + MAX_PORT);
if (e < s) problems.push('RANGE_END(' + e + ') 小于 RANGE_START(' + s + ')');

if (problems.length) {
  console.log('FAIL 端口段门禁：' + problems.length + ' 处');
  for (const p of problems) console.log('  · ' + p);
  process.exit(1);
}

console.log('PASS 端口段门禁：' + s + '-' + e + ' 共 ' + size + ' 个端口（≥' + MIN_SIZE + '，避开特权区与端口上限）');
