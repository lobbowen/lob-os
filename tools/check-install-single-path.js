#!/usr/bin/env node
'use strict';

// 商店目录（CatalogClient）与安装器（os/PackageInstaller）是唯一一条安装路径。
//
// 本仓真实踩过的坑，都源于存在平行的第二套实现：
//   · SupplyProvisioner.ensure —— 每次 App 启动无条件从商店拉 node/curl/git/jq/npm/
//     pnpm/sqlite7，绕过注册表与控制面板；它自己下载解压落位，落位规则与安装器不同，
//     于是 node 落在 toolchain/node/bin/ 而 libc++_shared.so 落在 usr/bin/，
//     $ORIGIN RUNPATH 找不到依赖，裸环境启动必然 linker 失败。
//   · ota/ProgramInstaller 与 os/PackageInstaller 各写一遍下载/校验/解压/落位。
//   · program-feed.json 写死单个 URL，而 SupervisorPool 按程序逐个建 InstanceHost、
//     每个都去拉那一个 URL；序列号 lastSequence 又是设备级单份，跨通道互相判重放。
//
// 判据：装包只有一个实现；启动时不装任何件；快应用配对两条路径都走同一份。

const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const J = (rel) => path.join(ROOT, 'container/app/src/main/java', rel);
const files = {
  app: J('lobos/OsApplication.kt'),
  catalog: J('lobos/os/CatalogClient.kt'),
  pkgInstaller: J('lobos/os/PackageInstaller.kt'),
  supply: J('lobos/runtime/SupplyProvisioner.kt'),
  otaInstaller: J('lobos/ota/ProgramInstaller.kt'),
  updater: J('lobos/ota/ProgramOtaUpdater.kt'),
  binder: J('lobos/quickapp/QuickAppBinder.kt'),
  pool: J('lobos/runtime/SupervisorPool.kt'),
  pipeline: J('lobos/ota/ProgramInstallPipeline.kt'),
  feed: path.join(ROOT, 'container/app/src/main/assets/program-feed.json'),
  feedDoc: path.join(ROOT, 'docs/INSTALL-CHANNEL.md'),
};

const problems = [];
const add = (m) => problems.push(m);

const src = {};
for (const [k, p] of Object.entries(files)) {
  if (k === 'feedDoc') { if (!fs.existsSync(p)) add('缺文档 ' + path.relative(ROOT, p)); continue; }
  if (!fs.existsSync(p)) { add('缺文件 ' + k + ' → ' + path.relative(ROOT, p)); return fail(); }
  src[k] = fs.readFileSync(p, 'utf8');
}

