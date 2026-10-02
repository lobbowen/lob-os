const fs = require('fs');
const path = require('path');

const ROOT = require('path').resolve(__dirname, '..');
const SRC = path.join(ROOT, 'container/app/src/main/java/lobos');

function files(dir, out) {
  out = out || [];
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) files(p, out);
    else if (p.endsWith('.kt')) out.push(p);
  }
  return out;
}

function check(text) {
  let i = 0;
  let depth = 0;
  let line = 1;
  const stack = [];
  let state = 'normal';
  while (i < text.length) {
    const c = text[i];
    const n = text[i + 1];
    if (c === '\n') line += 1;
    if (state === 'line') {
      if (c === '\n') state = 'normal';
      i += 1;
      continue;
    }
    if (state === 'block') {
      if (c === '*' && n === '/') { state = 'normal'; i += 2; continue; }
      i += 1;
      continue;
    }
    if (state === 'string') {
      if (c === '\\') { i += 2; continue; }
      if (c === '"') { state = 'normal'; i += 1; continue; }
      if (c === '$' && n === '{') { i += 2; continue; }
      i += 1;
      continue;
    }
    if (state === 'raw') {
      if (c === '"' && text.slice(i, i + 3) === '"""') { state = 'normal'; i += 3; continue; }
      i += 1;
      continue;
    }
    if (state === 'char') {
      if (c === '\\') { i += 2; continue; }
      if (c === "'") { state = 'normal'; i += 1; continue; }
      i += 1;
      continue;
    }
    if (c === '/' && n === '/') { state = 'line'; i += 2; continue; }
    if (c === '/' && n === '*') { state = 'block'; i += 2; continue; }
    if (text.slice(i, i + 3) === '"""') { state = 'raw'; i += 3; continue; }
    if (c === '"') { state = 'string'; i += 1; continue; }
    if (c === "'") { state = 'char'; i += 1; continue; }
    if (c === '{' || c === '(' || c === '[') { depth += 1; stack.push([c, line]); i += 1; continue; }
    if (c === '}' || c === ')' || c === ']') {
      depth -= 1;
      stack.pop();
      if (depth < 0) return { ok: false, why: '多余闭合 ' + c + ' 在第 ' + line + ' 行' };
      i += 1;
      continue;
    }
    i += 1;
  }
  if (state === 'string' || state === 'raw' || state === 'char') return { ok: false, why: '未闭合的引号（state=' + state + '）' };
  if (state === 'block') return { ok: false, why: '未闭合的块注释' };
  if (depth !== 0) return { ok: false, why: '未闭合 ' + (stack[stack.length - 1] || ['?', '?'])[0] + '（起始第 ' + (stack[stack.length - 1] || ['?', '?'])[1] + ' 行），depth=' + depth };
  return { ok: true };
}

const all = files(SRC, []);
let bad = 0;
for (const f of all) {
  const r = check(fs.readFileSync(f, 'utf8'));
  if (!r.ok) { console.log('  X ' + path.relative(ROOT, f) + ' :: ' + r.why); bad += 1; }
}
console.log('  检查 ' + all.length + ' 个 .kt，结构异常 ' + bad + ' 个');
process.exit(bad === 0 ? 0 : 1);