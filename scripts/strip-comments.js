'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');

const SKIP_DIRS = new Set(['node_modules', '.git', '.gradle', 'build', 'dist', '.cache', 'docs']);
const SKIP_FILES = new Set(['gradlew', 'gradlew.bat', 'package-lock.json']);
const NAME_LANGS = { '.gitignore': 'gitignore', file_contexts: 'hash' };
const GENERATED_REPORTS = new Set(['brand-scan-report.txt', 'debt-gate-report.txt']);
const GENERATED = new Set(['.github/native-assets.txt', '.github/native-capabilities.txt']);

const LANGS = {
  '.js': 'js',
  '.cjs': 'js',
  '.mjs': 'js',
  '.ts': 'ts',
  '.tsx': 'tsx',
  '.kt': 'kt',
  '.kts': 'kt',
  '.c': 'c',
  '.h': 'c',
  '.css': 'css',
  '.html': 'html',
  '.htm': 'html',
  '.xml': 'xml',
  '.svg': 'xml',
  '.yml': 'yaml',
  '.yaml': 'yaml',
  '.sh': 'sh',
  '.bash': 'sh',
  '.py': 'py',
  '.properties': 'hash',
  '.configfrag': 'hash',
  '.rc': 'hash',
  '.te': 'hash',
  '.conf': 'hash',
  '.pro': 'hash',
  '.gitignore': 'hash',
  '.bp': 'bp',
  '.txt': 'hash',
  '.json': 'json'
};

const CFG = {
  js: { line: '//', block: true, template: true, regex: true, dq: true, sq: true, jsx: false, interp: false },
  ts: { line: '//', block: true, template: true, regex: true, dq: true, sq: true, jsx: false, interp: false },
  tsx: { line: '//', block: true, template: true, regex: true, dq: true, sq: true, jsx: true, interp: false },
  kt: { line: '//', block: true, nested: true, template: false, regex: false, dq: true, sq: true, jsx: false, interp: true, rawTriple: true, backtickIdent: true },
  c: { line: '//', block: true, template: false, regex: false, dq: true, sq: true, jsx: false, interp: false, lineCont: true },
  css: { line: null, block: true, template: false, regex: false, dq: true, sq: true, jsx: false, interp: false },
  jsonc: { line: '//', block: true, template: false, regex: false, dq: true, sq: false, jsx: false, interp: false },
  bp: { line: '//', block: true, template: false, regex: false, dq: true, sq: true, jsx: false, interp: false }
};

const ALLOW = [
  /^#!/,
  /^<reference\b/,
  /^<amd-(module|dependency)\b/,
  /eslint-(disable|enable|ignore)/,
  /^@ts-(ignore|expect-error|nocheck|check)\b/,
  /^@vitest-environment\b/,
  /^shellcheck\b/,
  /^prettier-ignore\b/,
  /^#?\s*(region|endregion)\b/,
  /^noinspection\b/,
  /^(istanbul|c8|v8)\s+ignore\b/,
  /^@(jsx|jsxRuntime|jsxFrag|jsxImportSource|flow|noflow|generated|license|preserve|cc_on)\b/,
  /sourceMappingURL|sourceURL=/,
  /^#\s*yaml-language-server:/,
  /coding[:=]\s*utf-8/
];

