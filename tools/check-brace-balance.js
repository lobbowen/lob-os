#!/usr/bin/env node
'use strict';

// Kotlin 文件的花括号必须配平。
//
// 判据来源（本仓真实踩过）：删掉一段代码时连函数体外层的 '}' 一起删了，
// CI 报 "Syntax error: Missing '}'"。这类错误其他门禁查不出来：
// check-cross-refs 只看符号存不存在，check-kotlin-misuse 只看写法，
// 都不会发现文件本身结构断了。
//
// 做法：逐字符扫描，跳过字符串字面量、字符字面量、行注释、块注释与
// 字符串模板里的 ${}，避免把注释或文案里的括号算进深度。

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

// 返回 null 表示配平；否则返回 {line, depth} 描述第一次失衡的位置
function imbalance(text) {
  let depth = 0;
  let line = 1;
  let i = 0;
  // 模板表达式 ${...} 用的括号在字符串内部，但它们是真实的语法配对，
  // 所以照样计数（对深度无影响，只影响"首次为负"的定位精度）。
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
      const triple = text.slice(i, i + 3) === '"""';
      if (triple) {
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
        if (text[i] === '\\') { i += 1; } else if (text[i] === '\n') { line += 1; }
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
    if (c === '{') { depth += 1; i += 1; continue; }
    if (c === '}') {
      depth -= 1;
      if (depth < 0) return { line, depth };
      i += 1;
      continue;
    }
    i += 1;
  }
  return depth === 0 ? null : { line, depth };
}

const problems = [];
const files = walk(SRC);
for (const f of files) {
  const rel = f.replace(SRC + '/', '');
  const bad = imbalance(fs.readFileSync(f, 'utf8'));
  if (!bad) continue;
  problems.push(
    bad.depth < 0
      ? rel + '  第 ' + bad.line + ' 行出现多余的 }（结构已断）'
      : rel + '  文件结束时仍缺 ' + bad.depth + ' 个 }（最后检查到第 ' + bad.line + ' 行）',
  );
}

if (problems.length) {
  console.log('FAIL 括号配平门禁：' + problems.length + ' 个文件');
  for (const p of problems) console.log('  · ' + p);
  console.log('');
  console.log('这类错误只有编译才炸：删代码时把外层花括号一起删掉，');
  console.log('符号解析与写法检查都发现不了。');
  process.exit(1);
}

console.log('PASS 括号配平门禁：' + files.length + ' 个 Kotlin 文件结构完整' +
  '（已排除注释与字符串字面量里的括号）');
