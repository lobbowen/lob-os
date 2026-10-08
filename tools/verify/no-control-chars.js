'use strict';
const fs = require('node:fs');
const path = require('node:path');

const WHY = [
  '扫控制字符：ESC、BEL、空字符等。',
  '动机：连续三次因工具文件里的控制字符/续行截断导致 CI 取证整段失效，',
  '而 bash -n 查不出来 —— 混进控制字符后，报错行会变成 command not found，',
  '真正的错误行永远看不到。',
].join(String.fromCharCode(10));

const ROOT = path.resolve(__dirname, '..', '..');
const SKIP = new Set(['node_modules', '.git', 'build', 'dist', 'work', '.gradle']);
const EXT = /\.(sh|bash|js|cjs|mjs|json|ya?ml|md|kt|java|txt|properties|xml)$/;
const CTRL = new RegExp('[\\u0000-\\u0008\\u000b-\\u001f\\u007f]');

const hits = [];
let scanned = 0;

function walk(dir) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (SKIP.has(e.name)) continue;
    const p = path.join(dir, e.name);
    if (e.isDirectory()) { walk(p); continue; }
    if (!EXT.test(e.name)) continue;
    let text;
    try { text = fs.readFileSync(p, 'utf8'); } catch { continue; }
    scanned++;
    text.split('\n').forEach((line, i) => {
      if (!CTRL.test(line)) return;
      const cols = [];
      [...line].forEach((c, k) => { if (CTRL.test(c)) cols.push(k); });
      const codes = cols.map((k) => 'col' + k + '=U+' + line.charCodeAt(k).toString(16).padStart(4, '0'));
      hits.push('[控制字符] ' + path.relative(ROOT, p) + ':' + (i + 1) + '  ' + codes.join(' '));
    });
  }
}

walk(ROOT);

if (hits.length) {
  for (const h of hits) console.log(h);
  console.log('FAIL 控制字符扫描：' + hits.length + ' 处 —— bash -n 查不出，但会让 CI 取证整段失效');
  process.exit(1);
}
console.log('PASS 控制字符扫描：' + scanned + ' 个文件干净');
