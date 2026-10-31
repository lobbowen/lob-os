#!/usr/bin/env node
'use strict';

// 组件清单投影与仓内声明源的对账闸门 —— 发布侧的那一份。
//
// 与 scripts/verify/check-component-manifest-drift.js 的分工：
//   · scripts/verify/ 那一份拿**线上**清单来比，判「同 name@version 有没有换字节」
//     与「revision 单调」，它是发布前最后一道闸，跑在真正投递的那一轮。
//   · 本文件拿**仓内投影**来比，判「投影里那一格与钉值表 / 档位表 / pin 记录对不对得上」。
//     它只读本地文件，不联网，可以在任意一轮（哪怕校验轮）里跑。
//
// 它比什么（逐条列清）：
//   1. 投影里的每一件在 scripts/component-sources.json 的 sources 里都有登记。
//   2. 投影里记的 version 与 sources 里记的 version 逐字相同。
//   3. 投影里记的 sha256 是一份 64 位十六进制的非空值。
//   4. .github/native-capabilities.txt 档位表里的每一个 id，在
//      .github/native-capabilities-pin.json 里都有一条 pin（取不到事实的那几条
//      必须在 pin 文件的 missing 里写明原因 —— 那是「已知缺」，不是「对不上」）。
//   5. 每一条 pin 记的版本与 sources 里记的版本逐字相同
//      （版本变了而 pin 没重新生成，就是漂移）。
//   6. 每一条 pin 记的上游源码 sha256 与 sources 里记的那一格逐字相同。
//   7. 每一条 pin 记的 capsLibName 与档位表里那一列逐字相同。
//   8. 每一条 pin 的 sha256 是一份 64 位十六进制的非空值。
//
// 用法：
//   node scripts/publish/check-component-manifest-drift.js <投影文件> [通道]
//   node scripts/publish/check-component-manifest-drift.js --immutable <投影文件> [通道]
//
// --immutable 的含义：**只读对账**。本文件在两种模式下都绝不往被对账的文件上写，
//   而 --immutable 额外做一件事 —— 读完再读一次，比对前后两次的 sha256，
//   把「这份投影文件没有被改写」这句结论变成实测出来的，而不是承诺出来的。
//
// 退出码：
//   0  没有漂移
//   1  有漂移（漂移条目逐条印在 stdout 上）
//
// 本文件不签名、不读私钥、不联网。

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const cp = require('node:child_process');

// 本文件在 scripts/publish/ 下 —— 上溯两层到仓根。
const ROOT = path.resolve(__dirname, '../..');
const ARGS = process.argv.slice(2);

const IMMUTABLE = ARGS.includes('--immutable');
const POS = ARGS.filter((a) => !a.startsWith('--'));
// 没给路径时，按「仓内投影的落位」依次找：先正式清单，再两份投影文件。
const DEFAULT_CANDIDATES = [
  path.join(ROOT, 'release', 'component-manifest.json'),
  path.join(ROOT, 'work', 'declared.json'),
  path.join(ROOT, 'work', 'native-declared.json'),
];
const LOCAL = POS[0] || DEFAULT_CANDIDATES.find((p) => fs.existsSync(p)) || '';
const CHANNEL = POS[1] || 'canary';

const SOURCES = path.join(ROOT, 'scripts', 'component-sources.json');
const CAPS = path.join(ROOT, '.github', 'native-capabilities.txt');
const PIN = path.join(ROOT, '.github', 'native-capabilities-pin.json');

const HEX64 = /^[0-9a-f]{64}$/;

const drifts = [];
const drift = (s) => drifts.push(s);

function fail(msg) {
  console.error('::error title=清单投影与仓内声明对不上::' + msg);
  console.log('[drift] 判红');
  process.exit(1);
}

function sha256File(p) {
  return crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
}

// 档位表：<tier> <libName> <id>
function parseCaps() {
  if (!fs.existsSync(CAPS)) { fail('缺少 ' + CAPS + ' —— 底座件档位表没了'); }
  const byId = new Map();
  for (const line of fs.readFileSync(CAPS, 'utf8').split('\n')) {
    const t = line.trim();
    if (!t || t.startsWith('#')) continue;
    const parts = t.split(/\s+/);
    if (parts.length < 3) continue;
    byId.set(parts[2], { tier: parts[0], libName: parts[1] });
  }
  if (!byId.size) fail('从 ' + CAPS + ' 一条也没解析出来 —— 档位表格式变了？');
  return byId;
}

