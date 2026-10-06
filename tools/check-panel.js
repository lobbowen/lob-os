#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..');
const P = (rel) => path.join(ROOT, rel);

const FILES = {
  setup: P('container/app/src/main/java/lobos/ui/setup/SetupActivity.kt'),
  panel: P('container/app/src/main/java/lobos/ui/PanelActivity.kt'),
  main: P('container/app/src/main/java/lobos/MainActivity.kt'),
  layout: P('container/app/src/main/res/layout/activity_main.xml'),
  manifest: P('container/app/src/main/AndroidManifest.xml'),
  broker: P('container/app/src/main/java/lobos/bridge/CapabilityBroker.kt'),
  doc: P('docs/QUICKAPP-PANEL.md'),
};

const problems = [];
const add = (m) => problems.push(m);

for (const [k, p] of Object.entries(FILES)) {
  if (!fs.existsSync(p)) {
    console.log('FAIL 控制面板门禁：缺文件 ' + k + ' → ' + p);
    process.exit(1);
  }
}

const s = {};
for (const [k, p] of Object.entries(FILES)) s[k] = fs.readFileSync(p, 'utf8');

if (!/exported="false"/.test(s.manifest.split('PanelActivity')[1] || '')) {
  add('PanelActivity 必须 exported=false（控制面不对外暴露）');
}
if (!s.layout.includes('panelBtn')) add('主布局没有面板入口按钮');
if (!/panelBtn\.setOnClickListener/.test(s.main)) add('MainActivity 没有接面板入口');
if (!/PanelActivity/.test(s.main)) add('MainActivity 没有引用 PanelActivity');

for (const ev of ['os.appmgr.install', 'os.appmgr.upgrade', 'os.appmgr.uninstall']) {
  if (!s.panel.includes(ev)) add('面板没有 ' + ev + ' 入口');
}
if (!/invokeLocal/.test(s.panel)) add('面板没有走 invokeLocal（同进程调用，不依赖 socket）');
if (!/PortBroker\.list/.test(s.panel)) add('面板没有端口占用视图');

if (!/DesktopIcons\.request/.test(s.panel)) add('面板没有「装桌面」入口（乙入口）');
if (!/DesktopIcons\.withdraw/.test(s.panel)) add('面板没有「移桌面」入口');
if (!/QuickAppHost\.open/.test(s.panel)) add('面板没有「打开快应用」入口');

if (!/Thread\(r, "lobos-panel"\)/.test(s.panel)) add('面板的长耗时动作没有放后台线程（会 ANR）');

if (!/text = "控制面板"/.test(s.setup)) add('引导页没有「控制面板」按钮：能力不就绪时用户完全没法用控制面');
if (!/openPanel/.test(s.setup)) add('SetupActivity 没有 openPanel()');
const panelAt = s.setup.indexOf('panelBtn = Button');
const panelBlock = panelAt >= 0 ? s.setup.slice(panelAt, panelAt + 260) : '';
if (/visibility = View\.GONE/.test(panelBlock)) {
  add('引导页的面板按钮不能默认隐藏（enterBtn 就是这么把控制台挡死的）');
}
if (!/PanelActivity/.test(s.setup)) add('SetupActivity 没有引用 PanelActivity');

if (!/id != INSTALLER_ID/.test(s.broker)) {
  add('invokeLocal 未放行 INSTALLER_ID：控制面板装不了第一个程序（它在装之前就不在 listIds 里）');
}
if (!/issueInstallerSession/.test(s.broker)) add('宿主未签发 installer 会话');
const listenFail = s.broker.slice(
  s.broker.indexOf('private fun onListenFailed'),
  s.broker.indexOf('private fun socketPresent') + 400,
);
if (!/onListenFailed/.test(s.broker) || !/socketPresent/.test(s.broker) ||
    !/addrInUse && socketPresent/.test(listenFail) || !/沿用既有 socket/.test(listenFail)) {
  add('socket 监听失败没区分「已被占用但可用」：App 重启时旧 abstract socket 未释放，' +
    '会同时记一条 OK 和一条 FAIL，诊断里看着像矛盾（真机上出现过一次）');
}
if (!/const val INSTALLER_ID/.test(s.broker)) add('缺 INSTALLER_ID 常量');

if (!/CapabilityBroker\.INSTALLER_ID/.test(s.panel)) {
  add('面板没有持 INSTALLER_ID 身份调用：传目标 id 会被 invokeLocal 的「不在册」拒掉' +
    '（真机实测报「程序不在册」）');
}
if (!/session\.programId\.trim\(\) == INSTALLER_ID\).*return base \+ ApiSpec\.GROUP_SYS/s.test(s.broker)) {
  add('serverGranted 未把 installer 当系统身份：它在 listIds 为空时会被当程序会话，' +
    '拿到空能力集，于是 os.appmgr.install 要的 base 组缺失');
}

for (const must of ['不能反向连宿主', 'mount namespace', 'invokeLocal']) {
  if (!s.doc.includes(must)) add('文档未记「' + must + '」这一实测结论');
}

if (problems.length) {
  console.log('FAIL 控制面板门禁：' + problems.length + ' 处');
  for (const p of problems) console.log('  · ' + p);
  process.exit(1);
}

console.log('PASS 控制面板门禁：入口齐（装/升/卸/开/桌面/端口）；走 invokeLocal 同进程调用' +
  '（不依赖 socket —— abstract socket 在 app 私有命名空间，外部连不上）；' +
  'INSTALLER_ID 已放行否则装不了第一个程序；长耗时在后台线程');
