#!/usr/bin/env node
'use strict';

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

  for (let i = 1; i < rawLines.length; i += 1) {
    const cur = rawLines[i];
    if (cur.trim() === '') continue;
    const looksLikeFragment = /^\s+[A-Za-z_][\w.]*(\s*=[^,]*)?\s*,\s*$/.test(cur);
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
