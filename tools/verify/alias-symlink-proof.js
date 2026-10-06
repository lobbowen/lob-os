#!/usr/bin/env node
'use strict';

const fs = require('fs');
const path = require('path');
const cp = require('node:child_process');
const os = require('node:os');

const W = fs.mkdtempSync(path.join(os.tmpdir(), 'lobos-alias-'));
const BIN = path.join(W, 'usr', 'bin');
const DEST = path.join(W, 'programs', 'llvmtoolchain', '23.1.3', 'bin');
fs.mkdirSync(BIN, { recursive: true });
fs.mkdirSync(DEST, { recursive: true });

const TOOLS = ['clang', 'clang++', 'ld.lld', 'llvm-ar', 'llvm-nm', 'llvm-strip', 'llvm-objdump', 'llvm-readobj'];
fs.writeFileSync(path.join(DEST, 'package.json'), JSON.stringify({
  name: 'llvmtoolchain',
  version: '23.1.3',
  bin: Object.fromEntries(TOOLS.map(t => [t, 'bin/' + t])),
}, null, 2));
for (const t of TOOLS) {
  fs.writeFileSync(path.join(DEST, t), `#!/bin/sh\necho "${t} 23.1.3"\n`);
  fs.chmodSync(path.join(DEST, t), 0o755);
}

function linkOnlyPrimary() {
  fs.symlinkSync(path.join(DEST, 'clang'), path.join(BIN, 'clang'));
}
function linkPrimaryAndAliases() {
  fs.symlinkSync(path.join(DEST, 'clang'), path.join(BIN, 'clang'));
  for (const t of TOOLS) {
    if (t === 'clang') continue;
    const l = path.join(BIN, t);
    if (fs.existsSync(l)) fs.unlinkSync(l);
    fs.symlinkSync(path.join(DEST, t), l);
  }
}

const whichAll = () => {
  const r = cp.spawnSync('sh', ['-c',
    'for t in ' + TOOLS.join(' ') + '; do if command -v "$t" >/dev/null 2>&1; then echo "有 $t"; else echo "缺 $t"; fi; done',
  ], { env: { ...process.env, PATH: BIN + ':' + process.env.PATH }, encoding: 'utf8' });
  if (r.error) throw new Error('spawnSync 失败: ' + r.error.message);
  if (typeof r.stdout !== 'string') throw new Error('没拿到 stdout，status=' + r.status);
  return r.stdout;
};

let PASS = 0, FAIL = 0;
const t = (name, cond, detail) => {
  if (cond) { console.log('  [ok]   ' + name); PASS++; }
  else { console.log('  [FAIL] ' + name + (detail ? '\n         ' + detail : '')); FAIL++; }
};

console.log('== 别名软链：为什么 clang 件需要它 ==');
console.log('  件内工具: ' + TOOLS.join(', '));
console.log('  entry 只能声明一个: bin/clang\n');

fs.rmSync(BIN, { recursive: true, force: true }); fs.mkdirSync(BIN, { recursive: true });
linkOnlyPrimary();
let out = whichAll();
const missingBefore = out.split('\n').filter(l => l.startsWith('缺')).length;
console.log('  修复前（只建本名链）: 缺 ' + missingBefore + '/' + TOOLS.length + ' 个');
t('修复前 ld.lld 调不到（阶段1c 会卡在这里）', out.includes('缺 ld.lld'));
t('修复前 clang 能调到', out.includes('有 clang'));

fs.rmSync(BIN, { recursive: true, force: true }); fs.mkdirSync(BIN, { recursive: true });
linkPrimaryAndAliases();
out = whichAll();
const missingAfter = out.split('\n').filter(l => l.startsWith('缺')).length;
console.log('  修复后（本名+别名链）: 缺 ' + missingAfter + '/' + TOOLS.length + ' 个');
t('修复后八个工具全部可调', missingAfter === 0, out);
t('修复后 clang 仍可调（没被别名链覆盖掉）', out.includes('有 clang'));

t('clang 链指向件内真实文件',
  fs.realpathSync(path.join(BIN, 'clang')) === fs.realpathSync(path.join(DEST, 'clang')));
t('ld.lld 链指向件内真实文件',
  fs.realpathSync(path.join(BIN, 'ld.lld')) === fs.realpathSync(path.join(DEST, 'ld.lld')));

const r = cp.spawnSync(path.join(BIN, 'ld.lld'), [], { encoding: 'utf8' });
t('经 PATH 找到的 ld.lld 能执行并自报版本', (r.stdout || '').includes('ld.lld 23.1.3'), r.stdout + r.stderr);

console.log('\n== 装侧与发侧必须给出一模一样的别名集合 ==');

const PKG_NAME = 'llvmtoolchain';
const ENTRY_REL = 'bin/clang';
const ENTRY_BASE = 'clang';

