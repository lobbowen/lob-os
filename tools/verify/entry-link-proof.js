const fs = require('fs'), os = require('os'), path = require('path');
const W = fs.mkdtempSync(path.join(os.tmpdir(), 'lobos-link-'));
const BIN = path.join(W, 'usr', 'bin');
const DEST = path.join(W, 'programs', 'jq', '1.7.1', 'bin');
fs.mkdirSync(BIN, { recursive: true });
fs.mkdirSync(DEST, { recursive: true });
fs.writeFileSync(path.join(DEST, 'jq'), '#!/bin/sh\necho jq\n');

const entryRel = 'bin/jq';
const linkPath = path.join(BIN, entryRel.split('/').pop());

const linkedA = false;
const entryOkA = fs.existsSync(linkPath) && fs.statSync(linkPath).isFile();
console.log('[形态A] linkEntry 失败');
console.log('  linkEntry 返回      =', linkedA);
console.log('  安装流程报          = 成功（修复前的行为：返回值被忽略）');
console.log('  CatalogClient.entryOk =', entryOkA, '（控制面板能看到「入口不可用」）');
console.log('  → 两者矛盾：装好了 vs 调不到');

try { fs.symlinkSync(path.join(DEST, 'jq'), linkPath); } catch (e) { console.log('  建链异常:', e.message); }

const linkedB = true;
const entryOkB = fs.existsSync(linkPath) && fs.statSync(linkPath).isFile();
console.log('\n[形态B] linkEntry 成功');
console.log('  linkEntry 返回      =', linkedB);
console.log('  安装流程报          =', linkedB ? '成功' : '失败', '（修复后：跟着 linkEntry 走）');
console.log('  CatalogClient.entryOk =', entryOkB);

console.log('\n== 结论 ==');
console.log('  entryOk 能发现「链没建成」，但它出现在**控制面板**（事后）。');
console.log('  安装当场报成功 → 用户以为装好了，程序第一次 spawn 才炸。');
console.log('  修复后安装当场判红，报错里指名软链路径与可能原因（SELinux/不可写）。');

fs.rmSync(W, { recursive: true, force: true });