#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..');
const J = (p) => path.join(ROOT, 'container/app/src/main/java', p);

const FILES = {
  registry: J('lobos/os/ProgramRegistry.kt'),
  schema: J('lobos/os/ManifestSchema.kt'),
  index: J('lobos/os/ProgramIndex.kt'),
  installer: J('lobos/ota/ProgramInstaller.kt'),
  pkgInstaller: J('lobos/os/PackageInstaller.kt'),
  programDir: J('lobos/ota/ProgramDir.kt'),
  quickReg: J('lobos/quickapp/QuickAppRegistry.kt'),
  bridge: J('lobos/quickapp/LobosBridge.kt'),
  broker: J('lobos/bridge/CapabilityBroker.kt'),
  apiSpec: J('lobos/bridge/ApiSpec.kt'),
  guest: J('lobos/runtime/GuestAdapter.kt'),
  instance: J('lobos/runtime/InstanceHost.kt'),
  port: J('lobos/os/PortBroker.kt'),
  doc: path.join(ROOT, 'docs/QUICKAPP-CAPABILITY.md'),
  fixture: path.resolve(ROOT, '../pkg/1.0.0/program-manifest.json'),
};

const problems = [];
const add = (m) => problems.push(m);

for (const [k, p] of Object.entries(FILES)) {
  if (k === 'fixture') continue;
  if (!fs.existsSync(p)) {
    console.log('FAIL 快应用能力门禁：缺文件 ' + k + ' → ' + p);
    process.exit(1);
  }
}

const src = {};
for (const k of Object.keys(FILES)) {
  if (k === 'fixture') continue;
  src[k] = fs.readFileSync(FILES[k], 'utf8');
}

if (/optJSONObject\("ports"\)/.test(src.registry)) {
  add('ProgramRegistry 又在读 "ports".http —— 与 ManifestSchema 的 http 段不一致，http.env 会静默失效');
}
if (!src.registry.includes('optJSONObject(ManifestSchema.SC_HTTP)')) {
  add('ProgramRegistry 未用 ManifestSchema.SC_HTTP 读 http 段');
}
if (!/const val SC_HTTP = "http"/.test(src.schema)) add('ManifestSchema 缺 SC_HTTP');

if (!/const val HT_ENV = "env"/.test(src.schema)) add('ManifestSchema 缺 HT_ENV');
if (!/HT_ENV 不是合法环境变量名/.test(src.schema)) add('http.env 未做变量名校验');

if (!/put\(envName, port\.toString\(\)\)/.test(src.guest)) add('GuestAdapter 没有把端口写进 env');
if (!/httpEnv = spec\?\.http\?\.env/.test(src.instance)) add('InstanceHost 没有把清单 http.env 传下去');

