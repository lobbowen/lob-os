'use strict';
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..', '..');
const DIR = path.join(ROOT, 'scripts');
const SELF = path.join(ROOT, 'tools', 'verify', 'bare-vars-in-strings.js');

const ENV_INJECTED = new Set([
  'CC', 'CXX', 'AR', 'RANLIB', 'LD', 'CFLAGS', 'CXXFLAGS',
  'LDFLAGS', 'CPPFLAGS', 'LLVM_AR', 'LLVM_RANLIB', 'LLVM_STRIP',
  'LLVM_READELF', 'LLVM_NM', 'LLVM_OBJDUMP', 'ANDROID_API', 'ANDROID_ABI',
  'ANDROID_NDK', 'ANDROID_NDK_HOME', 'ANDROID_NDK_ROOT', 'ANDROID_NDK_LATEST_HOME',
  'ANDROID_HOME', 'ANDROID_SDK_ROOT', 'ANDROID_SDK_HOME', 'ABI', 'API',
  'JOBS', 'OUT', 'WORK', 'SRC', 'BUILD', 'INST', 'STAGE',
  'PREFIX', 'TOOL', 'DEPS', 'NINJA', 'KEEP_GOING', 'GITHUB_REPOSITORY',
  'GITHUB_ENV', 'GITHUB_OUTPUT', 'GITHUB_PATH', 'GITHUB_STEP_SUMMARY',
  'GITHUB_TOKEN', 'GH_TOKEN', 'GH_RELEASE_ASSETS', 'BUILD_PYTHON',
  'CPU_JOBS', 'NODE_BUILD_JOBS', 'LLVM_VERSION', 'PINNED_LLVM', 'KEEP_BUILD',
  'BUILD_TB', 'SKIP_FETCH',
]);

function scanFile(file) {
  const raw = fs.readFileSync(file, 'utf8');
  const rel = path.relative(ROOT, file);
  const problems = [];
  const defined = new Set();
  for (const m of raw.matchAll(/^\s*(?:export\s+|local\s+|declare\s+-\w+\s+)?([A-Za-z_][A-Za-z0-9_]*)=/gm)) defined.add(m[1]);
  for (const m of raw.matchAll(/\bfor\s+([A-Za-z_][A-Za-z0-9_]*)\s+in\b/g)) defined.add(m[1]);
  for (const m of raw.matchAll(/\bread\s+(?:-[a-z]+\s+)*([A-Za-z_][A-Za-z0-9_]*)/g)) defined.add(m[1]);
  for (const m of raw.matchAll(/\bwhile\s+([A-Za-z_][A-Za-z0-9_]*)/g)) defined.add(m[1]);

  raw.split('\n').forEach((line, idx) => {
    if ((line.match(/'/g) || []).length % 2 === 1) return;
    const clean = line.replace(/\\\$\{/g, '\x00');
    const re = /\$\{([A-Za-z_][A-Za-z0-9_]*)\}/g;
    let m;
    while ((m = re.exec(clean))) {
      const name = m[1];
      if (defined.has(name)) continue;
      if (ENV_INJECTED.has(name)) continue;
      if (new RegExp('(^|[\\s;&|])' + name + '=').test(line)) continue;
      problems.push(`[\u88f8\u53d8\u91cf] ${rel}:${idx + 1}  \${${name}}`);
    }
  });
  return problems;
}

if (!fs.existsSync(DIR)) {
  console.error(`FAIL \u626b\u63cf\u76ee\u5f55\u4e0d\u5b58\u5728\uff1a${DIR}\uff08ROOT=${ROOT}\uff09`);
  process.exit(2);
}
const files = fs.readdirSync(DIR).filter((f) => f.endsWith('.sh'));
if (!files.length) {
  console.error(`FAIL ${DIR} \u4e0b\u6ca1\u6709 .sh \u2014\u2014 \u95e8\u7981\u7a7a\u8f6c`);
  process.exit(2);
}

let bad = [];
for (const f of files) bad = bad.concat(scanFile(path.join(DIR, f)));

if (process.argv.includes('--self-test')) {
  bad = bad.concat(scanFile(SELF));
  console.log('[self-test] \u8fde\u672c\u6587\u4ef6\u4e00\u8d77\u626b\uff08\u5b83\u6ee1\u662f\u88f8 $\u2026\uff0c\u5fc5\u7136\u62a5\uff09');
}

if (bad.length) {
  for (const b of bad) console.log(b);
  console.log(`FAIL \u88f8\u53d8\u91cf\u626b\u63cf\uff1a${bad.length} \u5904\u5728 set -u \u4e0b\u4f1a\u6740\u6389\u811a\u672c`);
  process.exit(1);
}
console.log(`PASS \u88f8\u53d8\u91cf\u626b\u63cf\uff1a${files.length} \u4e2a\u811a\u672c\u91cc\u6ca1\u6709\u300c\u53cc\u5f15\u53f7\u5185\u65e0\u9ed8\u8ba4\u503c\u7684 $\{VAR\}\u300d`);
