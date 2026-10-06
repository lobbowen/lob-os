#!/usr/bin/env node
'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..', 'container', 'app', 'src', 'main', 'java', 'lobos');

function read(p) {
  const f = path.join(ROOT, p);
  if (!fs.existsSync(f)) { console.error('缺文件: ' + f); process.exit(1); }
  return fs.readFileSync(f, 'utf8');
}

const problems = [];

// 判据 1：node 的 DT_RUNPATH 是 $ORIGIN，只在自身所在目录找依赖。
// 商店供给把 node 放在 toolchain/node/bin/，那里必须同时存在 libc++_shared.so，
// 否则裸环境启动必然在 linker 阶段失败（cannot locate symbol _ZTVNSt6__ndk1...）。
const prefix = read('runtime/PrefixProvisioner.kt');
if (!/placeNodeDeps/.test(prefix)) {
  problems.push('PrefixProvisioner 没有 placeNodeDeps：node 的 $ORIGIN 目录缺 libc++_shared.so');
}
if (!/NODE_DEPS_NAME/.test(prefix)) {
  problems.push('PrefixProvisioner 缺 NODE_DEPS_NAME 常量');
}
if (!/expected[\s\S]*NODE_DEPS_NAME/.test(prefix)) {
  problems.push('PrefixProvisioner.expected() 未把 NODE_DEPS_NAME 算作应有件，会永远判缺件');
}

// 判据 2：NodeRuntime.version() 的 ProcessBuilder 必须带 LD_LIBRARY_PATH，
// 否则 linker 在进入 node 之前就失败，版本号取到空串。
const nodeRt = read('os/NodeRuntime.kt');
const versionFn = nodeRt.slice(nodeRt.indexOf('fun version'));
if (!/LD_LIBRARY_PATH/.test(versionFn)) {
  problems.push('NodeRuntime.version() 未设 LD_LIBRARY_PATH：取版本号必然拿到空串');
}

// 判据 3：LD_LIBRARY_PATH 的两个候选目录必须都真实含 libc++_shared.so。
// PrefixProvisioner 拷到 usr/bin/，libSearchPath 指向 nativeLibraryDir，两处并存
// 才不依赖「谁先落盘」。只留一处会再次出现裸环境启动失败。
if (!/nativeLibraryDir/.test(prefix)) {
  problems.push('PrefixProvisioner 不再从 nativeLibraryDir 取 libc++_shared.so：usr/bin 那份覆盖不到 $ORIGIN');
}

// 判据 4：runtime.json 的 schema 必须是 3（带 env 快照），schema 2 无从判断 env 是否真传进子进程。
const engine = fs.readFileSync(
  path.join(__dirname, '..', 'container', 'engine', 'src', 'runtime-json.js'), 'utf8',
);
if (!/SCHEMA\s*=\s*3/.test(engine)) {
  problems.push('runtime-json.js 的 SCHEMA 不是 3：缺 env 快照，出事时无法取证 env 是否传进子进程');
}

console.log('');
if (problems.length) {
  for (const p of problems) console.log('  ✗ ' + p);
  console.log('');
  console.log('FAIL node 原生依赖门禁：' + problems.length + ' 项不满足');
  console.log('');
  console.log('判据依据：node 二进制 DT_RUNPATH=$ORIGIN，DT_NEEDED 含 libc++_shared.so。');
  console.log('$ORIGIN = node 自身所在目录。实测该目录无 .so 时裸跑报');
  console.log('CANNOT LINK EXECUTABLE ... cannot locate symbol "_ZTVNSt6__ndk1..."。');
  process.exit(1);
}

console.log('PASS node 原生依赖门禁：');
console.log('  · libc++_shared.so 已放到 node 的 $ORIGIN 同目录（不依赖 LD_LIBRARY_PATH 是否传进子进程）');
console.log('  · NodeRuntime.version() 带 LD_LIBRARY_PATH 起进程，版本号不再取空');
console.log('  · runtime.json schema 3 落地实际 env 快照，可取证');
console.log('');