function readJson(p, what) {
  if (!fs.existsSync(p)) { fail('缺少 ' + what + '：' + p); }
  try {
    return JSON.parse(fs.readFileSync(p, 'utf8'));
  } catch (e) {
    fail('读不出 ' + what + '（' + p + '）：' + e.message);
  }
}

// 那一趟的**缺件表**（=> 而那 dist/.fetch-missing.tsv 那一档、=> 而那它逐字是那四列：
//   件名 / 那一档 / 那一档的原话 / 容器形态那一格）。
//   => 而那读不到时逐字是**空**（=> 而而那不是「没有缺件」、=> 而而那是没有那张表）。
function readMissingTable() {
  const out = new Map();
  const p = path.join(ROOT, 'dist', '.fetch-missing.tsv');
  if (!fs.existsSync(p)) return out;
  let raw = '';
  try { raw = fs.readFileSync(p, 'utf8'); } catch (e) { return out; }
  for (const line of raw.split('\n')) {
    if (!line.trim()) continue;
    const parts = line.split('\t');
    const id = String(parts[0] || '').trim();
    if (!id) continue;
    out.set(id, { kind: String(parts[1] || '').trim(), detail: String(parts[2] || '').trim() });
  }
  return out;
}

// 控制面板件清单（=> 而那逐字从 cache-key.sh 的 bucket_for 出、=> 而那**那是唯一真相**）。
//   => 而那底座件那一族（base / apkonly 筐）逐字**不进**这一轴
//     （=> 而它们随 APK 走、=> 而那不在控制面板清单里、=> 而按 C-135 那不是同一问）。
function bucketComponents() {
  const lister = path.join(ROOT, 'scripts', 'registry', 'list-bucket-components.js');
  if (!fs.existsSync(lister)) { fail('缺少控制面板件清单的来源：' + lister); }
  let out = '';
  try {
    out = cp.execFileSync('node', [lister, '--buckets'], { encoding: 'utf8' });
  } catch (e) {
    fail('读不出控制面板件清单（' + lister + '）：' + String(e.stderr || e.message).slice(0, 200));
  }
  const rows = [];
  for (const line of String(out).split('\n')) {
    const t = line.trim();
    if (!t) continue;
    const parts = t.split('\t');
    if (parts.length < 2) continue;
    rows.push({ name: parts[0].trim(), bucket: parts[1].trim() });
  }
  return rows;
}

// 投影文件有两种形状：
//   · 组件投影（publish-component-manifest.js --project 的 stdout）：数组，每项有 name
//   · 组件清单（签过名的那份）：对象，有 tools 数组
//   · 底座件投影（publish-native-manifest.js --project 的 stdout）：数组，每项有 id
//   · 底座件清单（签过名的那一份）：对象，有 components 数组
function classify(parsed) {
  if (Array.isArray(parsed)) {
    if (!parsed.length) return { kind: 'empty', tools: [], components: [] };
    if (parsed[0] && parsed[0].name) return { kind: 'component', tools: parsed, components: [] };
    if (parsed[0] && parsed[0].id) return { kind: 'native', tools: [], components: parsed };
    return { kind: 'unknown', tools: [], components: [] };
  }
  if (parsed && Array.isArray(parsed.tools)) return { kind: 'component', tools: parsed.tools, components: [] };
  if (parsed && Array.isArray(parsed.components)) return { kind: 'native', tools: [], components: parsed.components };
  return { kind: 'unknown', tools: [], components: [] };
}

