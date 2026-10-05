#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..');
const INSTALLER = path.join(ROOT, 'container/app/src/main/java/lobos/ota/ProgramInstaller.kt');
const DIR = path.join(ROOT, 'container/app/src/main/java/lobos/ota/ProgramDir.kt');
const DOC = path.join(ROOT, 'docs/QUICKAPP-INSTALL.md');

const problems = [];
const add = (m) => problems.push(m);

for (const p of [INSTALLER, DIR, DOC]) {
  if (!fs.existsSync(p)) {
    console.log('FAIL 快应用包结构门禁：缺文件 ' + p);
    process.exit(1);
  }
}

const inst = fs.readFileSync(INSTALLER, 'utf8');
const dir = fs.readFileSync(DIR, 'utf8');
const doc = fs.readFileSync(DOC, 'utf8');

if (!/const val FRONTEND_DIR = "frontend"/.test(inst)) add('ProgramInstaller 里没有 FRONTEND_DIR = "frontend"');
if (!/const val BACKEND_DIR = "backend"/.test(inst)) add('ProgramInstaller 里没有 BACKEND_DIR = "backend"');

for (const req of ['config.json', 'main/app-config.json', 'main/logic.js']) {
  if (!inst.includes('"' + req + '"')) add('安装器没有校验前端必需文件 ' + req);
}

if (!/fun quickAppDir\(\): File = File\(programRoot, "quickapp"\)/.test(dir)) {
  add('ProgramDir 里没有 quickAppDir()（前端落位位置）');
}

for (const phase of ['postcheck-frontend-missing', 'postcheck-frontend-incomplete', 'postcheck-backend-missing']) {
  if (!inst.includes(phase)) add('安装器缺少拒绝理由 ' + phase);
}

if (!/val stagedFrontend = File\(dest, FRONTEND_DIR\)/.test(inst) ||
    !/stagedFrontend\.renameTo\(frontendTarget\)/.test(inst)) {
  add('安装器没有把前端从版本目录里摘出来（stagedFrontend.renameTo(frontendTarget)）');
}

for (const d of ['frontend/', 'backend/']) {
  if (!doc.includes(d)) add('安装文档里没有画出 ' + d + ' 目录');
}

if (problems.length) {
  console.log('FAIL 快应用包结构门禁：' + problems.length + ' 处');
  for (const p of problems) console.log('  · ' + p);
  process.exit(1);
}

console.log('PASS 快应用包结构门禁：一个包 <version>/{program-manifest.json,frontend/,backend/}；' +
  '前端三个必需文件已校验；前端落位 quickapp/ 有实现；文档与代码一致');
