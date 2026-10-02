const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const A = path.join(ROOT, 'container/engine/src/sign.js');
const B = path.join(ROOT, 'container/app/src/main/assets/node/program-verify.js');

function fail(msg) {
  console.error('[dual-canonical] ' + msg);
  process.exit(1);
}

function grab(s) {
  const i = s.indexOf('function canonical(obj)');
  if (i < 0) return null;
  const j = s.indexOf('\nfunction ', s.indexOf('function canonValue'));
  if (j < 0) return s.slice(i).trim();
  return s.slice(i, j).trim();
}

const sa = fs.readFileSync(A, 'utf8');
const sb = fs.readFileSync(B, 'utf8');
const ca = grab(sa);
const cb = grab(sb);

if (!ca) fail('container/engine/src/sign.js 里找不到 canonical');
if (!cb) fail('assets/node/program-verify.js 里找不到 canonical');
if (ca !== cb) {
  fail('两份 canonical 已经分叉 —— CI 侧签名与设备侧验签会得出不同结果（信任链断裂）：');
  fail('  A (engine/src/sign.js): ' + ca.slice(0, 100));
  fail('  B (program-verify.js): ' + cb.slice(0, 100));
}

const { canonical } = require(A);
const nested = canonical({ engines: { node: '>=18', inject: 1 }, gate: { allow: false } });
if (!nested.includes('">=18"') || !nested.includes('"inject":1') || !nested.includes('"allow":false')) {
  fail('canonical 未覆盖嵌套字段：' + nested);
}

console.log('[dual-canonical] 两份 canonical 逐字一致，且嵌套字段纳入签名覆盖');