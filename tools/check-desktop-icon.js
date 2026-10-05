#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..');
const ICONS = path.join(ROOT, 'container/app/src/main/java/lobos/quickapp/DesktopIcons.kt');
const BRIDGE = path.join(ROOT, 'container/app/src/main/java/lobos/quickapp/LobosBridge.kt');
const LAUNCH = path.join(ROOT, 'container/app/src/main/java/lobos/quickapp/QuickAppLaunchActivity.kt');
const MANIFEST = path.join(ROOT, 'container/app/src/main/AndroidManifest.xml');
const UNINSTALL = path.join(ROOT, 'container/app/src/main/java/lobos/os/PackageInstaller.kt');
const SCHEMA = path.join(ROOT, 'container/app/src/main/java/lobos/os/ManifestSchema.kt');
const DOC = path.join(ROOT, 'docs/QUICKAPP-ICON.md');

const problems = [];
const add = (m) => problems.push(m);

for (const p of [ICONS, BRIDGE, LAUNCH, MANIFEST, UNINSTALL, SCHEMA, DOC]) {
  if (!fs.existsSync(p)) {
    console.log('FAIL 桌面图标门禁：缺文件 ' + p);
    process.exit(1);
  }
}

const icons = fs.readFileSync(ICONS, 'utf8');
const bridge = fs.readFileSync(BRIDGE, 'utf8');
const launch = fs.readFileSync(LAUNCH, 'utf8');
const manifest = fs.readFileSync(MANIFEST, 'utf8');
const uninstall = fs.readFileSync(UNINSTALL, 'utf8');
const schema = fs.readFileSync(SCHEMA, 'utf8');
const doc = fs.readFileSync(DOC, 'utf8');

for (const api of [
  'isRequestPinShortcutSupported',
  'requestPinShortcut',
  'pinnedShortcuts',
  'disableShortcuts',
]) {
  if (!icons.includes(api)) add('DesktopIcons 没有用到 ' + api);
}

if (!/enum class State/.test(icons)) add('DesktopIcons 没有三档状态枚举');
for (const wire of ['added', 'not_added', 'unsupported']) {
  if (!icons.includes('"' + wire + '"')) add('状态缺一档 ' + wire);
}

if (/removeDynamicShortcuts/.test(icons) || /removeDynamicShortcuts/.test(bridge)) {
  add('用了 removeDynamicShortcuts：它只删 dynamic，摘不掉 pinned（已核 ShortcutManager.java:254）');
}

if (/androidx\.core\.content\.pm/.test(icons)) {
  add('DesktopIcons 依赖 androidx compat：本项目 minSdk=26，平台 API 直接可用，不引 compat');
}

for (const ev of ['desktopIcon.add', 'desktopIcon.remove', 'desktopIcon.state']) {
  if (!bridge.includes('"' + ev + '"')) add('能力面缺事件 ' + ev);
  if (!bridge.includes(ev) || !doc.includes(ev)) add('文档与代码不同步：' + ev);
}

if (!/QuickAppLaunchActivity/.test(manifest)) add('AndroidManifest 没注册 QuickAppLaunchActivity');
if (!/exported="false"/.test(manifest.split('QuickAppLaunchActivity')[1] || '')) {
  add('QuickAppLaunchActivity 必须 exported=false（外部只需经桌面图标进入）');
}
if (!/EXTRA_ID = "lobos\.quickapp\.id"/.test(launch)) add('启动落点缺 EXTRA_ID');
if (!/action = ACTION_LAUNCH/.test(launch)) {
  add('快捷方式 Intent 必须有 action：ShortcutInfo.Builder.setIntent 强制要求，' +
    '缺了抛 NullPointerException（真机崩过一次，Force finishing PanelActivity）');
}
if (/FLAG_ACTIVITY_CLEAR_TASK/.test(launch)) {
  add('快捷方式 Intent 不该带 FLAG_ACTIVITY_CLEAR_TASK：点图标会清掉整个任务栈');
}
if (!/QuickAppHost\.open/.test(launch)) add('启动落点没有转交 QuickAppHost.open');

if (!uninstall.includes('DesktopIcons.withdrawNow')) add('卸载没有联动桌面入口');
if (uninstall.indexOf('DesktopIcons.withdrawNow') < uninstall.indexOf('ProgramIndex.remove')) {
  add('卸载应先摘桌面入口再删索引（顺序反了会漏）');
}

if (!/UI_NAME = "name"/.test(schema) || !/UI_ICON = "icon"/.test(schema)) add('清单缺 ui.name / ui.icon');
if (!/UI_NAME[^\n]*不能为空/.test(schema)) add('ui.name 未做必填校验');
if (!/UI_ICON 必须是件内相对路径/.test(schema)) add('ui.icon 未做越界校验');
if (!doc.includes('摘不掉') && !doc.includes('无法移除')) {
  add('文档没有说清「已固定的桌面入口系统摘不掉」这一硬事实');
}

if (problems.length) {
  console.log('FAIL 桌面图标门禁：' + problems.length + ' 处');
  for (const p of problems) console.log('  · ' + p);
  process.exit(1);
}

console.log('PASS 桌面图标门禁：三档状态只取平台事实；摘除走 disableShortcuts（pinned 摘不掉，已核 AOSP）；' +
  '两个入口齐（甲=extBridge 三事件 / 乙=控制面板）；卸载联动就位；ui.name 必填、ui.icon 限相对路径');
