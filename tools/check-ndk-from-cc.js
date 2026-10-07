#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..');
const SCRIPTS = path.join(ROOT, 'scripts');

let PASS = 0, FAIL = 0;
const t = (name, cond, detail) => {
  if (cond) { console.log('  [ok]   ' + name); PASS++; }
  else { console.log('  [FAIL] ' + name + (detail ? '\n         ' + detail : '')); FAIL++; }
};

const ALLOW = new Set(['verify-ndk-llvm.sh', 'build-native-capabilities.sh']);

console.log('== NDK 必须从 CC 反推，不得优先读环境变量 ==');

const files = fs.readdirSync(SCRIPTS).filter((f) => f.endsWith('.sh'));
let checked = 0;
for (const f of files) {
  if (ALLOW.has(f)) { console.log('  [跳过] ' + f + '（它就是要读环境变量）'); continue; }
  const src = fs.readFileSync(path.join(SCRIPTS, f), 'utf8');
  src.split('\n').forEach((line, i) => {
    if (line.trimStart().startsWith('#')) return;
    const assignsNdk = /(NDK_ROOT|ANDROID_NDK_ROOT|NDK)="\$\{/.test(line) || /^\s*(NDK|NDK_ROOT)="/.test(line);
    const prefersEnv = /\$\{(ANDROID_NDK_ROOT|ANDROID_NDK_HOME|ANDROID_NDK_LATEST_HOME):-/.test(line);
    if (assignsNdk && prefersEnv) {
      checked++;
      t(f + ':' + (i + 1) + ' 不优先读环境变量',
        false,
        '  ' + line.trim().slice(0, 100) +
        '\n         CI 上 CC 与 ANDROID_NDK_* 指向**不同的 NDK**（实测 30.x vs 29.x）；' +
        '\n         环境变量优先 → 拿到 runner 自带那版 → 下游按错的 NDK 核对 → ' +
        '要么找不到 gcc，要么报版本不符。改成 `X="$(cd "$(dirname "$CC")/../../../../.." && pwd)"`。');
    }
    if (/ls -d .*\/ndk\/\*.*sort -V.*tail -1/.test(line)) {
      checked++;
      t(f + ':' + (i + 1) + ' 不用「挑版本号最大的那个」当 NDK',
        false,
        '  ' + line.trim().slice(0, 100) +
        '\n         那是**猜**不是钉值；runner 上装了几版就选哪版，与 component-sources.json 无关。');
    }
  });
}
if (!checked) console.log('  （没命中任何风险写法）');

console.log('\n== 反例 ==');
const BAD = 'NDK_ROOT="${ANDROID_NDK_ROOT:-$(cd "$TC/../../../../.." && pwd)}"';
const GOOD = 'NDK_ROOT="$(cd "$TC/../../../../.." && pwd)"';
const GUESS = 'NDK=$(ls -d "$ANDROID_HOME"/ndk/' + '*' + ' | sort -V | tail -1)';
const ENV_PREF = /\$\{(ANDROID_NDK_ROOT|ANDROID_NDK_HOME|ANDROID_NDK_LATEST_HOME):-/;
const PICK_MAX = /ls -d .*\/ndk\/\*.*sort -V.*tail -1/;
t('反例：环境变量优先那行会被判红', ENV_PREF.test(BAD));
t('反例：sort -V | tail -1 会被判红', PICK_MAX.test(GUESS));
t('正确写法（无条件从 CC 反推）不会被误报',
  !ENV_PREF.test(GOOD) && !PICK_MAX.test(GOOD));

console.log('== 通过 ' + PASS + '，失败 ' + FAIL + ' ==');
process.exit(FAIL === 0 ? 0 : 1);