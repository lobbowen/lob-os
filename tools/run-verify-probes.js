#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const cp = require('node:child_process');

const VERIFY = path.join(__dirname, '..', 'scripts', 'component-verify.json');
const EXEC = process.argv.includes('--exec');
const ONLY = (() => {
  const i = process.argv.indexOf('--only');
  return i > 0 ? process.argv[i + 1] : null;
})();
const PREFIX = (() => {
  const i = process.argv.indexOf('--prefix');
  return i > 0 ? process.argv[i + 1] : null;
})();

const j = JSON.parse(fs.readFileSync(VERIFY, 'utf8'));
const crit = j.criteria || {};
const names = Object.keys(crit).filter((n) => !ONLY || n === ONLY).sort();

if (!names.length) {
  console.error('没有匹配的探针（--only ' + ONLY + '？）');
  process.exit(2);
}

let failed = 0;
let skipped = 0;
let ran = 0;

for (const name of names) {
  const c = crit[name] || {};
  const label = name.padEnd(9);
  if (!c.node || c.node.length < 20) {
    console.log('[skip] ' + label + ' 探针缺失或过短');
    skipped++;
    continue;
  }
  if (!EXEC) {
    console.log('[list] ' + label + ' 判据 ' + String(c.criterion || '').length
      + ' 字 / 探针 ' + c.node.length + ' 字 / 入口 ' + (c.entry || '(未声明)'));
    continue;
  }
  const env = Object.assign({}, process.env);
  if (PREFIX) env.PATH = PREFIX + path.delimiter + (env.PATH || '');
  const which = cp.spawnSync('sh', ['-c', 'command -v ' + name], { encoding: 'utf8', env });
  if (which.status !== 0) {
    console.log('[skip] ' + label + ' PATH 上没有 ' + name + ' —— 探针按设计用裸名调用，跑不了');
    skipped++;
    continue;
  }
  const hit = String(which.stdout || '').trim();
  const script = path.join(__dirname, 'probe-runner.mjs');
  const r = cp.spawnSync(process.execPath, [script], {
    encoding: 'utf8',
    input: c.node,
    timeout: 180000,
    env,
  });
  const out = String(r.stdout || '').trim();
  const err = String(r.stderr || '').trim();
  if (r.status === 0 && out.includes('LOBOS_PROBE_PASS')) {
    console.log('[ok]   ' + label + ' ' + out.replace(/\s+/g, ' ').slice(0, 100)
      + '  ← ' + hit);
    ran++;
  } else {
    console.log('[FAIL] ' + label + ' 退出码 ' + r.status
      + (err ? ' / ' + err.split('\n')[0].slice(0, 120) : '')
      + (out ? ' / ' + out.split('\n')[0].slice(0, 120) : ''));
    failed++;
  }
}

if (!EXEC) {
  console.log('\n以上只是列出。要真跑：node tools/run-verify-probes.js --exec'
    + '（需要与件同架构的机器，且 PATH 上有这些命令）');
  process.exit(0);
}
console.log('\n跑了 ' + ran + ' 条，跳过 ' + skipped + ' 条，失败 ' + failed + ' 条');
if (failed > 0) process.exit(1);
if (ran === 0 && skipped > 0) {
  process.stderr.write('probe: ' + skipped + ' 条全部被跳过（PATH 上没有对应的命令）'
    + ' —— 这不是通过。真跑要在与件同架构的机器上做。\n');
  process.exit(2);
}
process.exit(0);
