#!/usr/bin/env node
'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const J = (rel) => path.join(ROOT, 'container/app/src/main/java/lobos', rel);

const problems = [];
const read = (rel) => {
  const p = J(rel);
  if (!fs.existsSync(p)) { problems.push('缺文件 ' + rel); return ''; }
  return fs.readFileSync(p, 'utf8');
};

const elf = read('os/ElfFacts.kt');
const pipeline = read('ota/ProgramInstallPipeline.kt');
const prefix = read('runtime/PrefixProvisioner.kt');
const env = read('os/RuntimeEnvironment.kt');

if (!/DT_NEEDED/.test(elf)) {
  problems.push('ElfFacts 不读 DT_NEEDED：拿不到二进制自己要哪些 .so');
}
if (!/DT_RUNPATH|DT_RPATH/.test(elf)) {
  problems.push('ElfFacts 不读 DT_RUNPATH/DT_RPATH：不知道依赖该去哪找');
}

if (!/resolveRunPath/.test(pipeline)) {
  problems.push('安装器不解析完整 RUNPATH：只判布尔会把 $ORIGIN/../lib 放错目录');
}
if (/hasOriginRunPath/.test(elf)) {
  problems.push('ElfFacts 仍有 hasOriginRunPath 布尔字段：路径本身才是判据，布尔会丢信息');
}

if (!/satisfyElfDeps/.test(pipeline)) {
  problems.push('安装器不按 ELF 段铺依赖：装完的件可能起不来');
}
if (!/ElfFacts\.read/.test(pipeline)) {
  problems.push('安装器没有读 ELF 段：铺依赖无从下手');
}

if (!/elf-deps-unresolved/.test(pipeline)) {
  problems.push('依赖铺不齐时安装仍报成功：问题会推迟到运行时，现场更难查');
}

if (/placeNodeDeps/.test(prefix)) {
  problems.push('PrefixProvisioner 又出现 placeNodeDeps：这是 node 专用补丁，' +
    '应由安装器按 ELF 段统一处理');
}
if (/NODE_DEPS_NAME/.test(prefix)) {
  problems.push('PrefixProvisioner 仍有 NODE_DEPS_NAME：node 专用后门的残留');
}

if (!/fun libSearchPath/.test(env)) {
  problems.push('RuntimeEnvironment 无 libSearchPath：装到 $PREFIX/lib 或程序目录的 .so 运行时找不到');
}
if (!/PrefixProvisioner\.libDir/.test(env)) {
  problems.push('libSearchPath 未包含 $PREFIX/lib：装进 PREFIX 的依赖不可见');
}

if (problems.length) {
  console.log('FAIL 二进制依赖通用机制门禁：' + problems.length + ' 项');
  for (const p of problems) console.log('  ✗ ' + p);
  console.log('');
  console.log('依据：node 的 $ORIGIN linker 失败是真机事故，');
  console.log('当时的 placeNodeDeps 是 node 专用补丁 —— 本门禁守住通用机制，防止回退。');
  process.exit(1);
}

console.log('PASS 二进制依赖通用机制门禁：');
console.log('  · ElfFacts 读 DT_NEEDED 与 DT_RUNPATH（不问包声明什么）');
console.log('  · 安装器按段铺依赖，铺不齐时报错而非静默放过');
console.log('  · 无 node 专用后门（placeNodeDeps / NODE_DEPS_NAME 已不存在）');
console.log('  · 运行时搜索路径统一（APK 原生库 + $PREFIX/lib + 各程序 lib/）');
console.log('');