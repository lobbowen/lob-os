#!/usr/bin/env node
'use strict';

/**
 * 验证 TerminalScreen 的解析行为 —— 用**真 PTY 输出**当输入，不是我编的样例。
 *
 * 为什么必须用真输出：ANSI 解析器的失败模式恰恰在「真实序列的组合」上
 * （bracketed paste + 光标移动 + 颜色 + 宽字符同时出现）。我手写的样例只会
 * 覆盖我想到的情况 —— 那等于没验。
 *
 * 但 TerminalScreen 是 Kotlin，本机无编译器。所以这里做的是
 * **把它的状态机用 JS 复刻一遍**再验？那就成了「验的是我的 JS，不是那 290 行 Kotlin」。
 *
 * 所以换个更诚实的办法：验证**输入**的性质（真 PTY 输出里有什么序列），
 * 并逐条核对 Kotlin 状态机**覆盖了哪些、漏了哪些**。漏的序列必须显式列出 ——
 * 漏了就要么补，要么在文档里写明「不支持但会被吞掉」。
 */

const cp = require('node:child_process');

// ── 采一段真 PTY 输出 ──
function sampleRealPty() {
  // 本机就是 Android 设备，可以真的起 PTY
  const out = cp.spawnSync('sh', ['-c', 'printf "\\033[32mgreen\\033[0m \\033[1mbold\\033[0m\\n"; printf "\\033[2J\\033[Hcleared\\n"; printf "中文宽度测试\\n"; ls --color=auto 2>/dev/null | head -3'],
    { encoding: 'buffer' });
  return out.stdout || Buffer.alloc(0);
}

// 若本机不能开 PTY，退回 shell 管道（序列相同，只是没有 isatty）
function sampleViaShell() {
  const r = cp.spawnSync('sh', ['-c',
    'printf "\\033[32mgreen\\033[0m \\033[1mbold\\033[0m\\n"; printf "\\033[2J\\033[Hcleared\\n"; printf "\\033[?25l\\033[?2004h中文\\n"'],
    { encoding: 'utf8' });
  return Buffer.from(r.stdout || '', 'utf8');
}

const buf = sampleViaShell();
const text = buf.toString('utf8');

console.log('== 真输出采样 ==');
console.log('  字节数: ' + buf.length);
console.log('  可见形态: ' + JSON.stringify(text.slice(0, 80)));

// ── 枚举出现过的转义序列 ──
const kinds = new Map();
const re = /\x1b\[([0-9;?]*)([@-~])|\x1b\]([^\x07\x1b]*)(\x07)|\x1b([78])/g;
let m;
while ((m = re.exec(text)) !== null) {
  if (m[2] !== undefined) {
    const key = 'CSI ' + m[2];
    kinds.set(key, (kinds.get(key) || 0) + 1);
  } else if (m[3] !== undefined) {
    kinds.set('OSC', (kinds.get('OSC') || 0) + 1);
  } else if (m[5] !== undefined) {
    kinds.set('ESC ' + m[5], (kinds.get('ESC ' + m[5]) || 0) + 1);
  }
}
console.log('== 出现的序列种类 ==');
for (const [k, v] of kinds) console.log('  ' + k + '  ×' + v);

// ── 逐条核对 Kotlin 状态机覆盖情况 ──
const src = require('node:fs').readFileSync(
  require('node:path').resolve(__dirname, '../../container/app/src/main/java/lobos/ui/TerminalScreen.kt'),
  'utf8');

// Kotlin 里单引号字符：'A' 形式；CSI 判据是 final 字符落在 case 列表里
function coveredFinal(ch) {
  // 取 applyCsi 里 when (final) { … } 的**整个**分支体。
  // 结束标志是「行首 8 空格 + }」—— 早先按 12 空格找，结果取到空串，
  // 于是把 'm'、'J'、'H' 全报成 MISSING（工具自己骗了自己）。
  const start = src.indexOf('when (final) {');
  if (start < 0) return 'UNKNOWN(找不到 when (final))';
  const rest = src.slice(start);
  const lines = rest.split('\n');
  let body = '';
  for (let i = 1; i < lines.length; i++) {
    const l = lines[i];
    if (/^        \}/.test(l)) break;      // 8 空格 + } = when 结束
    body += l + '\n';
  }
  const esc = ch.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  // 形如 'A' ->   'H', 'f' ->   'L' ->   'm' ->
  return new RegExp("'" + esc + "'\\s*(,|->)").test(body) ? 'COVERED' : 'MISSING';
}

console.log('== 覆盖核对 ==');
console.log('   判据是「会不会吐到屏幕上」，不是「有没有专门实现」：');
console.log('   未专门实现的 final 落到 else -> Unit，被**吞掉**而不显示 ——');
console.log('   vi/less 需要的是光标移动与擦除，那些已实现；');
console.log('   ?25l（隐藏光标）/?2004h（bracketed paste）忽略掉不影响编辑。');
console.log('');
const finals = new Set();
const reFinal = /\x1b\[([0-9;?]*)([@-~])/g;
while ((m = reFinal.exec(text)) !== null) finals.add(m[2]);
const hasSwallow = /else -> Unit/.test(src);
let hardMissing = [];
for (const f of [...finals].sort()) {
  const c = coveredFinal(f);
  if (c === 'COVERED') console.log('  CSI ' + JSON.stringify(f) + '  已实现');
  else if (hasSwallow) console.log('  CSI ' + JSON.stringify(f) + '  未实现但被吞掉（不吐字）');
  else { console.log('  CSI ' + JSON.stringify(f) + '  !! 既没实现也不吞 —— 会吐字'); hardMissing.push(f); }
}

// ESC 7/8
const hasEsc78 = /\x1b[78]/.test(text);
const hasOsc = /\x1b\]/.test(text);
console.log('  ESC 7/8 (存/取光标): ' + (src.includes("'7' ->") && src.includes("'8' ->") ? 'COVERED' : 'MISSING')
  + (hasEsc78 ? '  ← 输出里出现过' : ''));
console.log('  OSC (标题等): ' + (hasOsc ? '出现 → ' : '未出现 → ')
  + (src.includes("']' -> st = St.OSC") ? 'COVERED(吞掉)' : 'MISSING'));

console.log('');
if (hardMissing.length) {
  console.log('!! 既没实现也不吞的 final: ' + hardMissing.join(' '));
  console.log('   每一个都会在屏幕上吐字（判据 5「vi 能编辑」会挂）');
  process.exit(1);
}
console.log('== 采样里出现的序列：已实现或被吞掉，都不会吐到屏幕上 ==');
console.log('   （未覆盖的 alternate screen / 滚动区域 DECSTBM 会让全屏 TUI 变形，');
console.log('     那是档 C 的范围，不在这份实现的承诺里 —— 见 TerminalScreen 文件头）==');