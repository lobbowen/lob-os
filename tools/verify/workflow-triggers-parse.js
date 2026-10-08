'use strict';
'use strict';

const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..', '..');
const DIR = path.join(ROOT, '.github', 'workflows');

const EVENTS = new Set([
  'push', 'pull_request', 'pull_request_target', 'schedule', 'workflow_dispatch',
  'workflow_call', 'workflow_run', 'repository_dispatch', 'release', 'deployment',
  'deployment_status', 'page_build', 'status', 'check_run', 'check_suite', 'issue_comment',
  'merge_group', 'branch_protection_rule', 'create', 'delete', 'fork',
]);
const FILTERS = new Set(['branches', 'branches-ignore', 'paths', 'paths-ignore', 'tags', 'tags-ignore']);

function parseOn(src) {
  const L = src.split('\n');
  const at = L.findIndex((l) => /^on:\s*$/.test(l) || /^on:\s*\{/.test(l));
  if (at < 0) return null;
  if (/^on:\s*\{/.test(L[at])) return { inline: true };
  let end = L.length;
  for (let i = at + 1; i < L.length; i++) {
    if (!L[i].trim()) continue;
    if (!/^\s/.test(L[i])) { end = i; break; }
  }
  const keys = [];
  for (let i = at + 1; i < end; i++) {
    if (!L[i].trim()) continue;
    if (L[i].match(/^ */)[0].length !== 2) continue;
    const m = /^ *([A-Za-z0-9_-]+):/.exec(L[i]);
    if (m) keys.push({ name: m[1], line: i + 1 });
  }
  return { keys };
}

const SELF = [
  {
    name: 'tags-挂在-on-层',
    src: 'on:\n  push:\n    branches: [main]\n  workflow_dispatch: {}\n  tags:\n    - \'x\'\n',
    bad: ['tags'],
  },
  {
    name: 'tags-正确挂在-push-下',
    src: 'on:\n  push:\n    branches: [main]\n    tags:\n      - \'x\'\n  workflow_dispatch: {}\n',
    bad: [],
  },
  {
    name: 'branches-挂在-on-层',
    src: 'on:\n  push:\n  branches: [main]\n',
    bad: ['branches'],
  },
];

let selfBad = 0;
for (const t of SELF) {
  const p = parseOn(t.src);
  const got = p && !p.inline ? p.keys.filter((k) => FILTERS.has(k.name)).map((k) => k.name) : [];
  const want = t.bad.slice().sort().join(',');
  const have = got.slice().sort().join(',');
  if (have !== want) {
    console.error(`FAIL 门禁自身失效：反例 ${t.name} 期望 [${want}]，实际 [${have}]`);
    selfBad++;
  }
  if (t.name === 'tags-挂在-on-层' && p.keys.some((k) => !EVENTS.has(k.name) && !FILTERS.has(k.name))) {
  }
}
if (selfBad) {
  console.error('门禁自己的反例没过 —— 下面这些结果不可信，先修门禁');
  process.exit(2);
}

const SCALARS = /^(\s*- (?:name|run|uses|id|if|shell):\s*)(\S.*)$/;

function unquotedMapKeys(src) {
  const out = [];
  const L = src.split('\n');
  for (let i = 0; i < L.length; i++) {
    const m = SCALARS.exec(L[i]);
    if (!m) continue;
    const v = m[2];
    if (/^["\x27|>{[]/.test(v)) continue;
    const c = /([A-Za-z_][A-Za-z0-9_-]*):(\s|$)/.exec(v);
    if (c) out.push({ line: i + 1, key: c[1], value: v });
  }
  return out;
}

const SCALAR_SELF = [
  { name: 'name 里带 on:', src: 'jobs:\n  a:\n    steps:\n      - name: 结构 — on: 块\n        run: x\n', bad: true },
  { name: 'name 里带 paths 冒号加空格', src: 'jobs:\n  a:\n    steps:\n      - name: 结构 — paths: 那个键\n        run: x\n', bad: true },
  { name: '正常中文 name', src: 'jobs:\n  a:\n    steps:\n      - name: 结构 — 三筐分类自洽\n        run: x\n', bad: false },
  { name: '加引号的带冒号', src: 'jobs:\n  a:\n    steps:\n      - name: "结构 — on: 块"\n        run: x\n', bad: false },
  { name: 'run 里的 shell 赋值', src: 'jobs:\n  a:\n    steps:\n      - name: x\n        run: FOO=1 bash -n a.sh\n', bad: false },
];

let scalarBad = 0;
for (const t of SCALAR_SELF) {
  const got = unquotedMapKeys(t.src).length > 0;
  if (got !== t.bad) {
    console.error(`FAIL 门禁自身失效：反例 ${t.name} 期望 ${t.bad ? '抓到' : '放过'}，实际${got ? '抓到了' : '放过了'}`);
    scalarBad++;
  }
}
if (scalarBad) {
  console.error('门禁自己的反例没过 —— 下面这些结果不可信，先修门禁');
  process.exit(2);
}

const files = fs.readdirSync(DIR).filter((f) => /\.ya?ml$/.test(f)).sort();
let red = 0;
for (const f of files) {
  const src = fs.readFileSync(path.join(DIR, f), 'utf8');
  const p = parseOn(src);
  if (!p || p.inline) continue;
  const bad = p.keys.filter((k) => FILTERS.has(k.name));
  const unknown = p.keys.filter((k) => !EVENTS.has(k.name) && !FILTERS.has(k.name));
  if (bad.length) {
    red++;
    console.log(`FAIL ${f}  ${bad.map((k) => `${k.name}(第${k.line}行)`).join(' · ')} 挂在 on: 层，不是某个触发事件下面`);
    console.log('     GitHub 会拒绝解析整个文件：它既不 push 触发、也不接受 dispatch，');
    console.log('     而且不产生 run 失败 —— 只表现为「怎么不触发」，查不到原因。');
  }
  for (const k of unknown) {
    red++;
    console.log(`FAIL ${f}  第${k.line}行的 "${k.name}" 既不是 GitHub 的触发事件，也不是它下面的过滤键`);
  }
  for (const s of unquotedMapKeys(src)) {
    red++;
    console.log(`FAIL ${f}  第 ${s.line} 行的标量里带 "${s.key}: " —— 没加引号，GitHub 会当成映射：`);
    console.log(`     ${s.value.slice(0, 72)}`);
    console.log('     同样表现为 dispatch 422 + 0 个 job，不产生 run 失败。');
  }
}

if (red) {
  console.log('');
  console.log(`FAIL workflow 可解析性：${red} 处。`);
  console.log('     这两类错都会让 GitHub 拒绝解析**整个文件**：dispatch 422、0 个 job、');
  console.log('     push 静默不触发，而且不产生 run 失败 —— 只表现为「怎么不触发」。');
  console.log('     修法：tags/branches/paths 缩进到某个事件下面；带 "键: " 的标量加引号。');
  process.exit(1);
}
console.log(`PASS workflow 可解析性：${files.length} 个 workflow 的触发块与标量都在合法形状上`);