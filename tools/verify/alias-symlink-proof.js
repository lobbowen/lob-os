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

fs.rmSync(W, { recursive: true, force: true });
console.log('== 通过 ' + PASS + '，失败 ' + FAIL + ' ==');
process.exit(FAIL === 0 ? 0 : 1);