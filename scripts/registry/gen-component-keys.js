#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const ROOT = path.resolve(__dirname, '..');
const PUB_SUPPLY = path.join(ROOT, 'container/app/src/main/assets/supply/component-public.pem');
const PUB_OTA = path.join(ROOT, 'container/app/src/main/assets/supply/component-public.pem');
const ARGS = process.argv.slice(2);
const FORCE = ARGS.includes('--force');
const DIR_ARG = ARGS.find((a) => !a.startsWith('--'));
const OUT_DIR = DIR_ARG || path.join(ROOT, '..', '_secrets');
const OUT_KEY = path.join(OUT_DIR, 'component-ed25519-private.pem');

if (!fs.existsSync(OUT_DIR)) {
  console.error('[genkeys] 私钥输出目录不存在: ' + OUT_DIR);
  process.exit(2);
}

const curSupply = fs.existsSync(PUB_SUPPLY) ? fs.readFileSync(PUB_SUPPLY, 'utf8') : '';
const curOta = fs.existsSync(PUB_OTA) ? fs.readFileSync(PUB_OTA, 'utf8') : '';

if (!FORCE) {
  if (curSupply && curOta && curSupply !== curOta) {
    console.error('[genkeys] 两个公钥文件内容不同 —— 设备侧 supply 与 ota 会用两把不同的钥验签。');
    console.error('          先查清为何分叉，再决定是否重生成（--force 会覆盖两者）。');
    process.exit(1);
  }
  if (curSupply && fs.existsSync(OUT_KEY)) {
    const pub = crypto.createPublicKey(fs.readFileSync(OUT_KEY)).export({ type: 'spki', format: 'pem' });
    if (pub.toString() === curSupply) {
      console.log('[genkeys] 已有配对密钥，公钥与私钥一致 —— 不重生成。');
      process.exit(0);
    }
  }
  if (curSupply && !fs.existsSync(OUT_KEY)) {
    console.log('[genkeys] 仓内已有公钥，但 ' + OUT_KEY + ' 不在 —— 无法证明配对。');
    console.log('          旧私钥一旦丢失，仓内公钥就成了只能验、不能签的孤儿。');
    console.log('          确要换钥请显式加 --force。');
    process.exit(1);
  }
}

const { publicKey, privateKey } = crypto.generateKeyPairSync('ed25519');
const pubPem = publicKey.export({ type: 'spki', format: 'pem' });
const privPem = privateKey.export({ type: 'pkcs8', format: 'pem' });

const check = crypto.verify(null, Buffer.from('probe'), publicKey,
  crypto.sign(null, Buffer.from('probe'), privateKey));
if (!check) {
  console.error('[genkeys] 判据自证失败：新生成的密钥对自验不通过');
  process.exit(1);
}

fs.writeFileSync(PUB_SUPPLY, pubPem);
fs.writeFileSync(PUB_OTA, pubPem);
fs.writeFileSync(OUT_KEY, privPem, { mode: 0o600 });
fs.chmodSync(OUT_KEY, 0o600);

const fp = crypto.createHash('sha256').update(publicKey.export({ type: 'spki', format: 'der' })).digest('hex');
console.log('[genkeys] 已生成 ed25519 密钥对');
console.log('          公钥（已提交进仓，APK 内置为信任根）:');
console.log('            container/app/src/main/assets/supply/component-public.pem');
console.log('            container/app/src/main/assets/supply/component-public.pem');
console.log('          私钥（不入库）: ' + OUT_KEY);
console.log('          公钥指纹 sha256(SPKI DER) = ' + fp);
console.log();
console.log('下一步：签清单用 publish-component-manifest.js 的第三个位置参数（私钥路径）。');
console.log('        它签完会立刻用仓内公钥自验是否配对，不配对就拒绝发布。');
console.log('        revision 必须由 tag 决定（LOBOS_COMPONENT_REVISION），线上已到 6，下一个至少 7。');