function publishSideAliases(bin, pieceName, declaredEntry) {
  const base = String(declaredEntry).split('/').pop();
  return Object.keys(bin).filter((a) => a !== pieceName && a !== base).sort();
}
function publishSideAliasesOld(bin, pieceName) {
  return Object.keys(bin).filter((a) => a !== pieceName).sort();
}

function safeSegment(s) {
  const v = String(s).trim();
  if (!v || v.length > 64 || v === '.' || v === '..') return false;
  return /^[0-9A-Za-z._+-]+$/.test(v);
}
function safeSegmentNoPlus(s) {
  const v = String(s).trim();
  if (!v || v.length > 64 || v === '.' || v === '..') return false;
  return /^[0-9A-Za-z._-]+$/.test(v);
}
function installSideAliases(bin, pieceName, primaryName, safe) {
  return [...new Set(Object.keys(bin)
    .filter((k) => k !== pieceName && k !== primaryName)
    .filter((k) => safe(k)))].sort();
}

const binMap = JSON.parse(fs.readFileSync(path.join(DEST, 'package.json'), 'utf8')).bin;
const pubNow = publishSideAliases(binMap, PKG_NAME, ENTRY_REL);
const insNow = installSideAliases(binMap, PKG_NAME, ENTRY_BASE, safeSegment);

t('发侧与装侧给出一致的别名集合（不一致 = 卸不干净、留死链）',
  JSON.stringify(pubNow) === JSON.stringify(insNow),
  '  发侧 ' + pubNow.join(',') + '\n  装侧 ' + insNow.join(','));

t('clang++ 在两侧都在（它是真命令，缺了就等于编不了 C++）',
  pubNow.includes('clang++') && insNow.includes('clang++'),
  '  发侧 ' + pubNow.join(',') + '；装侧 ' + insNow.join(','));

const pubOld = publishSideAliasesOld(binMap, PKG_NAME);
t('反例：发侧只跳件名时确实与装侧分叉（证明上一条断言不是装饰）',
  JSON.stringify(pubOld) !== JSON.stringify(insNow),
  '  两边竟然一样，那分叉断言就没有意义了');

const insNoPlus = installSideAliases(binMap, PKG_NAME, ENTRY_BASE, safeSegmentNoPlus);
t('反例：白名单去掉 + 后 clang++ 建不出链（证明放行 + 是必要的）',
  !insNoPlus.includes('clang++'),
  '  去掉 + 之后装侧仍有 clang++，那放行 + 就不是必需的');

const cppLink = path.join(BIN, 'clang++-probe');
let cppLinkOk = false;
try {
  if (fs.existsSync(cppLink) || fs.lstatSync.bind) { try { fs.unlinkSync(cppLink); } catch (e) {   } }
  fs.symlinkSync(path.join(DEST, 'clang++'), cppLink);
  cppLinkOk = fs.realpathSync(cppLink) === fs.realpathSync(path.join(DEST, 'clang++'));
  fs.unlinkSync(cppLink);
} catch (e) { cppLinkOk = false; }
t('clang++ 这个名字在真实文件系统上建链成功（实测，不是推断）', cppLinkOk);
const KT_SEG = path.resolve(__dirname, '../../container/app/src/main/java/lobos/os/ProgramIndex.kt');
const ktSeg = fs.readFileSync(KT_SEG, 'utf8');

function kotlinSafeSegmentBody(src) {
  const i = src.indexOf('fun safeSegment(');
  if (i < 0) return null;
  const rest = src.slice(i);
  const m = rest.match(/\n    \}/);
  return m ? rest.slice(0, m.index) : null;
}

const ktBody = kotlinSafeSegmentBody(ktSeg);
t('读得到 Kotlin 的 safeSegment 函数体', ktBody !== null, '  ' + KT_SEG);

if (ktBody) {
  const allowed = [...new Set((ktBody.match(/c == '(.)'/g) || [])
    .map((s) => s.slice("c == '".length, -1)))].sort().join('');
  t("Kotlin 白名单含 '+'（clang++ 是真命令，挡掉就等于编不了 C++）",
    allowed.includes('+'),
    '  Kotlin 当前允许的字符 = "' + allowed + '"（从 ' +
    (ktBody.match(/c == '(.)'/g) || []).length + ' 处 c == 字面量取出）');

  const JS_ALLOWED = '.-+_';
  const ktCore = allowed.replace(/[0-9A-Za-z]/g, '').split('').sort().join('');
  const jsCore = JS_ALLOWED.split('').sort().join('');
  t('JS 复刻的白名单与 Kotlin 一致（两套规则分叉 = 装卸行为不可预测）',
    ktCore === jsCore,
    '  Kotlin = "' + ktCore + '"  JS 复刻 = "' + jsCore + '"');

  const withoutPlus = allowed.replace(/\+/g, '');
  t('反例：拿掉 + 后字符集确实不同（证明上面两条断言不是装饰）',
    withoutPlus !== allowed);
}
fs.rmSync(W, { recursive: true, force: true });
console.log('== 通过 ' + PASS + '，失败 ' + FAIL + ' ==');
process.exit(FAIL === 0 ? 0 : 1);