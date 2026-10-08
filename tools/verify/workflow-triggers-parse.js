'use strict';
// workflow 的 on: 块必须能被 GitHub 解析。
//
// 具体修的是「tags 挂错层」：tags 是 push: 的子键，写成 on: 的同级键时
// 整个文件解析失败 —— GitHub 直接拒绝这个 workflow（dispatch 返回 422
// "Unexpected value 'tags'"），它连 push 触发都不会跑，而且**不报 run 失败**。
//
// 这不是排版洁癖：一份解析不过的 workflow 在 Actions 列表里是静默的，
// 人只会看到「怎么不触发」，查不到原因。
//
// 判据：on: 下每个键都必须是被 GitHub 承认的触发事件名，
//     而 tags/branches/paths 只能出现在某个触发事件**下面**。
'use strict';

const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..', '..');
const DIR = path.join(ROOT, '.github', 'workflows');

// GitHub 承认的触发事件（子键：它们各自能带哪些过滤条件）
const EVENTS = new Set([
  'push', 'pull_request', 'pull_request_target', 'schedule', 'workflow_dispatch',
  'workflow_call', 'workflow_run', 'repository_dispatch', 'release', 'deployment',
  'deployment_status', 'page_build', 'status', 'check_run', 'check_suite', 'issue_comment',
  'merge_group', 'branch_protection_rule', 'create', 'delete', 'fork',
]);
// 只在某个事件下才合法的过滤键
const FILTERS = new Set(['branches', 'branches-ignore', 'paths', 'paths-ignore', 'tags', 'tags-ignore']);

function parseOn(src) {
  const L = src.split('\n');
  const at = L.findIndex((l) => /^on:\s*$/.test(l) || /^on:\s*\{/.test(l));
  if (at < 0) return null;
  if (/^on:\s*\{/.test(L[at])) return { inline: true };
  // on: 块的范围：从 on: 之后到下一个**顶层**键（缩进 0 的非空行）。
  // 不能靠「缩进 <= 2 就停」来判边界 —— push: / tags: 这些直接子键本身就是缩进 2，
  // 那样会把 concurrency / permissions / jobs 全算进 on: 块（第一版就是这么错的）。
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

// 门禁自身先过已知反例
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
  // 另一侧：未知的事件名也要抓到
  if (t.name === 'tags-挂在-on-层' && p.keys.some((k) => !EVENTS.has(k.name) && !FILTERS.has(k.name))) {
    // tags 是已知的错法，交给上面的 FILTERS 检查
  }
}
if (selfBad) {
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
}

if (red) {
  console.log('');
  console.log(`FAIL on: 块：${red} 处。tags / branches / paths 必须缩进到某个事件（push: 等）下面`);
  process.exit(1);
}
console.log(`PASS on: 块：${files.length} 个 workflow 的触发事件与过滤键都在合法层上`);