// 判据 1：App 启动不得触发任何安装
if (/SupplyProvisioner\.ensure\s*\(/.test(src.app)) {
  add('OsApplication 启动时仍调 SupplyProvisioner.ensure：等于每次开机无条件从商店拉工具链，' +
    '绕过注册表与控制面板（且落位规则与安装器不一致）');
}
if (!/CatalogClient\.refresh/.test(src.app)) {
  add('OsApplication 启动应只刷商店目录（CatalogClient.refresh）');
}

// 判据 2：SupplyProvisioner.ensure 不得有调用方（它就是那条旁路）
const callers = ['lobos/OsApplication.kt', 'lobos/runtime/InstanceHost.kt', 'lobos/os/NodeRuntime.kt',
  'lobos/runtime/OsHostService.kt', 'lobos/bridge/CapabilityBroker.kt', 'lobos/ui/PanelActivity.kt']
  .filter((rel) => {
    const p = J(rel);
    if (!fs.existsSync(p)) return false;
    return /SupplyProvisioner\.ensure\s*\(/.test(fs.readFileSync(p, 'utf8'));
  });
if (callers.length) {
  add('SupplyProvisioner.ensure 仍有调用方：' + callers.join('、') +
    ' —— 工具链应该由「装程序时按 requires 决定」触发，不是开机自动装');
}

// 判据 3：ensure 本身应当退役（保留会造成"下一个人再接上"的风险）
if (/fun ensure\s*\(ctx: Context\)/.test(src.supply)) {
  add('SupplyProvisioner.ensure 仍定义着：这是平行的安装实现，留着就会被再次接上启动路径。' +
    '应删除，或降级为只读目录的纯函数');
}

// 判据 4：取包（下载 + 哈希校验）在两条路各自手里；解包与落位归安装点。
// 下载留在线是对的：商店路的 url 来自目录项，内置件路的 url 来自清单，本来就不同源。
const takeDl = [/httpGetToFile|ResumableDownloader/, /sha256/]
  .filter((r) => r.test(src.pkgInstaller)).length;
if (takeDl < 2) {
  add('os/PackageInstaller 不再自己取包（命中 ' + takeDl + '/2）：' +
    '下载与哈希校验是调用方该提供的能力，不能凭空消失');
}
const unpack = [/unzipFromFile|unzipInto/, /ProgramIndex\.upsert/, /setCurrentVersion/]
  .filter((r) => r.test(src.pipeline)).length;
if (unpack < 3) {
  add('ProgramInstallPipeline 不完整（命中 ' + unpack + '/3）：' +
    '解包、登记、提交 CURRENT 三步必须都在安装点里，不能散回调用方');
}
// 快应用配对放在落位器（ota/ProgramInstaller）里执行，那是安装流程内部的一步，
// 不是"调用方各自记得调"。判据是「安装流程内必配对」，不是「必须在哪个文件」。
const bindInFlow = src.otaInstaller.includes('QuickAppBinder.bindIfQuickApp')
  || src.pipeline.includes('QuickAppBinder.bindIfQuickApp');
if (!bindInFlow) {
  add('安装流程里没有快应用配对：装得上但打不开（点「打开」毫无反应）');
}

// 判据 6：两条路保留（应用商店 / 内置件 OTA），但安装必须落在同一个功能点。
//
// 这是本仓的结构决定：两条路的清单来源与作用不同，拆开是为了不耦合；
// 但安装行为只有一份实现，不能各写一套。
if (!/ProgramInstallPipeline\.install\s*\(/.test(src.pkgInstaller)) {
  add('os/PackageInstaller 没有走 ProgramInstallPipeline：两条路各写一套安装，' +
    '能力会互相缺失（原先 ota 侧没有写注册表与留审计，os 侧没有包内签名校验与后端平铺）');
}
if (!/ProgramInstallPipeline\.install\s*\(/.test(src.updater)) {
  add('ProgramOtaUpdater 没有走 ProgramInstallPipeline：内置件那条路必须与商店路共用安装实现');
}
if (!/enum class From/.test(src.pipeline) || !/STORE/.test(src.pipeline) || !/BUILTIN/.test(src.pipeline)) {
  add('ProgramInstallPipeline.From 必须同时有 STORE 与 BUILTIN：两条路都要能走同一个安装点');
}
// 合并不得丢能力：这几项原先只在其中一条路上有
if (!/QuickAppHost\.install/.test(src.binder)) {
  add('QuickAppBinder 没有把前端送进 dimina：装得上但打不开');
}

// 判据 7：设备级序列号状态不得复活
if (/program-feed-state/.test(src.updater) && !/scoped|按「通道 \+ 程序」隔离/.test(src.updater)) {
  add('ProgramOtaUpdater 又出现 program-feed-state 设备级状态文件：' +
    '序列号是发布方对某程序一次发布的概念，不是设备状态，跨通道/跨程序共用会互相判重放');
}

function fail() {
  console.log('');
  for (const p of problems) console.log('  ✗ ' + p);
  console.log('');
  console.log('FAIL 安装路径单一性门禁：' + problems.length + ' 项不满足');
  process.exit(1);
}

console.log('');
if (problems.length) {
  for (const p of problems) console.log('  ✗ ' + p);
  console.log('');
  console.log('FAIL 安装路径单一性门禁：' + problems.length + ' 项不满足');
  process.exit(1);
}

console.log('PASS 安装路径单一性门禁：');
console.log('  · App 启动只刷商店目录，不装任何件');
console.log('  · SupplyProvisioner.ensure 无调用方（平行的安装实现已退役）');
console.log('  · 取包（下载+哈希）在两条路各自手里；解包/登记/提交 CURRENT 在 ProgramInstallPipeline');
console.log('  · 两条路（应用商店 / 内置件 OTA）共用 ProgramInstallPipeline 一个安装点');
console.log('  · 快应用配对（端口→config.json→注册表→dimina）在安装流程内必发生');
console.log('');
