#!/usr/bin/env node
'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const OTA = path.join(ROOT, 'container/app/src/main/java/lobos/ota');
const HOST = path.join(ROOT, 'container/app/src/main/java/lobos/runtime/InstanceHost.kt');

const problems = [];

function read(p) {
  if (!fs.existsSync(p)) { problems.push('缺文件: ' + path.relative(ROOT, p)); return ''; }
  return fs.readFileSync(p, 'utf8');
}

const updater = read(path.join(OTA, 'ProgramOtaUpdater.kt'));
const selfCheck = read(path.join(OTA, 'ProgramOtaSelfCheck.kt'));
const host = read(HOST);

if (!/stateFile\(context: Context, cfg: Config, programId: String\)/.test(updater)) {
  problems.push('ProgramOtaUpdater.stateFile 没有 (cfg, programId) 维度：各通道共用一份 lastSequence 会互相判重放');
}
if (!/cfg\.channel \+ "-" \+ programId/.test(updater)) {
  problems.push('状态文件名未由「通道 + 程序」拼出');
}

if (!/loadState\(context: Context, cfg: Config, programId: String\)/.test(updater)) {
  problems.push('loadState 没有 (cfg, programId) 维度');
}
if (!/saveState\(context: Context, cfg: Config, programId: String,/.test(updater)) {
  problems.push('saveState 没有 (cfg, programId) 维度');
}

if (/\bloadState\(context\)/.test(updater) || /\bsaveState\(context, st\)/.test(updater)) {
  problems.push('仍有不带维度的 loadState/saveState 调用：会读错通道的状态');
}

if (!/program-feed-install\.json/.test(updater)) {
  problems.push('installId 未固定在 program-feed-install.json：灰度分桶标识必须是设备级唯一');
}

if (!/legacyStateFile/.test(updater)) {
  problems.push('缺向后兼容：旧全局 program-feed-state.json 未被回落读取，' +
    '设备上已推进的序列号会被当成 0 而放过重放');
}

if (!/promotePendingSequence\(context: Context, cfg: Config, programId: String\)/.test(updater)) {
  problems.push('promotePendingSequence 没有 (cfg, programId)：健康通过后推进的是别的通道的序列号');
}
if (!/dropPendingSequence\(context: Context, cfg: Config, programId: String\)/.test(updater)) {
  problems.push('dropPendingSequence 没有 (cfg, programId)');
}
if (!/promotePendingSequence\(this, cfg, programId\)/.test(host)) {
  problems.push('InstanceHost 调 promotePendingSequence 未传 (cfg, programId)');
}
if (!/dropPendingSequence\(this, cfg, programId\)/.test(host)) {
  problems.push('InstanceHost 调 dropPendingSequence 未传 (cfg, programId)');
}

if (!/program-feed-state-" \+ selfCheckSlug/.test(selfCheck)) {
  problems.push('ProgramOtaSelfCheck 未读分维度状态文件：自检会拿错通道的 lastSequence 判「可安装」');
}

console.log('');
if (problems.length) {
  for (const p of problems) console.log('  ✗ ' + p);
  console.log('');
  console.log('FAIL OTA 序列号隔离门禁：' + problems.length + ' 项不满足');
  process.exit(1);
}

console.log('PASS OTA 序列号隔离门禁：');
console.log('  · lastSequence / pendingSequence 按「通道 + 程序」分文件，各算各的');
console.log('  · installId 保持设备级单份（灰度分桶是设备维度）');
console.log('  · 旧全局状态文件有回落读取，升级不丢已推进的序列号');
console.log('  · 健康提交/回滚与自检都走同一维度');
console.log('');
