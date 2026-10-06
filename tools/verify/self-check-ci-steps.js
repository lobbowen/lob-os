#!/usr/bin/env node
'use strict';

const cp = require('child_process');
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..', '..');
const VERIFY = path.join(ROOT, 'tools', 'verify');

const GATES = [
  { s: 'pty-argv-proof.js',           readsSrc: true,  inject: 2 },
  { s: 'pty-winsize-endian.js',      readsSrc: true,  inject: 2 },
  { s: 'terminal-covers-real-pty.js',readsSrc: true,  inject: 1 },
  { s: 'alias-symlink-proof.js',     readsSrc: true,  inject: 1 },
  { s: 'sysroot-include-proof.js',   readsSrc: false, inject: 0 },
  { s: 'terminal-screen-algorithm.js', readsSrc: false, inject: 0 },
  { s: 'entry-link-proof.js',        readsSrc: false, inject: 0 },
];

const INJECTIONS = [
  {
    file: 'container/native/d3/pty-session.c',
    before: 'execv(argv[0], argv);',
    after:  'execv(argv[0], (char *const[]){argv[0], NULL});',
    gate: 'pty-argv-proof.js',
    why: 'argv 又被截断成只有 argv[0]（就是缺陷1本身）',
  },
  {
    file: 'container/native/d3/pty-session.c',
    before: 'if (session_open(out_fd, (uint8_t)slot, argv) != 0) {',
    after:  'if (session_open(out_fd, (uint8_t)slot, argv[0]) != 0) {',
    gate: 'pty-argv-proof.js',
    why: '调用处退回只传 argv[0]',
  },
  {
    file: 'container/app/src/main/java/lobos/runtime/PtySession.kt',
    before: 'b.order(java.nio.ByteOrder.LITTLE_ENDIAN)\n            b.putShort(rows.toShort()).putShort(cols.toShort())',
    after:  'b.putShort(rows.toShort()).putShort(cols.toShort())',
    gate: 'pty-winsize-endian.js',
    why: 'resize() 去掉小端声明 → 退回 ByteBuffer 默认大端',
  },
  {
    file: 'container/app/src/main/java/lobos/runtime/PtySession.kt',
    before: 'b.order(java.nio.ByteOrder.LITTLE_ENDIAN)\n                rows = b.short.toInt() and 0xFFFF',
    after:  'rows = b.short.toInt() and 0xFFFF',
    gate: 'pty-winsize-endian.js',
    why: 'onReady() 去掉小端声明 → rows=24 会被读成 6144',
  },
  {
    file: 'container/app/src/main/java/lobos/ui/TerminalScreen.kt',
    before: '            else -> Unit',
    after:  '            else -> print(ch)',
    gate: 'terminal-covers-real-pty.js',
    why: '兜底 else -> Unit 改成吐字（未实现的 final 就会漏到屏幕上）',
  },
  {
    file: 'container/app/src/main/java/lobos/os/ProgramIndex.kt',
    before: " || c == '+'",
    after:  '',
    gate: 'alias-symlink-proof.js',
    why: "safeSegment 白名单去掉 '+'（clang++ 建不出链 = 装上了却编不了 C++）",
  },
];

let PASS = 0, FAIL = 0;
const t = (name, cond, detail) => {
  if (cond) { console.log('  [ok]   ' + name); PASS++; }
  else { console.log('  [FAIL] ' + name + (detail ? '\n         ' + detail : '')); FAIL++; }
};

const indent = (s) => String(s).split('\n').map((l) => '           ' + l).join('\n');
const run = (s) => cp.spawnSync('node', [path.join(VERIFY, s)], { encoding: 'utf8', cwd: ROOT });
const sawFail = (r) => r.status !== 0 || /\[FAIL\]/.test(r.stdout || '');

function inject(inj) {
  const p = path.join(ROOT, inj.file);
  const orig = fs.readFileSync(p, 'utf8');
  if (!orig.includes(inj.before)) {
    t(inj.why + '（注入点找不到）', false, '  ' + inj.file + ' 里没有：' + inj.before.slice(0, 60));
    return;
  }
  let injected = false;
  try {
    fs.writeFileSync(p, orig.replace(inj.before, inj.after));
    injected = true;
    const r = run(inj.gate);
    t('A 类：' + inj.gate + ' 因「' + inj.why + '」判红', sawFail(r), '  注入后 rc=' + r.status);
    fs.writeFileSync(p, orig);
    injected = false;
    const back = run(inj.gate);
    if (back.status === 0) {
      t('恢复后 ' + inj.gate + ' 重新变绿', true);
    } else {
      const now = fs.readFileSync(p, 'utf8');
      const srcDirty = now !== orig;
      t('恢复后 ' + inj.gate + ' 重新变绿', false,
        (srcDirty
          ? '  源码没恢复干净（与注入前逐字节不同）—— 恢复逻辑有问题'
          : '  源码**已**恢复干净，但这道门禁在本环境仍红 —— 它有环境依赖')
        + '\n         注入点文件：' + inj.file
        + '\n         门禁输出：\n' + indent((back.stdout || '').trim()));
    }
  } finally {
    if (injected && fs.readFileSync(p, 'utf8') !== orig) fs.writeFileSync(p, orig);
  }
}

console.log('== 一、A 类门禁：源码缺陷必须能让它判红 ==');
const PREV = new Map(INJECTIONS.map((i) => [i.file, fs.readFileSync(path.join(ROOT, i.file), 'utf8')]));
for (const inj of INJECTIONS) inject(inj);

console.log('== 二、B 类门禁：不读源码，必须如实声明 ==');
for (const g of GATES) {
  if (g.readsSrc) continue;
  const src = fs.readFileSync(path.join(VERIFY, g.s), 'utf8');
  const couples = /\.\.\/\.\.\/(container|scripts)\//.test(src);
  t(g.s + ' 确实不读仓内源码（所以判不了源码缺陷）', !couples,
    '  它读了仓内源码，那它就应当属于 A 类，需要给它加注入点');
  const r = run(g.s);
  t(g.s + ' 自身断言全过', r.status === 0, '  rc=' + r.status);
}

console.log('== 三、A 类每个门禁都真的被注中过（不能只数注入点）==');
for (const g of GATES) {
  if (!g.readsSrc) continue;
  const n = INJECTIONS.filter((i) => i.gate === g.s).length;
  t(g.s + ' 至少有一个注入点', n >= 1, '  它归在 A 类（读源码），却没有注入点 —— 新加的？');
  t(g.s + ' 注入点数与声明一致', n === g.inject, '  期望 ' + g.inject + '，实得 ' + n);
}

console.log('== 四、门禁本身不能被改坏 ==');
const allInjReal = INJECTIONS.every((i) =>
  fs.readFileSync(path.join(ROOT, i.file), 'utf8').includes(i.before));
t('每个注入点在当前源码里都能找到（否则实验根本没发生）', allInjReal,
  '  找不到的注入点会让「没红」变得毫无意义 —— 详见下面第一节的实测记录');

console.log('== 五、源码未被污染 ==');
const polluted = [...PREV].filter(([f, c]) => fs.readFileSync(path.join(ROOT, f), 'utf8') !== c).map(([f]) => f);
t('注入过的文件内容与运行前一致（实验没留下坏状态）', polluted.length === 0,
  polluted.length ? '  变了：\n    ' + polluted.join('\n    ') : '');

console.log('== 通过 ' + PASS + '，失败 ' + FAIL + ' ==');
process.exit(FAIL === 0 ? 0 : 1);