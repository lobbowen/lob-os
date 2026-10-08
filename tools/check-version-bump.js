'use strict';
const { execFileSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..');
const VJ = path.join(ROOT, 'version.json');
const REL = 'version.json';

if (!fs.existsSync(VJ)) {
  console.error(`FAIL 找不到 ${REL} —— APK 版本的唯一真相没了`);
  process.exit(2);
}

const vj = JSON.parse(fs.readFileSync(VJ, 'utf8'));
const s = (vj && vj.shell) || {};
const NAME = s.versionName;
const CODE = s.versionCode;

function bad(msg) {
  console.error('FAIL 版本管理：' + msg);
  process.exit(1);
}

if (typeof NAME !== 'string') bad(`versionName 必须是字符串（读到 ${JSON.stringify(NAME)}）`);
const SEMVER = /^(\d+)\.(\d+)\.(\d+)$/;
const m = SEMVER.exec(NAME);
if (!m) {
  bad(
    `versionName "${NAME}" 不是 MAJOR.MINOR.PATCH。` +
    ' MAJOR 变 = 不兼容的协议或格式变更；MINOR 变 = 加了能力；PATCH 变 = 只修。'
  );
}
if (!Number.isInteger(CODE) || CODE < 1) {
  bad(`versionCode 必须是 ≥1 的整数（读到 ${JSON.stringify(CODE)}）—— 它是 OTA 判「是不是更新」的唯一依据`);
}

function gitOut(args) {
  try {
    return execFileSync('git', ['-C', ROOT, ...args], { encoding: 'utf8' }).trim();
  } catch (_e) {
    return null;
  }
}

const tags = gitOut(['tag', '-l']) || '';
const relTags = tags.split('\n').filter((t) => /^release-\d+\.\d+\.\d+$/.test(t));

if (relTags.length) {
  const last = relTags[relTags.length - 1];
  const lastName = last.slice('release-'.length);
  const lastCode = parseInt(lastName.split('.').join(''), 10);

  if (CODE < lastCode) {
    bad(
      `versionCode 从 ${lastCode}（上一次发布 ${last}）退到了 ${CODE}。` +
      ' Android 靠 code 单调判能否覆盖安装：code 变小，已装的设备就再也收不到更新。' +
      ' 要么继续往上加，要么明确这是新分支（那就用新的 tag 序列并在 docs/VERSIONING.md 写明）。'
    );
  }
  if (NAME === lastName && CODE === lastCode) {
    bad(
      `版本号与上一次发布完全相同（${last}）。` +
      ' 同号只能配同一批字节 —— 内容变了而号没变，设备会按「已是最新」跳过更新。' +
      ' 内容变了就必须提 versionCode。'
    );
  }
  if (CODE === lastCode && NAME !== lastName) {
    bad(
      `versionName 变成 "${NAME}" 但 versionCode 还是 ${lastCode}（上一次发布 ${last}）。` +
      ' 设备只看 code 判更新：换了名字却不加 code，这一版永远装不到。'
    );
  }
  const [lm, ln] = lastName.split('.').map(Number);
  const [cm, cn] = m.slice(1).map(Number);
  if (cm > lm) {
    console.log(
      `     注意：MAJOR 从 ${lm} 升到 ${cm} —— 这是不兼容变更。` +
      ' 存量设备上的数据与协议都要迁移；在 docs/VERSIONING.md 写明迁移说明后再发。'
    );
  } else if (cm === 0 && cn > ln) {
    console.log(
      `     注意：0.x 段里 MINOR 从 ${ln} 升到 ${cn} —— 按惯例 0.x 的 MINOR 变化视为可能不兼容，` +
      '在 docs/VERSIONING.md 写明。'
    );
  }
}

if (!Number.isInteger(s.bridgeProtocol) || s.bridgeProtocol < 1) {
  bad(`bridgeProtocol 必须是 ≥1 的整数（读到 ${JSON.stringify(s.bridgeProtocol)}）`);
}

console.log(`PASS 版本管理：versionName=${NAME}（语义化）versionCode=${CODE}（单调）bridgeProtocol=${s.bridgeProtocol}`);
if (relTags.length) console.log(`     上一次发布：${relTags[relTags.length - 1]}`);