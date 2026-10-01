'use strict';
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..');
const BANNED = /(DeviceAdminReceiver|set-device-owner|setKiosk|LockTask|setProfileOwner|removeActiveAdmin)/;
const SCOPES = [
  'container/app/src/main/java/lobos',
  'container/engine/src',
  'container/app/src/main',
  'scripts',
  '.github/workflows',
];
const hits = [];

function walk(p) {
  let st;
  try { st = fs.statSync(p); } catch (_e) { return; }
  if (st.isFile()) {
    if (p.endsWith(path.join("scripts", "do-gate.js"))) return;
    if (!/\.(kt|java|xml|js|c|h|yml|yaml|json)$/.test(p)) return;
    if (BANNED.test(fs.readFileSync(p, 'utf8'))) hits.push(path.relative(ROOT, p));
    return;
  }
  for (const e of fs.readdirSync(p)) walk(path.join(p, e));
}

for (const s of SCOPES) walk(path.join(ROOT, s));

const selfProof = BANNED.test('class X : DeviceAdminReceiver()');
if (!selfProof) {
  console.log('FAIL 判据自证失败：扫描器抓不住样本');
  process.exit(1);
}
if (hits.length) {
  console.log('FAIL DO 跑不通：不许启用设备所有者（' + hits.join(', ') + '）；档位探测允许');
  process.exit(1);
}
console.log('PASS DO 门禁：全仓没有启用设备所有者的代码（只允许探测档位）');
