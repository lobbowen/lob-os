#!/usr/bin/env node
'use strict';

// OTA 序列号必须按「通道 + 程序」隔离。
//
// 判据来源（真机报错，不是推的）：
//   manifest sequence=31 不高于已提交 32 —— 疑似重放，拒绝
// second 通道的清单 sequence=31，canary 通道已把 lastSequence 推进到 32，
// 而 lastSequence 原先是 filesDir 下唯一一份 program-feed-state.json，
// 于是两个通道互相压制：谁先推进，另一个通道的包就再也装不上。
//
// 通道只决定"去哪找新版本"，序列号本就该各通道各算。

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

// 判据 1：stateFile 必须带通道 + 程序维度
if (!/stateFile\(context: Context, cfg: Config, programId: String\)/.test(updater)) {
  problems.push('ProgramOtaUpdater.stateFile 没有 (cfg, programId) 维度：各通道共用一份 lastSequence 会互相判重放');
}
if (!/cfg\.channel \+ "-" \+ programId/.test(updater)) {
  problems.push('状态文件名未由「通道 + 程序」拼出');
}

// 判据 2：loadState / saveState 必须带同样维度
if (!/loadState\(context: Context, cfg: Config, programId: String\)/.test(updater)) {
  problems.push('loadState 没有 (cfg, programId) 维度');
}
if (!/saveState\(context: Context, cfg: Config, programId: String,/.test(updater)) {
  problems.push('saveState 没有 (cfg, programId) 维度');
}

// 判据 3：调用点必须传维度，且不得再有裸调用
if (/\bloadState\(context\)/.test(updater) || /\bsaveState\(context, st\)/.test(updater)) {
  problems.push('仍有不带维度的 loadState/saveState 调用：会读错通道的状态');
}

// 判据 4：installId 保持设备级单份，不得按通道拆
// 灰度分桶是设备维度，一个设备一个 UUID；拆了会导致同设备在各通道命中不同分桶。
if (!/program-feed-install\.json/.test(updater)) {
  problems.push('installId 未固定在 program-feed-install.json：灰度分桶标识必须是设备级唯一');
}

// 判据 5：向后兼容 —— 读不到分维度文件时必须回落旧全局文件
if (!/legacyStateFile/.test(updater)) {
  problems.push('缺向后兼容：旧全局 program-feed-state.json 未被回落读取，' +
    '设备上已推进的序列号会被当成 0 而放过重放');
}

// 判据 6：健康提交/回滚必须带维度，且调用方要能拿到 cfg
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

// 判据 7：自检必须读分维度文件，不得只读旧全局文件
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
