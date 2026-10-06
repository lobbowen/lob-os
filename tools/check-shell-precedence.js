#!/usr/bin/env node
'use strict';

/**
 * 扫 shell 里的 `A || B && C` 优先级陷阱。
 *
 * 为什么需要它：bash 里 `&&` 与 `||` **同优先级、左结合**，所以
 *
 *     command -v cmake || sudo apt-get update && sudo apt-get install cmake
 *
 * 实际是 `(command -v cmake || sudo apt-get update) && sudo apt-get install cmake`
 * —— install **无条件执行**。读起来像「有 cmake 就跳过」，其实每次都跑。
 *
 * 判据只抓**真正危险**的那种：`||` 右边接的不是测试而是命令。
 * 下面这种是安全的（两边都是测试，`||` 只在有测试失败时才触发），不能误报：
 *
 *     [ -n "$X" ] && [ -d "$Y" ] || die "无 X"
 *
 * 本机**没有编译器**，所以这个检查用 node 实现而不是 shell：
 * 但它判的是「文本形状」，不是语义 —— 所以它自己也要配反例。
 */

const fs = require('node:fs');
const path = require('node:path');

// 本文件在 tools/ 下（不是 tools/verify/），所以只往上**一层**就是仓根。
// 照抄 tools/verify/ 里那些脚本的 '../..' 会爬到仓外的 work/ —— 那时不是
// 「没找到文件」而是直接 ENOENT 崩掉，表现为「脚本一声不吭、rc=1」，
// 很难看出是路径写错。（这个坑我自己栽过：先写 '../..'，跑出来没有任何输出。）
const ROOT = path.resolve(__dirname, '..');

let PASS = 0, FAIL = 0;
const t = (name, cond, detail) => {
  if (cond) { console.log('  [ok]   ' + name); PASS++; }
  else { console.log('  [FAIL] ' + name + (detail ? '\n         ' + detail : '')); FAIL++; }
};

// 明确只扫**我们自己写的**文件：scripts/ 与 .github/workflows/。
// 第一版用全仓 walk，把 src/ 下 vendored 的 mingw-w64 源码也扫进来了
// （200+ 处误报，且那些文件我们不改）。范围本身就是判据的一部分。
const SKIP_DIRS = new Set(['node_modules', '.git', 'work', 'dist', 'build']);
const SCAN_ROOTS = ['scripts', path.join('.github', 'workflows')];
function walk(dir, out) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (SKIP_DIRS.has(e.name)) continue;
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (/\.(sh)$/.test(e.name) || /\.ya?ml$/.test(e.name)) out.push(p);
  }
  return out;
}

/** 一段文本看起来是不是「测试」：[ ... ] / test / [ x = y ] / command -v ... 等 */
function looksLikeTest(s) {
  const t = s.trim();
  if (!t) return false;
  if (/^\[\s.*\]$/.test(t)) return true;                       // [ -n "$X" ]
  if (/^test\b/.test(t)) return true;                          // test -f ...
  if (/^command -v\b/.test(t)) return true;                    // command -v cmake
  if (/^\[\[.*\]\]$/.test(t)) return true;                     // bash [[ ]]
  if (/^(hash|type|-)\b/.test(t)) return true;
  return false;
}

/**
 * 按 bash 的左结合语义，把一行切成 A op B op C，并返回**每个运算符右边的段**。
 *
 * `&&` 与 `||` 同优先级、从左往右，所以第一个运算符先结合：
 *   A && B || C   →  (A && B) || C      → 两个运算符的右段分别是 B、C
 *   A || B && C   →  (A || B) && C      → 右段分别是 B、C
 * 也就是说**不论哪种写法，运算符右段就是「第一个运算符之后的那段」**。
 *
 * 我第一版按「|| 到下一个 && 之间」切，于是
 * `[ -n "$X" ] && [ -d "$Y" ] || die` 里 || 在 && 之后，
 * 取到的是 `die` —— 把一个安全的惯用法误判成危险。已按左结合改。
 */
