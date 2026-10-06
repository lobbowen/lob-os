#!/usr/bin/env node
'use strict';

/**
 * 验证「别名软链」这件事本身：为什么阶段1c 需要它。
 *
 * 这不是测 Kotlin（无编译器），而是**测判据**：
 * 一个叫「clang 的件」若只建本名链，PATH 里到底能不能调到 ld.lld？
 *
 * 做法：真的建目录、真的建软链、真的用 execvp 语义去查 PATH。
 * 用 Linux 语义查（设备上是 Bionic，但 PATH 查找规则一致），
 * 结论对两边都成立。
 */

const fs = require('fs');
const path = require('path');
const cp = require('node:child_process');
const os = require('node:os');

const W = fs.mkdtempSync(path.join(os.tmpdir(), 'lobos-alias-'));
const BIN = path.join(W, 'usr', 'bin');
const DEST = path.join(W, 'programs', 'llvmtoolchain', '23.1.3', 'bin');
fs.mkdirSync(BIN, { recursive: true });
fs.mkdirSync(DEST, { recursive: true });

// 造一个 clang 形态的件：8 个工具，entry 只能声明一个
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

// ── 模拟修复前的 linkEntry：只建本名 ──
function linkOnlyPrimary() {
  fs.symlinkSync(path.join(DEST, 'clang'), path.join(BIN, 'clang'));
}
// ── 模拟修复后的 linkEntry：本名 + 别名 ──
function linkPrimaryAndAliases() {
  fs.symlinkSync(path.join(DEST, 'clang'), path.join(BIN, 'clang'));
  for (const t of TOOLS) {
    if (t === 'clang') continue;
    const l = path.join(BIN, t);
    if (fs.existsSync(l)) fs.unlinkSync(l);
    fs.symlinkSync(path.join(DEST, t), l);
  }
}

// PATH 查询：把 BIN 拼在**原 PATH 之后**，而不是替换掉。
// 替换掉的话 /bin/sh 自己都找不到（execvp 失败、spawnSync 返回 undefined），
// 那样测的就不是「clang 能不能调到」而是「shell 能不能起」了。
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

// 修复前
fs.rmSync(BIN, { recursive: true, force: true }); fs.mkdirSync(BIN, { recursive: true });
linkOnlyPrimary();
let out = whichAll();
const missingBefore = out.split('\n').filter(l => l.startsWith('缺')).length;
console.log('  修复前（只建本名链）: 缺 ' + missingBefore + '/' + TOOLS.length + ' 个');
t('修复前 ld.lld 调不到（阶段1c 会卡在这里）', out.includes('缺 ld.lld'));
t('修复前 clang 能调到', out.includes('有 clang'));

// 修复后
fs.rmSync(BIN, { recursive: true, force: true }); fs.mkdirSync(BIN, { recursive: true });
linkPrimaryAndAliases();
out = whichAll();
const missingAfter = out.split('\n').filter(l => l.startsWith('缺')).length;
console.log('  修复后（本名+别名链）: 缺 ' + missingAfter + '/' + TOOLS.length + ' 个');
t('修复后八个工具全部可调', missingAfter === 0, out);
t('修复后 clang 仍可调（没被别名链覆盖掉）', out.includes('有 clang'));

// 软链真的指向件内
t('clang 链指向件内真实文件',
  fs.realpathSync(path.join(BIN, 'clang')) === fs.realpathSync(path.join(DEST, 'clang')));
t('ld.lld 链指向件内真实文件',
  fs.realpathSync(path.join(BIN, 'ld.lld')) === fs.realpathSync(path.join(DEST, 'ld.lld')));

// 跑一下确认能执行
const r = cp.spawnSync(path.join(BIN, 'ld.lld'), [], { encoding: 'utf8' });
t('经 PATH 找到的 ld.lld 能执行并自报版本', (r.stdout || '').includes('ld.lld 23.1.3'), r.stdout + r.stderr);

// ── 装侧与发侧必须给出一模一样的别名集合（这条判据是补真缺陷的）──
//
// 实测过的两个缺陷，两条都会让「装上了却卸不干净 / 调不到」：
//
//   ① 发侧 publish-userland-manifest.js 的 aliasesOf 只跳「键 == 件名」，
//      没跳「键 == entry 的末段」。llvmtoolchain 件名是 llvmtoolchain、
//      entry 是 bin/clang，于是清单里多出 clang 与 clang++，而装侧
//      两条都不建（它跳 programId 与 primaryName）—— 清单比链多，
//      卸载时按清单删不存在的链，真正建过的那些变成指向已删目录的死链。
//
//   ② 装侧 ProgramIndex.safeSegment 的白名单不含 '+'，于是 clang++
//      建不出链 =「装上了 clang 但编不了 C++」。而判据只跑
//      `clang --version` 与编一个 .c，看不出 C++ 编不了 —— 这种缺失很安静。
//
// 两边各自的修复后规则都复刻在下面，并各配一个反例：
// 反例的意义是「若断言恒真，它就只是装饰」。
console.log('\n== 装侧与发侧必须给出一模一样的别名集合 ==');

const PKG_NAME = 'llvmtoolchain';
const ENTRY_REL = 'bin/clang';
const ENTRY_BASE = 'clang';