if (!src.installer.includes('bindQuickApp')) add('安装器收尾没有 bindQuickApp');
if (!/fun bindQuickApp/.test(src.installer)) add('bindQuickApp 未实现');
if (!/PortBroker\.claim|resolveHttpPort/.test(src.installer)) add('bindQuickApp 没有分配端口');
if (!/withEndpoint/.test(src.installer)) add('bindQuickApp 没有把后端地址注入前端 config.json');
if (!/QuickAppRegistry\.register/.test(src.installer)) add('bindQuickApp 没有写注册表');
if (!src.installer.includes('loadIntoDimina')) {
  add('安装器没有把前端送进 dimina：装得上但打不开（open 先查 isExistsApp，' +
    '没装进 dimina 就直接 return —— 真机点「打开」毫无反应且不报错）');
}
if ((src.installer.match(/loadIntoDimina\(/g) || []).length < 2) {
  add('loadIntoDimina 只有定义没有调用点：装了端口、注入了 config、写了注册表，' +
    '但前端从没送进 dimina，所以点「打开」毫无反应');
}
if (!/QuickAppHost\.install/.test(src.installer)) add('loadIntoDimina 没有调 QuickAppHost.install');
if (!/QuickAppHost\.ready\(\)/.test(src.installer)) {
  add('装入 dimina 前没查运行时是否就绪（未 init 时 install 会静默失败）');
}
if (!src.quickReg.includes('fun register')) add('QuickAppRegistry.register 未实现');
if (!src.quickReg.includes('uiName = ui.name')) add('注册表没有落 ui.name');
if (!src.quickReg.includes('uiIcon = ui.icon')) add('注册表没有落 ui.icon');

const callSites = src.installer.split('bindQuickApp(context, programId, km.quickAppDir())').length - 1;
if (callSites < 2) {
  add('bindQuickApp 只有 ' + callSites + ' 个调用点：already-installed 分支必须也重做配对，' +
    '否则回滚后重装 / App 重装数据保留 / feed 重复投递都会让 B 阶段配对静默失效');
}

if (!/fun flattenBackend/.test(src.installer)) add('安装器没有把 backend/ 平铺到版本目录根部');
if (!src.installer.includes('postcheck-entry-not-at-root')) add('安装器未校验平铺后入口是否落在版本目录根部');
if (!/packedEntry\.isEmpty\(\)/.test(src.installer)) add('入口判据未防空串（File(root,"") 是目录，会误判为通过）');
if (!/fun rewriteEntry/.test(src.installer)) add('安装器没有改写清单 entry（包内写 backend/x.js，平铺后须变成 x.js）');
if (!src.programDir.includes('existing == null) lobos.os.Desired.RUNNING else base.desired')) {
  add('首次登记的程序 desired 不是 RUNNING：新装程序会停在 STOPPED，后端永不启动' +
    '（真机装成功后 desired=STOPPED，程序在跑 0/1）');
}
if (!src.installer.includes('postcheck-entry-not-in-package')) add('安装器未校验包内入口真实存在（校验器在装前就要用这个路径）');

if (!/val uiName: String/.test(src.index)) add('IndexEntry 缺 uiName');
if (!/val uiIcon: String/.test(src.index)) add('IndexEntry 缺 uiIcon');
if (/uiUrl/.test(src.index)) add('IndexEntry 还留着死字段 uiUrl（无任何写入者）');

if (!src.pkgInstaller.includes('PortBroker.release')) add('卸载没有释放端口');
if (!src.pkgInstaller.includes('DesktopIcons.withdrawNow')) add('卸载没有摘桌面入口');

if (!src.broker.includes('fun invokeLocal')) add('CapabilityBroker 缺 invokeLocal');
if (!/dispatch\(req, holder\)/.test(src.broker)) add('invokeLocal 没有复用 dispatch（会另起一套判据）');
if (!src.broker.includes('serverGranted(session)')) add('invokeLocal 没有复用 serverGranted 授权判据');
if (!/listIds\(this\)\.contains\(id\)/.test(src.broker)) {
  add('invokeLocal 未核对程序是否在册：listIds 读空时 isProgramSession 视作系统会话，会误授 lobos:sys');
}
if (!src.bridge.includes('"invoke"')) add('能力面缺 invoke 事件');
if (!/SCOPE_SYSTEM/.test(src.bridge)) add('invoke 未对 SCOPE_SYSTEM 方法设闸');

if (!fs.existsSync(FILES.doc)) {
  console.log('FAIL 快应用能力门禁：缺 docs/QUICKAPP-CAPABILITY.md');
  process.exit(1);
}
for (const s of ['bindQuickApp', 'invokeLocal', 'backend.remote']) {
  if (!src.doc.includes(s)) add('文档未提 ' + s);
}

if (problems.length) {
  console.log('FAIL 快应用能力门禁：' + problems.length + ' 处');
  for (const p of problems) console.log('  · ' + p);
  process.exit(1);
}

console.log('PASS 快应用能力门禁：http 段读法与校验已对齐（env 注入曾静默失效，已钉死）；' +
  '安装三步配对（分配端口→注入 config.json→写注册表 ui.*）；卸载释放端口；' +
  '能力调用同进程走 dispatch 并复用 serverGranted，且 system 作用域对快应用关闭');
