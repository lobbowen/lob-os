#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..');

let PASS = 0, FAIL = 0;
const t = (name, cond, detail) => {
  if (cond) { console.log('  [ok]   ' + name); PASS++; }
  else { console.log('  [FAIL] ' + name + (detail ? '\n         ' + detail : '')); FAIL++; }
};

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

function looksLikeTest(s) {
  const t = s.trim();
  if (!t) return false;
  if (/^\[\s.*\]$/.test(t)) return true;
  if (/^test\b/.test(t)) return true;
  if (/^command -v\b/.test(t)) return true;
  if (/^\[\[.*\]\]$/.test(t)) return true;
  if (/^(hash|type|-)\b/.test(t)) return true;
  return false;
}

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
    const st = line.trimStart();
    if (st.startsWith('#')) return;
    if (st.startsWith('-') || st.startsWith('name:')) return;
    if (!/\|\|/.test(line)) return;
    if (!/&&/.test(line)) return;
    if (/\|\|=/.test(line)) return;
    const seg = operatorSegments(line);
    if (seg === null || !seg.right) return;
    checked++;
    const dangerous = seg.op === '||' && !looksLikeTest(seg.right);
    t(rel + ':' + (i + 1) + (dangerous ? ' **危险：|| 在 && 之前，右边那条无条件执行**' : ' 安全'),
      !dangerous,
      dangerous ? '  ' + seg.right.slice(0, 90) + '\n         → 实际语义 (' + line.trim() + ')\n         bash 里 && 与 || 同优先级左结合；改成 if' : '');
  });
}
console.log('');
console.log('  混合 || 与 && 的行共 ' + checked + ' 处');

console.log('');
console.log('== 反例验证（否则上面可能恒真）==');
const BAD = 'command -v cmake || sudo apt-get update && sudo apt-get install cmake';
t('反例行（真实踩过的那行）被判危险', !looksLikeTest(operatorSegments(BAD).right));
const GOOD = '[ -n "$NDK" ] && [ -d "$NDK" ] || die "无 NDK"';
t('安全惯用法 A && B || C 不误报',
  operatorSegments(GOOD).op === '&&', '  首个运算符不是 ||，不该判红');
const GOOD2 = 'has_exact "$n" && echo "[ok]" || note_missing "$n"';
t('A && B || C 且 || 右段是命令（仓里 verify-apk-native.sh:173 就是）不误报',
  operatorSegments(GOOD2).op === '&&');
t('同名惯用法 A || B && C 会被判危险（方向相反，语义不同）',
  operatorSegments('A || B && C').op === '||');
t('反例行确实同时含 || 与 &&（否则根本不进检查）', /\|\|/.test(BAD) && /&&/.test(BAD));
t('取到的正是第一个运算符的右段', operatorSegments(BAD).right.startsWith('sudo apt-get update'));

console.log('== 通过 ' + PASS + '，失败 ' + FAIL + ' ==');
process.exit(FAIL === 0 ? 0 : 1);