#!/usr/bin/env node
'use strict';

// 二进制件的原生依赖必须由通用机制处理，不能为某个包开后门。
//
// 判据来源（本仓真实事故）：
//   node 的 DT_RUNPATH=$ORIGIN，libc++_shared.so 必须与它同目录，
//   否则裸环境启动报 "cannot locate symbol _ZTVNSt6__ndk1..."。
// 当时的修法是在 PrefixProvisioner 里写 placeNodeDeps 专门给 node 补依赖 ——
// 那是补丁：换个带 $ORIGIN 的二进制还要再写一遍。
//
// 现在改成 Linux 那一套：
//   · ElfFacts 读二进制自己的 ELF 段（DT_NEEDED / DT_RUNPATH）
//   · 安装器按段铺依赖，不问包"你要什么"
//   · 运行时搜索路径统一（等价 ld.so.conf + ldconfig）
//
// 所以判据是「机制在、且不为具体包开后门」，不是「某个函数存在」。

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

// 判据 1：ElfFacts 读得出段里的两样东西
if (!/DT_NEEDED/.test(elf)) {
  problems.push('ElfFacts 不读 DT_NEEDED：拿不到二进制自己要哪些 .so');
}
if (!/DT_RUNPATH|DT_RPATH/.test(elf)) {
  problems.push('ElfFacts 不读 DT_RUNPATH/DT_RPATH：不知道依赖该去哪找');
}

// 判据 2：安装器按段铺依赖
if (!/satisfyElfDeps/.test(pipeline)) {
  problems.push('安装器不按 ELF 段铺依赖：装完的件可能起不来');
}
if (!/ElfFacts\.read/.test(pipeline)) {
  problems.push('安装器没有读 ELF 段：铺依赖无从下手');
}

// 判据 3：依赖找不到时报错，不静默放过
// （装上一个跑不起来的件比装不上更坏：问题推迟到运行时才暴露）
if (!/elf-deps-unresolved/.test(pipeline)) {
  problems.push('依赖铺不齐时安装仍报成功：问题会推迟到运行时，现场更难查');
}

// 判据 4：不为具体包开后门
if (/placeNodeDeps/.test(prefix)) {
  problems.push('PrefixProvisioner 又出现 placeNodeDeps：这是 node 专用补丁，' +
    '应由安装器按 ELF 段统一处理');
}
if (/NODE_DEPS_NAME/.test(prefix)) {
  problems.push('PrefixProvisioner 仍有 NODE_DEPS_NAME：node 专用后门的残留');
}

// 判据 5：运行时搜索路径统一（等价 ld.so.conf）
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