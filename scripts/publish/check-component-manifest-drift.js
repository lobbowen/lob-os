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

  if (shape.kind === 'component') {
    const projected = new Set(shape.tools.map((t) => String(t.name)));
    //  缺陷名（C-102）**那一轴逐字把「已知缺件」与「件级缺失」混成一档，
    //    于是三处已知缺件被逐字判成漂移** ——
    //01 裁的 S606-02-MISSING-PIECE-FORM-RULING.md 逐字裁的是

    const missingTable = readMissingTable();
    for (const row of bucketComponents()) {
      const id = row.name;
      if (row.bucket === 'base' || row.bucket === 'apkonly') continue;
      if (!sources[id]) continue;
      if (projected.has(id)) continue;
      const miss = missingTable.get(id);
      if (miss) {
        // 那**已知缺件**逐字**不判红**（它逐字在缺件表里、缺件表就是那显式的一格），
        // 它逐字**报出来**（才是「件不静默消失」那一格的兑现）。
        console.log('[drift] ' + id + '：控制面板件清单里有这一件（筐 ' + row.bucket + '），' +
          '它逐字**不在**那投影里 —— ' +
          '而它逐字**在**那缺件表里（档位 ' + miss.kind + '、一档的原话逐字：' + miss.detail + '）' +
          '投影侧逐字**不留占位**是**对的**（按 01 裁的 S606-02 逐字「它答『取到了什么』」）。');
        continue;
      }
      drift(id + '：控制面板件清单里有这一件（筐 ' + row.bucket + '），它逐字**不在**那投影里、' +
        '也**不在**那缺件表里 —— 这一件**真**缺失（投影侧逐字只从投影出发、零漂移；' +
        '这一件逐字**该被看见**）');
    }
  }

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
