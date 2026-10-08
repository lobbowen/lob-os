'use strict';

const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..', '..');
const DIR = path.join(ROOT, '.github', 'workflows');

if (!fs.existsSync(DIR)) {
  console.error(`FAIL 找不到 workflow 目录：${DIR}`);
  process.exit(2);
}

function steps(src) {
  return src.split('\n');
}
function pkgLine(L) { return L.findIndex((l) => /run:\s*bash scripts\/package-component\.sh /.test(l)); }

function findStep(L, name) {
  return L.findIndex((l) => new RegExp(`^ {6}- name: ${name}`).test(l));
}

const SELF_TEST = [
  {
    name: 'release-before-package',
    src: [
      'jobs:', '  build:', '    steps:',
      '      - name: 发布本件预制品到 Release', '        run: x',
      '      - name: 打包 + sha256', '        run: bash scripts/package-component.sh jq',
    ].join('\n'),
    bad: ['Release'],
  },
  {
    name: 'cache-before-package',
    src: [
      'jobs:', '  build:', '    steps:',
      '      - name: 存回编译中间产物', '        run: x',
      '      - name: 打包 + sha256', '        run: bash scripts/package-component.sh jq',
    ].join('\n'),
    bad: ['存缓存'],
  },
  {
    name: 'package-first',
    src: [
      'jobs:', '  build:', '    steps:',
      '      - name: 打包 + sha256', '        run: bash scripts/package-component.sh jq',
      '      - name: 存回编译中间产物', '        run: x',
      '      - name: 发布本件预制品到 Release', '        run: x',
    ].join('\n'),
    bad: [],
  },
];

let selfBad = 0;
for (const t of SELF_TEST) {
  const L = steps(t.src);
  const pkg = pkgLine(L);
  if (pkg < 0) {
    console.error(`FAIL 门禁自身失效：反例 ${t.name} 里找不到打包步`);
    selfBad++;
    continue;
  }
  const got = [];
  if (findStep(L, '发布本件预制品到 Release') >= 0 && findStep(L, '发布本件预制品到 Release') < pkg) got.push('Release');
  if (findStep(L, '存回编译中间产物') >= 0 && findStep(L, '存回编译中间产物') < pkg) got.push('存缓存');
  const want = t.bad.slice().sort().join(',');
  const have = got.slice().sort().join(',');
  if (have !== want) {
    console.error(`FAIL 门禁自身失效：反例 ${t.name} 期望 [${want}]，实际 [${have}]`);
    selfBad++;
  }
}
if (selfBad) {
  console.error('门禁自己的反例没过 —— 下面这些结果不可信，先修门禁');
  process.exit(2);
}

const files = fs.readdirSync(DIR)
  .filter((f) => /^build-[a-z0-9-]+\.yml$/.test(f))
  .sort();

let red = 0;
let checked = 0;
for (const f of files) {
  const L = steps(fs.readFileSync(path.join(DIR, f), 'utf8'));
  const pkg = pkgLine(L);
  if (pkg < 0) continue;
  checked++;
  for (const what of ['发布本件预制品到 Release', '存回编译中间产物']) {
    const at = findStep(L, what);
    if (at >= 0 && at < pkg) {
      red++;
      console.log(`FAIL ${f}  「${what}」步（第 ${at + 1} 行）排在「打包 + sha256」步（第 ${pkg + 1} 行）之前`);
    }
  }
}

if (red) {
  console.log('');
  console.log(`FAIL 步骤顺序：${red} 处。run 照样是绿的，坏的是下一轮：`);
  console.log('     · Release 的 tar 里装的是松散文件，下一轮「已发布就复用」拿到一份没有 zip 的预制品');
  console.log('     · 存回缓存存的是上一轮的旧 zip，下一轮「增量编」用的是过期件');
  process.exit(1);
}
console.log(`PASS 步骤顺序：${checked} 条打包链里，「打包 → 存缓存 → 发 Release」的顺序全部正确`);