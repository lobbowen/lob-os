#!/usr/bin/env node
'use strict';

// Kotlin 结构自检：括号配平 + 悬空参数列表 + 孤立 ) 后的残留片段。
//
// 这不是"为了门禁而门禁"，判据来自四次真实事故：
//
//  1. 删 bindQuickApp 那 63 行时，把函数体外层的 '}' 一起删了
//     → CI: Syntax error: Missing '}'
//  2. 删 hostDegraded（跨行表达式体）时，把函数体删了留悬空表达式
//  3. 删 recordAttempt（参数列表跨行）时，只吃了声明两行，
//     参数列表 ctx/outcome/detail 残留 → CI: Expecting member declaration
//  4. 删 ENTRY_ORDER / GATING（val X = listOf( 跨行）时，把头和 ) 删了一半，
//     留下 `        CapabilityCatalog.ADB_CHANNEL,`
//     → CI: Expecting member declaration
//
// 后三次括号都是配平的，符号解析也查不出来，只有编译才炸。
// 本机没编译器时全靠人读 diff 兜，风险太高。
//
// 只做这三条：它们是「结构完整性」，不是代码风格，不该判谁对谁错。

const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const SRC = path.join(ROOT, 'container/app/src/main/java');

function walk(dir, out) {
  out = out || [];
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (p.endsWith('.kt')) out.push(p);
  }
  return out;
}

// 跳过注释与字符串字面量，否则文案里的括号会算进深度
function stripCode(text) {
  let out = '';
  let i = 0;
  let line = 1;
  const marks = [];
  while (i < text.length) {
    const c = text[i];
    if (c === '\n') { line += 1; i += 1; continue; }
    if (c === '/' && text[i + 1] === '/') {
      while (i < text.length && text[i] !== '\n') i += 1;
      continue;
    }
    if (c === '/' && text[i + 1] === '*') {
      i += 2;
      while (i < text.length && !(text[i] === '*' && text[i + 1] === '/')) {
        if (text[i] === '\n') line += 1;
        i += 1;
      }
      i += 2;
      continue;
    }
    if (c === '"') {
      if (text.slice(i, i + 3) === '"""') {
        i += 3;
        while (i < text.length && text.slice(i, i + 3) !== '"""') {
          if (text[i] === '\n') line += 1;
          i += 1;
        }
        i += 3;
        continue;
      }
      i += 1;
      while (i < text.length && text[i] !== '"') {
        if (text[i] === '\\') i += 1;
        else if (text[i] === '\n') line += 1;
        i += 1;
      }
      i += 1;
      continue;
    }
    if (c === "'") {
      i += 1;
      while (i < text.length && text[i] !== "'") {
        if (text[i] === '\\') i += 1;
        i += 1;
      }
      i += 1;
      continue;
    }
    out += c;
    marks.push(line);
    i += 1;
  }
  return { code: out, lineOf: (idx) => marks[idx] || 1 };
}

const problems = [];
const files = walk(SRC);

for (const f of files) {
  const rel = f.replace(SRC + '/', '');
  const raw = fs.readFileSync(f, 'utf8');
  const { code, lineOf } = stripCode(raw);

  // 判据 1：花括号配平
  let depth = 0;
  let firstNegative = 0;
  for (let i = 0; i < code.length; i += 1) {
    if (code[i] === '{') depth += 1;
    else if (code[i] === '}') {
      depth -= 1;
      if (depth < 0 && !firstNegative) firstNegative = lineOf(i);
    }
  }
  if (firstNegative) {
    problems.push(rel + '  第 ' + firstNegative + ' 行出现多余的 }');
  } else if (depth > 0) {
    problems.push(rel + '  文件结束时仍缺 ' + depth + ' 个 }（最后检查到第 ' + lineOf(code.length - 1) + ' 行）');
  }

  // 判据 2：悬空参数列表
  //   形如 `    ctx: Context,` 的参数行，其上一非空行必须是 fun 声明、
  //   注解、续行（以 ( { , 结尾）或括号收尾 —— 否则说明 fun 头被删了。
  const rawLines = raw.split('\n');
  for (let i = 0; i < rawLines.length; i += 1) {
    const m = /^(\s+)[a-zA-Z_][A-Za-z0-9_]*\s*:\s*[A-Za-z][\w.<>,\s]*,\s*$/.exec(rawLines[i]);
    if (!m) continue;
    let j = i - 1;
    while (j >= 0 && rawLines[j].trim() === '') j -= 1;
    if (j < 0) continue;
    const prev = rawLines[j].trim();
    const ok = /^(fun|@|\)|\{|})$/.test(prev)
      || prev.endsWith('(') || prev.endsWith('{') || prev.endsWith(',')
      || /\bfun\b/.test(prev);
    if (ok) continue;
    problems.push(
      rel + '  第 ' + (i + 1) + ' 行是悬空参数「' + rawLines[i].trim() + '」'
      + '（上一行是「' + prev.slice(0, 40) + '」）—— fun 头被删了',
    );
  }

  // 判据 3：悬空右括号 / 残留表达式片段
  //   形如 `        CapabilityCatalog.ADB_CHANNEL,` 或 `        expr { … }`
  //   的行出现在「上一非空行是孤立的 ) 」之后 —— 说明 val X = listOf( 的头被删了，
  //   只剩 ) 和后续元素。括号是配平的，纯结构检查查不出，只有编译才炸。
  for (let i = 1; i < rawLines.length; i += 1) {
    const cur = rawLines[i];
    if (cur.trim() === '') continue;
    // 只看「以逗号结尾的裸标识符路径」或「裸调用」
    const looksLikeFragment = /^\s+[A-Za-z_][\w.]*\s*,\s*$/.test(cur)
      || /^\s+[A-Za-z_][\w.]*\s*\{\s*$/.test(cur);
    if (!looksLikeFragment) continue;
    let j = i - 1;
    while (j >= 0 && rawLines[j].trim() === '') j -= 1;
    if (j < 0) continue;
    const prev = rawLines[j].trim();
    if (prev === ')') {
      problems.push(
        rel + '  第 ' + (i + 1) + ' 行「' + cur.trim() + '」是孤立 ) 之后的残留片段'
        + '—— 声明头被删了（形如 val X = listOf( 少了一半）',
      );
    }
  }
}

if (problems.length) {
  console.log('FAIL 结构自检门禁：' + problems.length + ' 处');
  for (const p of problems) console.log('  · ' + p);
  console.log('');
  console.log('判据只管结构完整性（括号配平 + 悬空参数），不管代码风格。');
  console.log('依据：本仓删代码时真踩过三次，都是只有编译才炸的类型。');
  process.exit(1);
}

console.log('PASS 结构自检门禁：' + files.length + ' 个 Kotlin 文件'
  + ' 括号配平、无悬空参数列表、无孤立 ) 后的残留片段');