function isAllowed(body) {
  const t = body.replace(/^(\/\/+|#+|\/\*+|\*+|<!--|--)/, '').replace(/\*\/$/, '').trim();
  return ALLOW.some((re) => re.test(t));
}

function splitLines(text) {
  const out = [];
  let start = 0;
  for (let i = 0; i < text.length; i++) {
    if (text[i] === '\n') {
      out.push({ start, end: i, body: text.slice(start, i) });
      start = i + 1;
    }
  }
  out.push({ start, end: text.length, body: text.slice(start) });
  return out;
}

const IDENT = /[A-Za-z0-9_$]/;
const RE_PREV = new Set(['', '(', ',', '=', ':', '[', '!', '&', '|', '?', '{', '}', ';', '+', '-', '*', '%', '<', '>', '~', '^', '\n']);
const RE_WORDS = new Set(['return', 'typeof', 'instanceof', 'in', 'of', 'new', 'delete', 'void', 'throw', 'case', 'do', 'else', 'yield', 'await', 'default', 'extends']);

function scanCLike(text, cfg) {
  const comments = [];
  const anomalies = [];
  const n = text.length;
  const ctx = [{ k: 'code' }];
  let i = 0;
  let prev = '';
  let word = '';

  const code = () => {
    const c = text[i];
    if (i === 0 && text.startsWith('#!')) {
      while (i < n && text[i] !== '\n') i++;
      return;
    }
    if (cfg.line && text.startsWith(cfg.line, i)) {
      const s = i;
      i += cfg.line.length;
      while (i < n) {
        if (text[i] === '\n') {
          if (cfg.lineCont && i > s && text[i - 1] === '\\') {
            i++;
            continue;
          }
          break;
        }
        i++;
      }
      comments.push({ start: s, end: i, kind: 'line' });
      return;
    }
    if (cfg.block && text.startsWith('/*', i)) {
      const s = i;
      i += 2;
      ctx.push({ k: 'block', depth: 1, start: s });
      return;
    }
    if (cfg.rawTriple && text.startsWith('"""', i)) {
      i += 3;
      prev = '"';
      ctx.push({ k: 'raw' });
      return;
    }
    if (cfg.dq && c === '"') {
      i++;
      prev = '"';
      ctx.push({ k: 'str', q: '"', interp: !!cfg.interp });
      return;
    }
    if (cfg.sq && c === "'") {
      i++;
      prev = "'";
      ctx.push({ k: 'str', q: "'", interp: false });
      return;
    }
    if (cfg.template && c === '`') {
      i++;
      prev = '`';
      ctx.push({ k: 'tmpl' });
      return;
    }
    if (cfg.backtickIdent && c === '`') {
      i++;
      prev = '`';
      ctx.push({ k: 'ident' });
      return;
    }
    if (cfg.jsx && c === '<' && jsxStart(text, i, prev, word)) {
      i++;
      prev = '>';
      ctx.push({ k: 'jsxTag', selfClose: false, closing: false });
      return;
    }
    if (c === '{') {
      const top = ctx[ctx.length - 1];
      if (top.k === 'interp') top.braces++;
      prev = '{';
      word = '';
      i++;
      return;
    }
    if (c === '}') {
      const top = ctx[ctx.length - 1];
      if (top.k === 'interp') {
        if (top.braces > 0) top.braces--;
        else {
          ctx.pop();
          prev = '}';
          word = '';
          i++;
          return;
        }
      }
      prev = '}';
      word = '';
      i++;
      return;
    }
    if (cfg.regex && c === '/' && !text.startsWith('//', i) && !text.startsWith('/*', i) && regexOk(prev, word)) {
      const s = i;
      i++;
      let inClass = false;
      let closed = false;
      while (i < n) {
        const d = text[i];
        if (d === '\\') {
          i += 2;
          continue;
        }
        if (d === '\n') break;
        if (d === '[') inClass = true;
        else if (d === ']') inClass = false;
        else if (d === '/' && !inClass) {
          closed = true;
          i++;
          break;
        }
        i++;
      }
      if (closed) {
        while (i < n && /[a-z]/i.test(text[i])) i++;
      } else {
        anomalies.push({ at: s, msg: 'unterminated-regex' });
        i = s + 1;
      }
      prev = ')';
      word = '';
      return;
    }
    if (IDENT.test(c)) {
      word += c;
    } else if (c !== ' ' && c !== '\t' && c !== '\r' && c !== '\n') {
      if (word) word = '';
    }
    if (c !== ' ' && c !== '\t' && c !== '\r' && c !== '\n') prev = c;
    if (c === '\n') {
      prev = '';
    }
    i++;
  };

  const inStr = (top) => {
    const c = text[i];
    if (c === '\\') {
      i += 2;
      return;
    }
    if (c === '\n' && top.q !== '"') {
      anomalies.push({ at: top.start || i, msg: 'unterminated-string' });
      ctx.pop();
      prev = top.q;
      return;
    }
    if (top.interp && c === '$' && text[i + 1] === '{') {
      i += 2;
      prev = '{';
      ctx.push({ k: 'interp', braces: 0 });
      return;
    }
    if (c === top.q) {
      ctx.pop();
      prev = top.q;
      i++;
      return;
    }
    i++;
  };

  const inTmpl = () => {
    const c = text[i];
    if (c === '\\') {
      i += 2;
      return;
    }
    if (c === '$' && text[i + 1] === '{') {
      i += 2;
      prev = '{';
      ctx.push({ k: 'interp', braces: 0 });
      return;
    }
    if (c === '`') {
      ctx.pop();
      prev = '`';
      i++;
      return;
    }
    i++;
  };

  const inRaw = () => {
    if (text.startsWith('"""', i)) {
      i += 3;
      ctx.pop();
      prev = '"';
      return;
    }
    if (text[i] === '$' && text[i + 1] === '{') {
      i += 2;
      prev = '{';
      ctx.push({ k: 'interp', braces: 0 });
      return;
    }
    i++;
  };

  const inIdent = () => {
    if (text[i] === '`') {
      ctx.pop();
      prev = '`';
      i++;
      return;
    }
    if (text[i] === '\n') {
      anomalies.push({ at: i, msg: 'unterminated-backtick' });
      ctx.pop();
      prev = '`';
      return;
    }
    i++;
  };

  const inBlock = (top) => {
    if (cfg.nested && text.startsWith('/*', i)) {
      top.depth++;
      i += 2;
      return;
    }
    if (text.startsWith('*/', i)) {
      top.depth--;
      i += 2;
      if (top.depth === 0) {
        comments.push({ start: top.start, end: i, kind: 'block' });
        ctx.pop();
      }
      return;
    }
    i++;
  };

  const inJsxTag = (top) => {
    const c = text[i];
    if (c === '{') {
      i++;
      prev = '{';
      word = '';
      ctx.push({ k: 'interp', braces: 0 });
      return;
    }
    if (c === '"' || c === "'") {
      i++;
      prev = c;
      word = '';
      ctx.push({ k: 'str', q: c, interp: false });
      return;
    }
    if (c === '>') {
      i++;
      prev = '>';
      word = '';
      if (top.closing) {
        ctx.pop();
        const below = ctx[ctx.length - 1];
        if (below && below.k === 'jsxChildren') ctx.pop();
        return;
      }
      if (top.selfClose) {
        ctx.pop();
        return;
      }
      top.k = 'jsxChildren';
      return;
    }
    if (c === '/' && text[i + 1] === '>') top.selfClose = true;
    i++;
  };

  const inJsxChildren = () => {
    const c = text[i];
    if (c === '{') {
      i++;
      prev = '{';
      word = '';
      ctx.push({ k: 'interp', braces: 0 });
      return;
    }
    if (c === '<') {
      i++;
      prev = '<';
      word = '';
      const closing = text[i] === '/';
      if (closing) i++;
      ctx.push({ k: 'jsxTag', selfClose: false, closing });
      return;
    }
    i++;
  };

  while (i < n) {
    const top = ctx[ctx.length - 1];
    if (top.k === 'code' || top.k === 'interp') code();
    else if (top.k === 'str') inStr(top);
    else if (top.k === 'tmpl') inTmpl();
    else if (top.k === 'raw') inRaw();
    else if (top.k === 'ident') inIdent();
    else if (top.k === 'block') inBlock(top);
    else if (top.k === 'jsxTag') inJsxTag(top);
    else if (top.k === 'jsxChildren') inJsxChildren();
    else break;
  }
  if (ctx.some((f) => f.k === 'block')) {
    const b = ctx.find((f) => f.k === 'block');
    comments.push({ start: b.start, end: n, kind: 'block' });
    anomalies.push({ at: b.start, msg: 'unterminated-block-comment' });
  }
  return { comments, anomalies };
}

function jsxStart(text, i, prev, word) {
  if (word && RE_WORDS.has(word)) return true;
  if (!RE_PREV.has(prev)) return false;
  const d = text[i + 1];
  if (!d) return false;
  if (d === '>') return true;
  if (d === '/' ) return false;
  if (!/[A-Za-z_$]/.test(d)) return false;
  return true;
}

function regexOk(prev, word) {
  if (word && RE_WORDS.has(word)) return true;
  return RE_PREV.has(prev);
}

function scanHash(text, cfg) {
  const comments = [];
  const lines = splitLines(text);
  for (let li = 0; li < lines.length; li++) {
    const L = lines[li];
    let i = L.start;
    if (li === 0 && text.startsWith('#!')) continue;
    let q = null;
    while (i < L.end) {
      const c = text[i];
      if (q) {
        if (c === '\\' && q === '"') {
          i += 2;
          continue;
        }
        if (c === q) q = null;
        i++;
        continue;
      }
      if (c === '"' || c === "'") {
        q = c;
        i++;
        continue;
      }
      if (c === '#' || (cfg.bang && c === '!')) {
        if (i === L.start || /[\s=:(,;]/.test(text[i - 1])) {
          comments.push({ start: i, end: L.end, kind: 'line' });
        }
        break;
      }
      i++;
    }
  }
  return { comments, anomalies: [] };
}

function skipSq(text, i) {
  while (i < text.length && text[i] !== "'") i++;
  return i + 1;
}

function skipDq(text, i) {
  const n = text.length;
  while (i < n) {
    const c = text[i];
    if (c === '\\') {
      i += 2;
      continue;
    }
    if (c === '"') return i + 1;
    if (c === '$' && text[i + 1] === '(') {
      i = skipParen(text, i + 2);
      continue;
    }
    if (c === '$' && text[i + 1] === '{') {
      i = skipBraced(text, i + 2);
      continue;
    }
    if (c === '`') {
      i = skipBacktick(text, i + 1);
      continue;
    }
    i++;
  }
  return i;
}

function skipBacktick(text, i) {
  while (i < text.length) {
    if (text[i] === '\\') {
      i += 2;
      continue;
    }
    if (text[i] === '`') return i + 1;
    i++;
  }
  return i;
}

function skipParen(text, i) {
  let depth = 1;
  while (i < text.length && depth > 0) {
    const c = text[i];
    if (c === '\\') {
      i += 2;
      continue;
    }
    if (c === "'") {
      i = skipSq(text, i + 1);
      continue;
    }
    if (c === '"') {
      i = skipDq(text, i + 1);
      continue;
    }
    if (c === '`') {
      i = skipBacktick(text, i + 1);
      continue;
    }
    if (c === '(') depth++;
    else if (c === ')') depth--;
    i++;
  }
  return i;
}

function skipBraced(text, i) {
  let depth = 1;
  while (i < text.length && depth > 0) {
    const c = text[i];
    if (c === '\\') {
      i += 2;
      continue;
    }
    if (c === "'") {
      i = skipSq(text, i + 1);
      continue;
    }
    if (c === '"') {
      i = skipDq(text, i + 1);
      continue;
    }
    if (c === '{') depth++;
    else if (c === '}') depth--;
    i++;
  }
  return i;
}

function scanSh(text) {
  const comments = [];
  const anomalies = [];
  const n = text.length;
  let i = 0;
  let heredocs = [];
  while (i < n) {
    const c = text[i];
    if (c === '\\') {
      i += 2;
      continue;
    }
    if (c === "'") {
      i = skipSq(text, i + 1);
      continue;
    }
    if (c === '"') {
      i = skipDq(text, i + 1);
      continue;
    }
    if (c === '$' && text[i + 1] === "'") {
      i++;
      i = skipSq(text, i + 1);
      continue;
    }
    if (c === '$' && text[i + 1] === '"') {
      i++;
      i = skipDq(text, i + 1);
      continue;
    }
    if (c === '`') {
      i = skipBacktick(text, i + 1);
      continue;
    }
    if (c === '$' && text[i + 1] === '{') {
      i = skipBraced(text, i + 2);
      continue;
    }
    if (c === '$' && text[i + 1] === '(') {
      i = skipParen(text, i + 2);
      continue;
    }
    if (c === '#' && (i === 0 || /[\s;&|()<>]/.test(text[i - 1]))) {
      if (i === 0 && text[1] === '!') {
        i += 2;
        while (i < n && text[i] !== '\n') i++;
        continue;
      }
      const s = i;
      while (i < n && text[i] !== '\n') i++;
      comments.push({ start: s, end: i, kind: 'line' });
      continue;
    }
    if (c === '<' && text[i + 1] === '<' && text[i + 2] !== '<' && text[i + 2] !== '=') {
      let j = i + 2;
      let stripTabs = false;
      if (text[j] === '-') {
        stripTabs = true;
        j++;
      }
      while (text[j] === ' ' || text[j] === '\t') j++;
      let delim = '';
      const q = text[j] === "'" || text[j] === '"' ? text[j] : null;
      if (q) {
        j++;
        while (j < n && text[j] !== q) {
          delim += text[j];
          j++;
        }
        j++;
      } else {
        while (j < n && /[A-Za-z0-9_\\-]/.test(text[j])) {
          if (text[j] !== '\\') delim += text[j];
          j++;
        }
      }
      if (delim) heredocs.push({ delim, stripTabs });
      i = j;
      continue;
    }
    if (c === '\n') {
      i++;
      while (heredocs.length) {
        const h = heredocs.shift();
        while (i < n) {
          const ls = i;
          while (i < n && text[i] !== '\n') i++;
          const line = text.slice(ls, i);
          if (i < n) i++;
          const cmp = h.stripTabs ? line.replace(/^\t+/, '') : line;
          if (cmp === h.delim) break;
        }
      }
      continue;
    }
    i++;
  }
  return { comments, anomalies };
}

function scanYaml(text) {
  const comments = [];
  const anomalies = [];
  const lines = splitLines(text);
  const blocks = [];
  for (let li = 0; li < lines.length; li++) {
    const m = /^(\s*)([A-Za-z0-9_.-]+):\s*[|>][-+]?[0-9]*\s*$/.exec(lines[li].body);
    if (!m) continue;
    const headerIndent = m[1].length;
    const key = m[2];
    let cs = li + 1;
    let contentIndent = null;
    while (cs < lines.length) {
      const b = lines[cs].body;
      if (b.trim() === '') {
        cs++;
        continue;
      }
      contentIndent = b.match(/^ */)[0].length;
      break;
    }
    if (contentIndent === null) {
      blocks.push({ start: lines[li].end + 1, end: text.length, key, indent: headerIndent + 1 });
      break;
    }
    if (contentIndent <= headerIndent) continue;
    let ce = cs;
    while (ce < lines.length) {
      const b = lines[ce].body;
      if (b.trim() !== '' && b.match(/^ */)[0].length < contentIndent) break;
      ce++;
    }
    const start = lines[cs - 1] ? lines[cs - 1].end + 1 : lines[li].end + 1;
    const lastLine = lines[ce - 1];
    blocks.push({ start: lines[cs].start, end: lastLine ? lastLine.end : text.length, key, indent: contentIndent });
    li = ce - 1;
  }
  const inBlock = (idx) => blocks.find((b) => idx >= b.start && idx < b.end);
  for (const b of blocks) {
    if (!SHELL_KEYS.has(b.key)) continue;
    const sub = text.slice(b.start, b.end);
    const r = scanSh(sub);
    for (const cm of r.comments) comments.push({ start: b.start + cm.start, end: b.start + cm.end, kind: cm.kind });
  }
  for (const L of lines) {
    if (inBlock(L.start)) continue;
    let i = L.start;
    let q = null;
    while (i < L.end) {
      const c = text[i];
      if (q) {
        if (c === '\\' && q === '"') {
          i += 2;
          continue;
        }
        if (c === q) q = null;
        i++;
        continue;
      }
      if (c === '"' || c === "'") {
        q = c;
        i++;
        continue;
      }
      if (c === '#' && (i === L.start || text[i - 1] === ' ' || text[i - 1] === '\t')) {
        comments.push({ start: i, end: L.end, kind: 'line' });
        break;
      }
      i++;
    }
  }
  return { comments, anomalies };
}

const SHELL_KEYS = new Set(['run', 'script', 'shell', 'before_script', 'after_script']);

function scanXml(text, cfg) {
  const comments = [];
  const anomalies = [];
  const n = text.length;
  let i = 0;
  while (i < n) {
    if (text.startsWith('<!--', i)) {
      const s = i;
      const e = text.indexOf('-->', i + 4);
      i = e < 0 ? n : e + 3;
      comments.push({ start: s, end: i, kind: 'block' });
      if (e < 0) anomalies.push({ at: s, msg: 'unterminated-xml-comment' });
      continue;
    }
    if (text.startsWith('<![CDATA[', i)) {
      const e = text.indexOf(']]>', i);
      i = e < 0 ? n : e + 3;
      continue;
    }
    if (text.startsWith('<?', i)) {
      const e = text.indexOf('?>', i);
      i = e < 0 ? n : e + 2;
      continue;
    }
    if (text[i] === '<') {
      let j = i + 1;
      let q = null;
      while (j < n) {
        const d = text[j];
        if (q) {
          if (d === q) q = null;
          j++;
          continue;
        }
        if (d === '"' || d === "'") {
          q = d;
          j++;
          continue;
        }
        if (d === '>') {
          j++;
          break;
        }
        j++;
      }
      if (cfg.html) {
        const tag = /^<([a-zA-Z][\w-]*)/.exec(text.slice(i, j));
        const name = tag ? tag[1].toLowerCase() : '';
        if (name === 'style' || name === 'script') {
          const close = text.toLowerCase().indexOf('</' + name, j);
          const stop = close < 0 ? n : close;
          const sub = text.slice(j, stop);
          const r = scanCLike(sub, name === 'style' ? CFG.css : CFG.js);
          for (const cm of r.comments) comments.push({ start: j + cm.start, end: j + cm.end, kind: cm.kind });
          for (const a of r.anomalies) anomalies.push({ at: j + a.at, msg: a.msg });
          i = stop;
          continue;
        }
      }
      i = j;
      continue;
    }
    i++;
  }
  return { comments, anomalies };
}

function scanText(text, lang) {
  if (lang === 'json') {
    try {
      JSON.parse(text);
      return { comments: [], anomalies: [] };
    } catch (_) {
      return scanCLike(text, CFG.jsonc);
    }
  }
  if (lang === 'yaml') return scanYaml(text);
  if (lang === 'sh') return scanSh(text);
  if (lang === 'py') return scanHash(text, { bang: false });
  if (lang === 'hash') return scanHash(text, { bang: true });
  if (lang === 'gitignore') return scanHash(text, { bang: false });
  if (lang === 'html') return scanXml(text, { html: true });
  if (lang === 'xml') return scanXml(text, { html: false });
  const cfg = CFG[lang];
  if (!cfg) return { comments: [], anomalies: [] };
  return scanCLike(text, cfg);
}

function stripText(text, comments) {
  if (!comments.length) return collapseBlank(text);
  const ops = [];
  for (const c of comments) {
    const ls = text.lastIndexOf('\n', c.start - 1) + 1;
    let le = text.indexOf('\n', c.end);
    if (le < 0) le = text.length;
    const before = text.slice(ls, c.start);
    const after = text.slice(c.end, le);
    const aloneBefore = before.trim() === '';
    const aloneAfter = after.trim() === '';
    if (aloneBefore && aloneAfter) {
      ops.push({ start: ls, end: le < text.length ? le + 1 : le, repl: '' });
    } else if (c.kind === 'line') {
      ops.push({ start: c.start, end: c.end, repl: '' });
    } else {
      ops.push({ start: c.start, end: c.end, repl: ' ' });
    }
  }
  ops.sort((a, b) => a.start - b.start);
  let out = '';
  let cur = 0;
  const touched = [];
  for (const op of ops) {
    if (op.start < cur) continue;
    out += text.slice(cur, op.start) + op.repl;
    if (op.repl === '') touched.push(out.length);
    cur = op.end;
  }
  out += text.slice(cur);
  const lines = out.split('\n');
  const touchedLines = new Set(touched.map((idx) => {
    let ln = 0;
    for (let k = 0; k < idx && k < out.length; k++) if (out[k] === '\n') ln++;
    return ln;
  }));
  for (const ln of touchedLines) {
    if (ln >= 0 && ln < lines.length) lines[ln] = lines[ln].replace(/[ \t]+$/, '');
  }
  return collapseBlank(lines.join('\n'));
}

function collapseBlank(text) {
  const hadNl = text.endsWith('\n');
  let out = text.replace(/\n{3,}/g, '\n\n');
  out = out.replace(/^\n+/, '');
  out = out.replace(/\n+$/, '');
  return hadNl ? out + '\n' : out;
}

function listTargets() {
  const out = [];
  const walk = (dir) => {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
      if (e.isDirectory()) {
        if (SKIP_DIRS.has(e.name)) continue;
        walk(path.join(dir, e.name));
        continue;
      }
      if (SKIP_FILES.has(e.name)) continue;
      const ext = path.extname(e.name).toLowerCase();
      const base = path.basename(e.name).toLowerCase();
      if (GENERATED.has(path.relative(ROOT, path.join(dir, e.name)))) continue;
      if (GENERATED_REPORTS.has(e.name)) continue;
      let lang = LANGS[ext] || NAME_LANGS[base] || null;
      if (!lang && base === 'panel') lang = 'js';
      if (!lang) continue;
      const abs = path.join(dir, e.name);
      out.push({ abs, rel: path.relative(ROOT, abs), lang });
    }
  };
  walk(ROOT);
  return out.sort((a, b) => a.rel.localeCompare(b.rel));
}

const PROSE_KEYS = ['_note', '$comment', 'notes'];

function jsonProsePass(fix) {
  const changed = [];
  for (const t of listTargets()) {
    if (t.lang !== 'json') continue;
    const src = fs.readFileSync(t.abs, 'utf8');
    let obj;
    try {
      obj = JSON.parse(src);
    } catch (_) {
      continue;
    }
    let hit = false;
    if (obj && typeof obj === 'object' && !Array.isArray(obj)) {
      for (const k of PROSE_KEYS) {
        if (Object.prototype.hasOwnProperty.call(obj, k)) {
          delete obj[k];
          hit = true;
        }
      }
    }
    if (!hit) continue;
    const out = JSON.stringify(obj, null, 2) + '\n';
    changed.push(t.rel);
    if (fix) fs.writeFileSync(t.abs, out);
  }
  return changed;
}

function run(opts) {
  const targets = listTargets();
  const report = { files: 0, comments: 0, allowed: 0, removed: 0, anomalies: [], offenders: [], perLang: {}, todo: [] };
  for (const t of targets) {
    const text = fs.readFileSync(t.abs, 'utf8');
    const r = scanText(text, t.lang);
    report.files++;
    report.perLang[t.lang] = report.perLang[t.lang] || { files: 0, comments: 0, removed: 0 };
    report.perLang[t.lang].files++;
    report.perLang[t.lang].comments += r.comments.length;
    report.comments += r.comments.length;
    for (const a of r.anomalies) report.anomalies.push(t.rel + ':' + a.msg + '@' + a.at);
    const allowed = r.comments.filter((c) => isAllowed(text.slice(c.start, c.end)));
    const strip = r.comments.filter((c) => !isAllowed(text.slice(c.start, c.end)));
    report.allowed += allowed.length;
    report.removed += strip.length;
    report.perLang[t.lang].removed += strip.length;
    for (const c of strip) {
      const body = text.slice(c.start, c.end);
      if (/\b(TODO|FIXME|XXX|HACK)\b/.test(body)) report.todo.push(t.rel);
    }
    if (opts.check && strip.length) report.offenders.push(t.rel + ' (' + strip.length + ')');
    if (opts.fix && strip.length) {
      const out = stripText(text, strip);
      fs.writeFileSync(t.abs, out);
    }
  }
  if (opts.jsonKeys) {
    report.jsonChanged = jsonProsePass(!!opts.fix);
  }
  return report;
}

if (require.main === module) {
  const args = process.argv.slice(2);
  const opts = {
    fix: args.includes('--fix'),
    check: args.includes('--check') || args.length === 0,
    jsonKeys: args.includes('--json-keys'),
    json: args.includes('--json')
  };
  const report = run(opts);
  if (opts.json) {
    process.stdout.write(JSON.stringify(report, null, 2) + '\n');
  } else {
    if (opts.fix) {
      process.stdout.write('removed ' + report.removed + ' comments in ' + report.files + ' files; kept ' + report.allowed + ' directive comments\n');
      if (report.jsonChanged) process.stdout.write('json prose keys removed from: ' + report.jsonChanged.join(', ') + '\n');
    }
    if (opts.check) {
      process.stdout.write('files with comments: ' + report.offenders.length + '\n');
      for (const o of report.offenders.slice(0, 40)) process.stdout.write('  ' + o + '\n');
    }
    process.stdout.write('by language: ' + JSON.stringify(report.perLang) + '\n');
    if (report.anomalies.length) process.stdout.write('anomalies: ' + report.anomalies.slice(0, 20).join(', ') + '\n');
  }
  if (opts.check && report.offenders.length) process.exit(1);
}

module.exports = { ROOT, listTargets, scanText, stripText, isAllowed, run, LANGS, NAME_LANGS, GENERATED, GENERATED_REPORTS, PROSE_KEYS };