function main() {
  if (!LOCAL) fail('没给投影文件（用法：node scripts/publish/check-component-manifest-drift.js [--immutable] <投影文件> [通道]）');
  if (!fs.existsSync(LOCAL)) fail('投影文件不存在：' + LOCAL);

  const before = sha256File(LOCAL);
  const parsed = readJson(LOCAL, '投影文件');
  const shape = classify(parsed);

  const sourcesDoc = readJson(SOURCES, '钉值表');
  const sources = sourcesDoc.sources || {};
  const caps = parseCaps();
  const pinDoc = readJson(PIN, '底座件 pin 记录');
  const pins = pinDoc.pins || {};
  const pinMissing = pinDoc.missing || [];

  if (shape.kind === 'empty') fail('投影文件是空的 —— 没有件可对照，不许当作通过');
  if (shape.kind === 'unknown') {
    fail('投影文件的形状认不出来（既不是件数组也不是清单对象）：' + LOCAL);
  }

  console.log('[drift] 对账对象：' + LOCAL + '（通道 ' + CHANNEL + '，形状 ' + shape.kind + '）');
  console.log('[drift] 声明源：scripts/component-sources.json（' + Object.keys(sources).length + ' 件）、'
    + '.github/native-capabilities.txt（' + caps.size + ' 条）、'
    + '.github/native-capabilities-pin.json（pin ' + Object.keys(pins).length + ' 条，missing ' + pinMissing.length + ' 条）');

  // 组件投影：件在钉值表里登记了没有、版本对不对、哈希是不是一份像样的哈希。
  for (const t of shape.tools) {
    const id = t.name;
    const src = sources[id];
    if (!src) {
      drift(id + '：钉值表 scripts/component-sources.json 里没有这一件 —— 投影里凭空多出来的');
      continue;
    }
    if (src.version && String(src.version) !== String(t.version)) {
      drift(id + '.version：投影=' + t.version + ' 钉值表=' + src.version);
    }
    if (!t.sha256 || !HEX64.test(String(t.sha256))) {
      drift(id + '.sha256：投影里不是一份 64 位十六进制的值（读到 ' + JSON.stringify(t.sha256) + '）');
    }
  }

  //  缺陷名（C-102）**一件缺失即零漂移** ——
  //    => 而那旧版逐字**只**从**投影**那一侧出发（=> 而那 :144 逐字 shape.tools、
  //      => 而那 :160 逐字 shape.components），
  //    => 而而那**声明侧**逐字**没有**反向核（=> 而那组件清单里逐字**少**一件时、=> 而那零漂移）。
  //    => 而那 node / python / sqlite / pkgconf 那四件逐字**在** components.json 的 sources 里
  //      （=> 而那四件逐字**没有** piece: true、=> 而那它们**不进**那取件循环）、
  //      => 而它们逐字是**控制面板清单该有的那几件**（=> 而那 bucket_for 逐字把 node/python
  //      归 rt 筐、=> 而 pkgconf/sqlite 归 tool 筐）=> 而它们**缺了**逐字**该被看见**。
  //    => 而那**判据**逐字：=> 而那**控制面板件清单**逐字从 cache-key.sh 的 bucket_for 出（=> 而那那是唯一真相）、
  //      => 而那投影里逐字**缺**哪一件、=> 而那那一件就逐字报缺。
  //    => 而而那「底座件」那一族（base / apkonly 筐）逐字**不进**这一轴
  //      （=> 而它们随 APK 走、=> 而那不在控制面板清单里、=> 而按 C-135 那不是同一问）。
  if (shape.kind === 'component') {
    const projected = new Set(shape.tools.map((t) => String(t.name)));
    //  缺陷名（C-102）**那一轴逐字把「已知缺件」与「件级缺失」混成一档，
    //    于是三处已知缺件被逐字判成漂移** ——
    //    => 而 01 裁的 S606-02-MISSING-PIECE-FORM-RULING.md 逐字裁的是
    //      「**投影侧 `work/declared.json` 逐字不留占位**（=> 而它答『取到了什么』）」，
    //    => 而那**投影里没有**那几件逐字是**对的**（=> 而那那几件逐字是「已知缺件」）。
    //    => 而而那那一裁逐字还给了另一半：「**声明侧那一份逐字要带缺件状态**」。
    //    => 而那**判红的正确形态**逐字是：「那一件逐字在控制面板件清单里、
    //      => 而它逐字既不在投影里、=> 而也**不在**那一趟的缺件表里」。
    //    => 而那缺件表逐字是 dist/.fetch-missing.tsv（=> 而那它逐字是那四列那一档：
    //      件名 / 那一档 / 那一档的原话 / 容器形态）。
    const missingTable = readMissingTable();
    for (const row of bucketComponents()) {
      const id = row.name;
      if (row.bucket === 'base' || row.bucket === 'apkonly') continue;
      if (!sources[id]) continue;
      if (projected.has(id)) continue;
      const miss = missingTable.get(id);
      if (miss) {
        //  => 而那**已知缺件**逐字**不判红**（=> 而它逐字在缺件表里、=> 而那缺件表就是那显式的一格），
        //    => 而而它逐字**报出来**（=> 而那才是「件不静默消失」那一格的兑现）。
        console.log('[drift] ' + id + '：控制面板件清单里有这一件（筐 ' + row.bucket + '），' +
          '=> 而它逐字**不在**那投影里 —— ' +
          '而而它逐字**在**那缺件表里（档位 ' + miss.kind + '、=> 而那一档的原话逐字：' + miss.detail + '）' +
          '=> 而而那投影侧逐字**不留占位**是**对的**（=> 而按 01 裁的 S606-02 逐字「它答『取到了什么』」）。');
        continue;
      }
      drift(id + '：控制面板件清单里有这一件（筐 ' + row.bucket + '），=> 而它逐字**不在**那投影里、' +
        '=> 而也**不在**那缺件表里 —— 这一件**真**缺失（=> 而那投影侧逐字只从投影出发、=> 而那零漂移；' +
        '=> 而而那这一件逐字**该被看见**）');
    }
  }

  // 底座件投影：id 必须在档位表里、版本必须与钉值表逐字相同、入口必须是声明过的那一个。
  for (const c of shape.components) {
    const id = c.id;
    if (!caps.has(id)) {
      drift(id + '：底座件投影里有这一件，但 .github/native-capabilities.txt 档位表里没有它');
    }
    const src = sources[id];
    if (!src) {
      drift(id + '：钉值表 scripts/component-sources.json 里没有这一件');
      continue;
    }
    if (src.version && String(src.version) !== String(c.version)) {
      drift(id + '.version：投影=' + c.version + ' 钉值表=' + src.version);
    }
    const declaredEntry = src.installName || (caps.get(id) ? caps.get(id).libName : '');
    if (declaredEntry && c.entry && String(c.entry) !== String(declaredEntry)) {
      drift(id + '.entry：投影=' + c.entry + ' 声明=' + declaredEntry);
    }
  }

  // 档位表里的每一件，在 pin 记录里都要有一条 pin。
  const pinsById = new Map();
  for (const fp of Object.keys(pins)) {
    const p = pins[fp];
    if (!p || !p.id) {
      drift('pin ' + fp.slice(0, 12) + '…：这一条 pin 没有 id 格');
      continue;
    }
    if (pinsById.has(p.id)) {
      drift(p.id + '：pin 记录里有两条 pin（键 ' + fp.slice(0, 12) + '… 与 ' + pinsById.get(p.id).fp.slice(0, 12) + '…）');
    }
    pinsById.set(p.id, { fp, p });
  }
  const missingById = new Map();
  for (const m of pinMissing) missingById.set(m.id, m);
  for (const id of caps.keys()) {
    if (pinsById.has(id)) continue;
    const m = missingById.get(id);
    if (m) {
      drift(id + '：档位表里有这一件，pin 记录里没有 —— pin 文件把它记成 missing（原因：' + m.reason + '）');
    } else {
      drift(id + '：档位表里有这一件，pin 记录里既没有 pin 也没有 missing —— 钉值断了');
    }
  }

  // 每一条 pin 自身：版本、上游源码哈希、档位表那一列、自身哈希。
  for (const [id, { fp, p }] of pinsById) {
    const src = sources[id] || {};
    const declared = p.declared || {};
    if (src.version && String(src.version) !== String(declared.version)) {
      drift(id + '：pin 记的版本（' + declared.version + '）与钉值表（' + src.version + '）不一致 —— pin 需要重新生成');
    }
    const pinSrc = String(declared.upstreamSourceSha256 || '');
    const tableSrc = String(src.sha256 || '');
    if (pinSrc !== tableSrc) {
      drift(id + '：pin 记的上游源码 sha256（' + (pinSrc || '空') + '）与钉值表（' + (tableSrc || '空') + '）不一致 —— pin 需要重新生成');
    }
    const capsRow = caps.get(id);
    if (capsRow && String(p.capsLibName) !== String(capsRow.libName)) {
      drift(id + '.capsLibName：pin 记的是 ' + p.capsLibName + '，档位表里是 ' + capsRow.libName);
    }
    if (!p.sha256 || !HEX64.test(String(p.sha256))) {
      drift(id + '：pin 的 sha256 不是一份 64 位十六进制的值（读到 ' + JSON.stringify(p.sha256) + '）');
    }
  }

  const after = sha256File(LOCAL);
  if (IMMUTABLE) {
    if (before !== after) {
      fail('被对账的文件在对账过程中被改写了（前后 sha256：' + before.slice(0, 12) + '… -> ' + after.slice(0, 12) + '…）');
    }
    console.log('[drift] immutable：被对账的文件未被改写（前后 sha256 相同：' + before + '）');
  }

  if (drifts.length) {
    for (const d of drifts) console.log('[drift] ' + d);
    console.log('[drift] ' + drifts.length + ' 条不一致');
    fail(drifts.length + ' 条不一致 —— 投影与仓内声明分家了');
  }
  console.log('[drift] 逐条一致：投影与钉值表、档位表、pin 记录没有分家');
}

main();