function operatorSegments(line) {
  const first = line.search(/&&|\|\|/);
  if (first < 0) return null;
  const op = line.slice(first, first + 2);
  const rest = line.slice(first + 2);
  const next = rest.search(/&&|\|\|/);
  const seg = next < 0 ? rest : rest.slice(0, next);
  return { op, right: seg.replace(/\s*2>&1\s*$/, '').trim() };
}

const files = SCAN_ROOTS.flatMap((r) => walk(path.join(ROOT, r), []));
console.log('== `A || B && C` 优先级陷阱 ==');
console.log('   范围：scripts/ 与 .github/workflows/（共 ' + files.length + ' 个文件）');
console.log('');

let checked = 0;
for (const f of files) {
  const rel = path.relative(ROOT, f);
  fs.readFileSync(f, 'utf8').split('\n').forEach((line, i) => {
    // 跳过注释与散文行：这道门禁自己的说明文字里就写着 `A || B && C`，
    // 不跳过的话它会把自己判红（第一版就栽在这）。
    const st = line.trimStart();
    if (st.startsWith('#')) return;
    // 只跳过 YAML 的 step 名/键行。**不要**按「像散文」去跳 —— 那会漏掉真缺陷：
    // 一条有害的命令完全可以写在一行里而旁边有破折号。
    if (st.startsWith('-') || st.startsWith('name:')) return;
    if (!/\|\|/.test(line)) return;
    if (!/&&/.test(line)) return;
    if (/\|\|=/.test(line)) return;          // 赋值，不是逻辑
    const seg = operatorSegments(line);
    if (seg === null || !seg.right) return;
    checked++;
    // 只有「|| 在前」才危险，因为左结合：
    //   A || B && C  → (A || B) && C  → C 无条件执行   ← 危险
    //   A && B || C  → (A && B) || C  → C 只在前面失败时跑 ← 安全惯用法
    //
    // 我第一版判「只要同时出现 || 与 && 就危险」，结果把仓里两处
    // `[ -e "$f" ] || continue` + `has_exact && echo || note_missing`
    // 这类**完全正常**的写法误报了。判据要窄：首个运算符是 || 且右段是命令。
    const dangerous = seg.op === '||' && !looksLikeTest(seg.right);
    t(rel + ':' + (i + 1) + (dangerous ? ' **危险：|| 在 && 之前，右边那条无条件执行**' : ' 安全'),
      !dangerous,
      dangerous ? '  ' + seg.right.slice(0, 90) + '\n         → 实际语义 (' + line.trim() + ')\n         bash 里 && 与 || 同优先级左结合；改成 if' : '');
  });
}
console.log('');
console.log('  混合 || 与 && 的行共 ' + checked + ' 处');

// ── 反例：证明本检查不是装饰 ──
console.log('');
console.log('== 反例验证（否则上面可能恒真）==');
const BAD = 'command -v cmake || sudo apt-get update && sudo apt-get install cmake';
t('反例行（真实踩过的那行）被判危险', !looksLikeTest(operatorSegments(BAD).right));
const GOOD = '[ -n "$NDK" ] && [ -d "$NDK" ] || die "无 NDK"';
t('安全惯用法 A && B || C 不误报',
  operatorSegments(GOOD).op === '&&', '  首个运算符不是 ||，不该判红');
// A && B || C 里 || 右边是命令（note_missing）也安全 —— 这是仓里真实存在的一类
const GOOD2 = 'has_exact "$n" && echo "[ok]" || note_missing "$n"';
t('A && B || C 且 || 右段是命令（仓里 verify-apk-native.sh:173 就是）不误报',
  operatorSegments(GOOD2).op === '&&');
t('同名惯用法 A || B && C 会被判危险（方向相反，语义不同）',
  operatorSegments('A || B && C').op === '||');
t('反例行确实同时含 || 与 &&（否则根本不进检查）', /\|\|/.test(BAD) && /&&/.test(BAD));
t('取到的正是第一个运算符的右段', operatorSegments(BAD).right.startsWith('sudo apt-get update'));

console.log('== 通过 ' + PASS + '，失败 ' + FAIL + ' ==');
process.exit(FAIL === 0 ? 0 : 1);