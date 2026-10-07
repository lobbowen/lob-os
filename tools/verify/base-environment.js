'use strict';
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..', '..');
const P = (p) => path.join(ROOT, p);

const PLAN = P('docs/ENV-EXECUTION-PLAN.md');
const CAPS = P('.github/native-capabilities.txt');
const KEY = P('scripts/cache-key.sh');
const BASE_LIBS = P('scripts/build-base-libs.sh');
const BUSYBOX = P('scripts/build-native-busybox.sh');
const CAPS_SH = P('scripts/build-native-capabilities.sh');
const APK_WF = P('.github/workflows/build-apk.yml');

for (const f of [PLAN, CAPS, KEY, BASE_LIBS, BUSYBOX, CAPS_SH, APK_WF]) {
  if (!fs.existsSync(f)) {
    console.error(`FAIL 文件不存在：${f}（ROOT=${ROOT}）`);
    process.exit(2);
  }
}

const problems = [];
const notes = [];

const keySrc = fs.readFileSync(KEY, 'utf8');
const bm = keySrc.match(/bucket_for\(\)\s*\{[\s\S]*?\n\}/);
if (!bm) {
  console.error('FAIL cache-key.sh 里没有 bucket_for()');
  process.exit(2);
}
const CLASS = {};
for (const line of bm[0].split('\n')) {
  const g = /^\s*([a-z|0-9_-]+)\)\s+echo "(\w+)"/.exec(line);
  if (g) for (const t of g[1].split('|')) CLASS[t] = g[2];
}

const baseAll = Object.keys(CLASS).filter((t) => CLASS[t] === 'base').sort();
const nativeCaps = new Set();
for (const l of fs.readFileSync(CAPS, 'utf8').split('\n')) {
  const t = l.trim();
  if (!t || t.startsWith('#')) continue;
  const f = t.split(/\s+/);
  if (f.length >= 3) nativeCaps.add(f[1]);
}

const capsSrc = fs.readFileSync(CAPS_SH, 'utf8');
const libsSrc = fs.readFileSync(BASE_LIBS, 'utf8');
const bbSrc = fs.readFileSync(BUSYBOX, 'utf8');
const apkSrc = fs.readFileSync(APK_WF, 'utf8');
const planSrc = fs.readFileSync(PLAN, 'utf8');

function requireOne(label, ok, why) {
  if (ok) notes.push(`  ok   ${label}`);
  else {
    problems.push(`[缺产出] ${label} —— ${why}`);
  }
}

requireOne('bash → libbash.so 在 CAPABILITY 清单', nativeCaps.has('libbash.so'),
  '清单里没有它，PrefixProvisioner 不会把它铺到 $PREFIX/bin');
requireOne('rg → liblobosrg.so 在 CAPABILITY 清单', nativeCaps.has('liblobosrg.so'),
  '同上是 rg');
requireOne('busybox → libbusybox.so 在 CAPABILITY 清单', nativeCaps.has('libbusybox.so'),
  '同上是 busybox；且 build-native-busybox.sh 必须在');

requireOne('build-native-busybox.sh 产出 libbusybox.so',
  bbSrc.indexOf('libbusybox') >= 0 || /TOOL="busybox"/.test(bbSrc),
  '找不到 busybox 的产出声明');

for (const lib of ['libz', 'libcurl']) {
  requireOne(`${lib}.so 由 build-base-libs.sh 写入 jniLibs`,
    libsSrc.indexOf(`$J/${lib}.so`) >= 0, `脚本里没有 cp 到 $J/${lib}.so`);
}
requireOne('libssl.so / libcrypto.so 由 build-base-libs.sh 产出',
  /for base in ssl crypto\s*;/.test(libsSrc) && libsSrc.indexOf('$J/lib$base.so') >= 0,
  '找不到 for base in ssl crypto 的循环');
requireOne('libc++_shared.so 由 build-apk.yml 拷进 jniLibs',
  apkSrc.indexOf('libc++_shared.so') >= 0 && apkSrc.indexOf('$J/libc++_shared.so') >= 0,
  'C++ 原生件会 CANNOT LINK —— 它不入 CAPABILITY，只能由这条直接拷');

for (const lib of ['libz.so', 'libssl.so', 'libcrypto.so', 'libcurl.so']) {
  requireOne(`${lib} 已登记进 CAPABILITY 清单（PrefixProvisioner 要铺它）`,
    nativeCaps.has(lib), `清单里没有 ${lib}，它不会被铺到 $PREFIX/lib`);
}

const baseInPlan = [];
for (const line of planSrc.split('\n')) {
  if (!line.startsWith('|')) continue;
  for (const t of baseAll) {
    if (line.includes('`' + t + '`') && !baseInPlan.some((x) => x.t === t)) {
      baseInPlan.push({ t, line: line.trim() });
    }
  }
}
const inPlan = new Set(baseInPlan.map((x) => x.t));
for (const t of baseAll) {
  if (!inPlan.has(t)) {
    problems.push(`[计划缺] base 筐的 ${t} 没出现在 ENV-EXECUTION-PLAN §2.3.1 的表里 —— 分类表与代码不一致`);
  }
}

const PUBLISHED_VIA_RELEASE = ['sysroot', 'make', 'cmake', 'pkg-config', 'jq', 'curl'];
const NATIVE_VIA_APK = ['bash', 'rg', 'busybox'];
const NOT_YET = ['llvmtoolchain'];

console.log('== base 筐（随 APK 打包的基础环境）逐件复审 ==');
for (const t of baseAll) {
  let how;
  if (NATIVE_VIA_APK.includes(t)) how = '随 APK 原生件（jniLibs）';
  else if (PUBLISHED_VIA_RELEASE.includes(t)) how = 'build-userland.yml → Release base-*';
  else if (NOT_YET.includes(t)) how = 'ndk-llvm job（尚无发布资产）';
  else how = '？未归类';
  console.log(`  ${t.padEnd(14)} ${how}`);
}

console.log('');
console.log('== 产出源核对 ==');
for (const n of notes) console.log(n);
if (problems.length) {
  console.log('');
  for (const p of problems) console.log(p);
  console.log(`FAIL base 复审：${problems.length} 处`);
  process.exit(1);
}
console.log('');
console.log(`PASS base 复审：base 筐 ${baseAll.length} 件的产出源齐全，与 §2.3.1 一致`);