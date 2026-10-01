'use strict';
const path = require('node:path');
const fs = require('node:fs');
const tool = require(path.join(__dirname, 'strip-comments.js'));

const report = tool.run({});
let bad = 0;
const offenders = report.offenders || [];
if (report.removed !== 0) {
  console.log('FAIL 不许上注释：仍剩 ' + report.removed + ' 条，例如 ' + offenders.slice(0, 8).join(', '));
  bad++;
}
if (report.allowed > 20) {
  console.log('FAIL 功能性指令过多（' + report.allowed + ' > 20）');
  bad++;
}
if ((report.anomalies || []).length) {
  console.log('FAIL 词法扫描异常：' + report.anomalies.slice(0, 3).join(', '));
  bad++;
}
const prose = [];
for (const t of tool.listTargets()) {
  if (t.lang !== 'json') continue;
  let obj;
  try { obj = JSON.parse(fs.readFileSync(t.abs, 'utf8')); } catch (_e) { continue; }
  if (obj && typeof obj === 'object' && !Array.isArray(obj)) {
    for (const k of tool.PROSE_KEYS) {
      if (Object.prototype.hasOwnProperty.call(obj, k)) prose.push(t.rel + '.' + k);
    }
  }
}
if (prose.length) {
  console.log('FAIL JSON 里不许写散文键：' + prose.slice(0, 5).join(', '));
  bad++;
}
console.log(bad === 0 ? 'PASS 注释门禁：零遗留注释、无 JSON 散文键' : 'FAIL 注释门禁');
process.exit(bad === 0 ? 0 : 1);
