'use strict';
const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');

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

// 分类解析复用 scripts/list-bucket-components.js（仓内唯一读 bucket_for 的那份实现）
const CLASS = {};
for (const line of execFileSync(process.execPath,
  [P('scripts/list-bucket-components.js'), '--buckets'],
  { encoding: 'utf8' }).split('\n')) {
  if (!line.trim()) continue;
  const [n, b] = line.split('\t');
  CLASS[n.trim()] = (b || '').trim();
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

// 每件 base 筐的产出源，是「它自己那条独立构建链在不在」这个事实，不是名单。
// 名单会在拆链后过时（曾把 6 件都写成 build-component.yml，而那条链早就不编它们了）——
// 名单过时不会让件坏，但会让人以为这些件还归那条链管。
const WFDIR = P('.github/workflows');
function chainOf(t) {
  return fs.existsSync(path.join(WFDIR, `build-${t}.yml`)) ? `build-${t}.yml` : null;
}
// tag 名由 cache-key.sh 算（唯一真相），门禁不自己拼一份
function bucketTagOf(t) {
  try {
    return execFileSync('bash', [KEY, 'tag', t], { encoding: 'utf8' }).trim();
  } catch (e) {
    return `（cache-key.sh tag ${t} 算不出来：${String(e.message).slice(0, 60)}）`;
  }
}
// 随 APK 打包的原生件：它们没有独立链（产物在 build-apk 的 jniLibs 里）
const NATIVE_VIA_APK = ['bash', 'rg', 'busybox'];

console.log('== base 筐（随 APK 打包的基础环境）逐件复审 ==');
for (const t of baseAll) {
  let how;
  if (NATIVE_VIA_APK.includes(t)) {
    how = '随 APK 原生件（jniLibs）';
  } else {
    const wf = chainOf(t);
    if (wf) {
      how = `${wf} → Release ${bucketTagOf(t)}`;
    } else {
      how = '？没有产出它的独立链';
      problems.push(`[无产出] base 筐的 ${t} 既不是随 APK 的原生件，也没有 build-${t}.yml —— 没人编它`);
    }
  }
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