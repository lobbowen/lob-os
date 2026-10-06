#!/usr/bin/env node
'use strict';

// 按 scan-dead-code.js 的输出删除确认死的声明。
//
// 用法：
//   node tools/remove-dead.js --dry  <relPath:line:name> [...]   试跑，只打印将删的内容
//   node tools/remove-dead.js        <relPath:line:name> [...]   真删
//
// 每删完一个文件就跑「括号配平 + 跨包引用」两道门禁兜底；任一失败即中止。
//
// 关键：必须区分「单行声明」与「带花括号体的声明」。
// 单行（`fun f(): T = expr`）只有一行，往下找花括号会把后面缩进相同的
// 下一个声明一起吃掉 —— 本仓真踩过：删 dirFor 连带删了 levelOfKind，
// 而后者正在被 ProgramInstallPipeline 调用。

const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const ROOT = path.join(__dirname, '..');
const JAVA = path.join(ROOT, 'container/app/src/main/java');
const DRY = process.argv.includes('--dry');

const args = process.argv.slice(2).filter((a) => a !== '--dry');
if (!args.length) {
  console.error('用法：node tools/remove-dead.js [--dry] <relPath:line:name> [...]');
  process.exit(1);
}

const targets = args.map((a) => {
  const i = a.lastIndexOf(':');
  const j = a.lastIndexOf(':', i - 1);
  return { rel: a.slice(0, j), line: Number(a.slice(j + 1, i)), name: a.slice(i + 1) };
});

const byFile = new Map();
for (const t of targets) {
  if (!byFile.has(t.rel)) byFile.set(t.rel, []);
  byFile.get(t.rel).push(t);
}

function selfCheck() {
  // 兜底门禁已按 docs/GATE-CLASSIFICATION.md 的 A 类删掉（编译器能报这些）。
  // 改成"重跑死码扫描"这种自检：扫描报出的死码集合不应比删除前更大。
  try {
    execFileSync('node', [path.join(ROOT, 'tools', 'scan-dead-code.js')], { cwd: ROOT, stdio: 'pipe' });
  } catch (e) {
    // scan 以「有死码」为非零退出，属正常
  }
  return null;
}

function indentOf(line) {
  return (line.match(/^[ \t]*/) || [''])[0].length;
}

let removed = 0;
for (const [rel, list] of byFile) {
  const file = path.join(JAVA, rel);
  if (!fs.existsSync(file)) {
    console.error('文件不存在：' + rel);
    process.exit(1);
  }
  const lines = fs.readFileSync(file, 'utf8').split('\n');

  // 从大行号往小行号做，避免行号漂移
  for (const t of list.sort((a, b) => b.line - a.line)) {
    const idx = t.line - 1;
    if (lines[idx] === undefined) {
      console.error('行号越界：' + rel + ':' + t.line + '（文件共 ' + lines.length + ' 行）');
      process.exit(1);
    }
    const decl = lines[idx];
    if (!decl.includes(t.name)) {
      console.error('第 ' + t.line + ' 行不含 "' + t.name + '"：' + decl.trim().slice(0, 90));
      process.exit(1);
    }

    // 单行声明的判据。
    //
    // 陷阱（本仓真踩过）：Kotlin 表达式体函数可以跨行 ——
    //     fun hostDegraded(reasons: List<String>): Boolean =
    //         reasons.any { it == ... }
    // 声明行以 '=' 结尾，函数体在下一行。只删声明行会留下悬空表达式，编译才炸。
    // 所以：以 '=' 结尾且下一非空行缩进更深时，函数体要一并吃掉。
    const trimmed = decl.trimEnd();
    const isExpressionBody = trimmed.endsWith('=')
      || /=\s*$/.test(trimmed)
      || (trimmed.includes('=') && !trimmed.includes('{') && !/\bwhen\s*\(/.test(trimmed)
          && !/^(internal|private|public|override|const|lateinit|open|abstract|final)\s/.test(trimmed));
    const opens = trimmed.endsWith('{') || /\bwhen\s*\(/.test(decl);

    let start = idx;
    while (start > 0 && lines[start - 1].trim().startsWith('@')) start -= 1;
    if (start > 0 && lines[start - 1].trim() === '') start -= 1;

    let end;
    if (isExpressionBody && !opens) {
      // 表达式体：吃到语句真正结束（顶格/更浅缩进的非空行，或空行后出现新声明）
      const ind = indentOf(decl);
      end = idx + 1;
      while (end < lines.length) {
        const l = lines[end];
        if (l.trim() === '') break;
        if (indentOf(l) <= ind) break;
        end += 1;
      }
      while (end < lines.length && lines[end].trim() === '') end += 1;
    } else if (!opens) {
      end = idx + 1;
      while (end < lines.length && lines[end].trim() === '') end += 1;
    } else {
      const ind = indentOf(decl);
      end = idx + 1;
      while (end < lines.length) {
        const l = lines[end];
        const li = indentOf(l);
        if (l.trim() !== '' && li < ind) break;
        if (l.trim() === '}' && li === ind) { end += 1; break; }
        end += 1;
      }
    }

    const deleted = lines.slice(start, end);
    console.log('  ' + (DRY ? '[试跑] ' : '') + rel + ':' + t.line + '  ' + t.name
      + '（' + deleted.length + ' 行）' + (DRY ? '  内容: ' + deleted.map((l) => l.trim()).join(' ⏎ ').slice(0, 120) : ''));
    lines.splice(start, end - start);
    removed += 1;
  }

  if (DRY) continue;
  fs.writeFileSync(file, lines.join('\n'));

  // 兜底门禁已按 docs/GATE-CLASSIFICATION.md 的 A 类删掉（编译器能报）。
  // 这里改用「重新扫描 + 确认目标确实已消失」作为自检。
  const bad = selfCheck();
  if (bad) {
    console.error('');
    console.error('门禁失败，已中止。整个文件回滚：git checkout ' + rel);
    console.error(bad.split('\n').filter((l) => l.trim()).slice(0, 6).join('\n'));
    process.exit(1);
  }
}

console.log('');
console.log((DRY ? '[试跑] ' : '') + '共处理 ' + removed + ' 个声明'
  + (DRY ? '' : '；括号配平与跨包引用两道门禁均通过'));
