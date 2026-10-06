#!/usr/bin/env node
'use strict';

/**
 * 门禁自验证：那七道 CI 门禁**失败时真的会红**吗？
 *
 * 起因是实测结论（不是推测）：七道里**只有三道**真的读仓内源码。
 * 另外四道是用 JS 复刻一份逻辑，然后测那份复刻 —— 复刻和它自己当然一致，
 * 所以「复刻错了」或「Kotlin 错了」它都发现不了。实测记录：
 *
 *   注入「删掉 linkSysrootInclude(ctx) 调用点」 → sysroot-include-proof  rc=0
 *   注入「CJK 宽度 2 改 1」                       → terminal-screen-algorithm rc=0
 *
 * 这类门禁就是装饰。docs/GATE-CLASSIFICATION.md 写的纪律是
 * 「门禁要造一个已知会被它抓到的用例」—— 本文件就是把那个用例固定下来，
 * **并让没抓到的门禁在这里判红**（而不是继续挂在 CI 上装样子）。
 *
 * 两类门禁在这里分开判：
 *   A 类「源码耦合门禁」必须因源码缺陷而红，否则无意义；
 *   B 类「形态/环境门禁」本来就不该读源码，改源码不该影响它 ——
 *      这类只要「源码无关」成立且自身断言通过即可，不强求能抓源码缺陷。
 */

const cp = require('child_process');
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..', '..');
const VERIFY = path.join(ROOT, 'tools', 'verify');

/** 每个脚本：脚本名 / 读不读仓内源码 / A 类应有几次「因源码缺陷而红」 */
const GATES = [
  { s: 'pty-argv-proof.js',           readsSrc: true,  inject: 2 },
  { s: 'pty-winsize-endian.js',      readsSrc: true,  inject: 2 },
  { s: 'terminal-covers-real-pty.js',readsSrc: true,  inject: 1 },
  { s: 'sysroot-include-proof.js',   readsSrc: false, inject: 0 },
  { s: 'terminal-screen-algorithm.js', readsSrc: false, inject: 0 },
  { s: 'alias-symlink-proof.js',     readsSrc: false, inject: 0 },
  { s: 'entry-link-proof.js',        readsSrc: false, inject: 0 },
];

// ── 注入点：每个都必须是「这个门禁声称能抓」的缺陷 ──
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
    // 这道门禁的判据是「既没实现、又不被 else -> Unit 吞掉」的 final 才判红
    // —— 它真正守着的是**兜底分支**。删某个 final 分支它不该红（吞掉本来就在
    // 承诺里，实测 rc=0 是对的）；删兜底它必须红。我第一次拿「删 'J'」和
    // 「CJK 宽度改 1」去试它，两次都是 rc=0 —— 两个注入点都不对题。
    file: 'container/app/src/main/java/lobos/ui/TerminalScreen.kt',
    before: '            else -> Unit',
    after:  '            else -> print(ch)',
    gate: 'terminal-covers-real-pty.js',
    why: '兜底 else -> Unit 改成吐字（未实现的 final 就会漏到屏幕上）',
  },
];

let PASS = 0, FAIL = 0;
const t = (name, cond, detail) => {
  if (cond) { console.log('  [ok]   ' + name); PASS++; }
  else { console.log('  [FAIL] ' + name + (detail ? '\n         ' + detail : '')); FAIL++; }
};

const run = (s) => cp.spawnSync('node', [path.join(VERIFY, s)], { encoding: 'utf8', cwd: ROOT });
const sawFail = (r) => r.status !== 0 || /\[FAIL\]/.test(r.stdout || '');

/** 注入 → 跑 → 恢复（恢复必须真发生，否则源码被留在坏状态） */
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
    // 必须**先恢复再跑第二次** —— 否则这第二次仍是带缺陷的状态，
    // 「恢复后重新变绿」就变成同一个断言跑两遍，绿红都一样。
    fs.writeFileSync(p, orig);
    injected = false;
    const back = run(inj.gate);
    t('恢复后 ' + inj.gate + ' 重新变绿', back.status === 0, '  恢复后 rc=' + back.status);
  } finally {
    if (injected && fs.readFileSync(p, 'utf8') !== orig) fs.writeFileSync(p, orig);
  }
}

console.log('== 一、A 类门禁：源码缺陷必须能让它判红 ==');
for (const inj of INJECTIONS) inject(inj);

console.log('== 二、B 类门禁：不读源码，必须如实声明 ==');
for (const g of GATES) {
  if (g.readsSrc) continue;
  const src = fs.readFileSync(path.join(VERIFY, g.s), 'utf8');
  // 「读仓内源码」的判据是：把仓内路径解析出来并读进来。
  // 用 ../.. 之外的仓内相对路径 + readFileSync 同时出现来判定。
  const couples = /\.\.\/\.\.\/(container|scripts)\//.test(src);
  t(g.s + ' 确实不读仓内源码（所以判不了源码缺陷）', !couples,
    '  它读了仓内源码，那它就应当属于 A 类，需要给它加注入点');
  const r = run(g.s);
  t(g.s + ' 自身断言全过', r.status === 0, '  rc=' + r.status);
}

console.log('== 三、A 类每个门禁都真的被注中过（不能只数注入点）==');
// 第一节已经逐条实测过「注入了会不会红」，这里防的是**清单漂移**：
// 有人往 A 类里加了个新门禁却忘了给注入点，上面会静默没人拦。
for (const g of GATES) {
  if (!g.readsSrc) continue;
  const n = INJECTIONS.filter((i) => i.gate === g.s).length;
  t(g.s + ' 至少有一个注入点', n >= 1, '  它归在 A 类（读源码），却没有注入点 —— 新加的？');
  t(g.s + ' 注入点数与声明一致', n === g.inject, '  期望 ' + g.inject + '，实得 ' + n);
}

console.log('== 四、门禁本身不能被改坏 ==');
// 自检脚本要是自己判自己，它绿了也没意义 —— 所以要求每个脚本都
// 有可执行的退出码，且「注入点必须真实存在于源码里」这一条不能再被绕过。
const allInjReal = INJECTIONS.every((i) =>
  fs.readFileSync(path.join(ROOT, i.file), 'utf8').includes(i.before));
t('每个注入点在当前源码里都能找到（否则实验根本没发生）', allInjReal,
  '  找不到的注入点会让「没红」变得毫无意义 —— 详见下面第一节的实测记录');

console.log('== 五、源码未被污染 ==');
const dirty = cp.spawnSync('git', ['status', '--porcelain', '--', 'container', 'scripts'], { encoding: 'utf8', cwd: ROOT }).stdout.trim();
t('注入实验后 container/ 与 scripts/ 干净', dirty === '', dirty ? '  残留：\n' + dirty : '');

console.log('== 通过 ' + PASS + '，失败 ' + FAIL + ' ==');
process.exit(FAIL === 0 ? 0 : 1);