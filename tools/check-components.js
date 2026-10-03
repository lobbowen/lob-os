#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..');
const COMPONENTS = path.join(ROOT, 'components');
const VERIFY = path.join(ROOT, 'scripts', 'userland-verify.json');
const NATIVE_ASSETS = path.join(ROOT, '.github', 'native-assets.txt');
const NATIVE_CAPS = path.join(ROOT, '.github', 'native-capabilities.txt');
const SCRIPTS = path.join(ROOT, 'scripts');

const problems = [];
const bad = (m) => problems.push(m);

function readJson(p) {
  try { return JSON.parse(fs.readFileSync(p, 'utf8')); } catch (e) { bad(p + ' 读不出 JSON：' + e.message); return null; }
}

if (!fs.existsSync(COMPONENTS)) {
  bad('components/ 不存在 —— 三分区（userland / runtime / native）是构建物的唯一类目索引');
  report();
}

const dirs = fs.readdirSync(COMPONENTS, { withFileTypes: true })
  .filter((d) => d.isDirectory())
  .map((d) => d.name)
  .sort();
const want = ['native', 'runtime', 'userland'];
for (const w of want) if (!dirs.includes(w)) bad('components/' + w + '/ 不在（三个类目缺一不可）');
const docs = [path.join(COMPONENTS, 'README.md')];
for (const d of dirs) {
  if (!want.includes(d)) bad('components/' + d + '/ 不在声明的三个类目里（新增类目要先改 components/README.md 的判定规则）');
  const doc = path.join(COMPONENTS, d, 'COMPONENT.md');
  if (!fs.existsSync(doc)) bad('components/' + d + '/COMPONENT.md 缺失（每区一份形态契约）');
  else docs.push(doc);
}
for (const extra of ['PUBLISH.md', 'REHEARSE.md']) {
  const p = path.join(COMPONENTS, 'userland', extra);
  if (fs.existsSync(p)) docs.push(p);
}

const NOT_PORTED_LIST = path.join(COMPONENTS, 'NOT-PORTED.json');
const notPorted = new Set();
if (fs.existsSync(NOT_PORTED_LIST)) {
  const np = readJson(NOT_PORTED_LIST);
  const groups = (np && np.groups) || {};
  for (const [name, g] of Object.entries(groups)) {
    if (!g || typeof g.why !== 'string' || !g.why.trim()) {
      bad('components/NOT-PORTED.json 的组「' + name + '」没写 why —— 不写为什么不搬，后来人只能靠猜');
    }
    for (const p of (g && g.files) || []) notPorted.add(p);
  }
  if (!Object.keys(groups).length) bad('components/NOT-PORTED.json 没有任何组');
} else {
  bad('components/NOT-PORTED.json 缺失（判定为不搬入的脚本要有名单，否则「文档提到但仓内没有」与「承诺了却没搬」分不清）');
}
for (const p of notPorted) {
  if (fs.existsSync(path.join(ROOT, p))) {
    bad('components/NOT-PORTED.json 列了 ' + p + '，但它已经在仓内 —— 清单该清了（留着的意思是「判错了」，会让人以为它仍不可用）');
  }
}

for (const doc of docs) {
  const rel = path.relative(ROOT, doc);
  const txt = fs.readFileSync(doc, 'utf8');
  const refs = new Set();
  for (const m of txt.matchAll(/(?:scripts|tools|container|\.github)\/[A-Za-z0-9._\/-]+\.[a-z]{1,4}/g)) refs.add(m[0]);
  for (const r of refs) {
    if (fs.existsSync(path.join(ROOT, r))) continue;
    if (notPorted.has(r)) continue;
    bad(rel + ' 引用了不存在的 ' + r + ' —— 文档承诺的门禁/脚本不在仓内，读者会照着去找不存在的文件');
  }
}

const verify = readJson(VERIFY);
const criteria = (verify && verify.criteria) || {};
const pieces = Object.keys(criteria).sort();