// 发侧（已修）：跳过两种「本名」——键==件名、键==entry 末段
function publishSideAliases(bin, pieceName, declaredEntry) {
  const base = String(declaredEntry).split('/').pop();
  return Object.keys(bin).filter((a) => a !== pieceName && a !== base).sort();
}
// 发侧（修复前）：只跳件名 —— 用来造反例
function publishSideAliasesOld(bin, pieceName) {
  return Object.keys(bin).filter((a) => a !== pieceName).sort();
}

// 装侧：ProgramInstallPipeline.aliasNames + ProgramIndex.safeSegment。
// 白名单与仓内 Kotlin 一致：字母数字与 . _ - +；空格与分隔符拒。
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

// 反例①：把发侧退回「只跳件名」，必须与装侧分叉，否则上面那条断言是装饰
const pubOld = publishSideAliasesOld(binMap, PKG_NAME);
t('反例：发侧只跳件名时确实与装侧分叉（证明上一条断言不是装饰）',
  JSON.stringify(pubOld) !== JSON.stringify(insNow),
  '  两边竟然一样，那分叉断言就没有意义了');

// 反例②：白名单去掉 + 后 clang++ 应当从装侧消失
const insNoPlus = installSideAliases(binMap, PKG_NAME, ENTRY_BASE, safeSegmentNoPlus);
t('反例：白名单去掉 + 后 clang++ 建不出链（证明放行 + 是必要的）',
  !insNoPlus.includes('clang++'),
  '  去掉 + 之后装侧仍有 clang++，那放行 + 就不是必需的');

// 反例③：clang++ 的链在真实文件系统上确实建得出来（不是理论可行）
const cppLink = path.join(BIN, 'clang++-probe');
let cppLinkOk = false;
try {
  if (fs.existsSync(cppLink) || fs.lstatSync.bind) { try { fs.unlinkSync(cppLink); } catch (e) { /* 本来就没有 */ } }
  fs.symlinkSync(path.join(DEST, 'clang++'), cppLink);
  cppLinkOk = fs.realpathSync(cppLink) === fs.realpathSync(path.join(DEST, 'clang++'));
  fs.unlinkSync(cppLink);
} catch (e) { cppLinkOk = false; }
t('clang++ 这个名字在真实文件系统上建链成功（实测，不是推断）', cppLinkOk);
// ── 钉住 Kotlin 侧的白名单（JS 复刻盯不住它）──
//
// 上面那些断言用的是 JS 复刻的 safeSegment。复刻与 Kotlin 各改各的 ——
// 实测：把 Kotlin 里的 `|| c == '+'` 删掉，alias-symlink-proof.js 依然全绿。
// 所以这里**读 Kotlin 源码**，判它与复刻一致。
//
// 判据形态：取 safeSegment 的函数体，看它允许的字符集合。
// 只判「含有哪些字符」，不判代码形状 —— 换写法不该判红，改语义才判红。
const KT_SEG = path.resolve(__dirname, '../../container/app/src/main/java/lobos/os/ProgramIndex.kt');
const ktSeg = fs.readFileSync(KT_SEG, 'utf8');

// 取 fun safeSegment 那一段（到下一个空行 + } 之前）
function kotlinSafeSegmentBody(src) {
  const i = src.indexOf('fun safeSegment(');
  if (i < 0) return null;
  const rest = src.slice(i);
  // 函数体到「行首 4 空格 + }」为止
  const m = rest.match(/\n    \}/);
  return m ? rest.slice(0, m.index) : null;
}

const ktBody = kotlinSafeSegmentBody(ktSeg);
t('读得到 Kotlin 的 safeSegment 函数体', ktBody !== null, '  ' + KT_SEG);

if (ktBody) {
  // 它允许的字面字符：形如 c == 'x'
  const allowed = [...new Set((ktBody.match(/c == '(.)'/g) || [])
    .map((s) => s.slice("c == '".length, -1)))].sort().join('');
  t("Kotlin 白名单含 '+'（clang++ 是真命令，挡掉就等于编不了 C++）",
    allowed.includes('+'),
    '  Kotlin 当前允许的字符 = "' + allowed + '"（从 ' +
    (ktBody.match(/c == '(.)'/g) || []).length + ' 处 c == 字面量取出）');

  // 与 JS 复刻对齐：复刻的字符集必须与 Kotlin 一致，否则两套规则会分叉
  const JS_ALLOWED = '.-+_';
  const ktCore = allowed.replace(/[0-9A-Za-z]/g, '').split('').sort().join('');
  const jsCore = JS_ALLOWED.split('').sort().join('');
  t('JS 复刻的白名单与 Kotlin 一致（两套规则分叉 = 装卸行为不可预测）',
    ktCore === jsCore,
    '  Kotlin = "' + ktCore + '"  JS 复刻 = "' + jsCore + '"');

  // 反例：Kotlin 若退回不含 + 的版本，上面两条必须会红
  const withoutPlus = allowed.replace(/\+/g, '');
  t('反例：拿掉 + 后字符集确实不同（证明上面两条断言不是装饰）',
    withoutPlus !== allowed);
}
fs.rmSync(W, { recursive: true, force: true });
console.log('== 通过 ' + PASS + '，失败 ' + FAIL + ' ==');
process.exit(FAIL === 0 ? 0 : 1);