for (const p of pieces) {
  const c = criteria[p] || {};
  if (typeof c.entry !== 'string' || !c.entry) bad('件 ' + p + ' 没声明 entry');
  else if (c.entry.startsWith('/') || c.entry.split('/').includes('..')) {
    bad('件 ' + p + ' 的 entry 不是合规的件内相对路径：' + c.entry);
  }
  if (typeof c.node !== 'string' || c.node.length < 20) {
    bad('件 ' + p + ' 没有能力判据探针（只看在不在不算数）');
  }
}

const scripts = fs.readdirSync(SCRIPTS);
const userlandBuilds = scripts.filter((s) => /^build-userland-[a-z0-9-]+\.sh$/.test(s))
  .map((s) => s.replace(/^build-userland-/, '').replace(/\.sh$/, ''))
  .sort();
for (const p of userlandBuilds) {
  if (!pieces.includes(p)) {
    bad('scripts/build-userland-' + p + '.sh 产出一颗件，但 scripts/userland-verify.json 里没有它的能力判据 —— 不许发布无判据的件');
  }
}
for (const p of pieces) {
  const has = scripts.includes('build-userland-' + p + '.sh') || scripts.includes('build-' + p + '.sh');
  if (!has) bad('件 ' + p + ' 在通道上声明了，但没有对应的构建脚本（build-userland-' + p + '.sh 或 build-' + p + '.sh）');
}

const nodeInApk = path.join(ROOT, 'container', 'app', 'src', 'main', 'jniLibs', 'arm64-v8a', 'libnode.so');
if (fs.existsSync(nodeInApk)) {
  bad('jniLibs 下有 libnode.so —— node 走商店通道（components/runtime/COMPONENT.md），进 APK 等于 116MB 且无法独立升级');
}

if (fs.existsSync(NATIVE_ASSETS)) {
  const txt = fs.readFileSync(NATIVE_ASSETS, 'utf8');
  for (const line of txt.split('\n')) {
    const t = line.trim();
    if (!t || t.startsWith('#')) continue;
    if (t === 'libnode.so') bad('.github/native-assets.txt 列了 libnode.so —— 它不属于 APK 内原生件清单');
  }
} else {
  bad('.github/native-assets.txt 缺失（APK 内原生件清单，由 scripts/gen-native-assets.js 生成）');
}

if (fs.existsSync(NATIVE_CAPS)) {
  const caps = fs.readFileSync(NATIVE_CAPS, 'utf8');
  for (const line of caps.split('\n')) {
    const t = line.trim();
    if (!t || t.startsWith('#')) continue;
    const parts = t.split(/\s+/);
    if (parts.length < 2) { bad('.github/native-capabilities.txt 有一行形状不对：' + t); continue; }
    const lib = parts[1];
    if (lib === 'libnode.so') bad('.github/native-capabilities.txt 列了 libnode.so —— 它是 runtime 类（走商店），不是小件原生件');
  }
} else {
  bad('.github/native-capabilities.txt 缺失（小件原生件清单，由 scripts/gen-native-assets.js 生成）');
}

const nodeBuild = path.join(SCRIPTS, 'build-node-android.sh');
if (fs.existsSync(nodeBuild)) {
  const src = fs.readFileSync(nodeBuild, 'utf8');
  const m = /^\s*cp\s+.*out\/Release\/node\s+"?\$?\{?OUT_DIR\}?"?\s*$/m.exec(src);
  if (m) bad('build-node-android.sh 把 node 产物 cp 进 OUT_DIR（jniLibs）—— 它必须落 dist/，再由 build-userland-node.sh 落成商店件');
}

report();

function report() {
  if (problems.length) {
    console.log('FAIL 构建物类目门禁：' + problems.length + ' 处');
    for (const p of problems.slice(0, 20)) console.log('  · ' + p);
    process.exit(1);
  }
  const n = pieces.length;
  console.log('PASS 构建物类目门禁：三个类目在位，' + n + ' 颗件（' + pieces.join(' ') + '）声明与脚本对得上，APK 原生件清单不含 runtime 类');
  process.exit(0);
}
