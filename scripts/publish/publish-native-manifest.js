#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

// ★ 上溯两层到仓根 —— 本文件在 scripts/publish/，只上溯一层会落在 scripts/ 下，
//   于是 CAPS/SOURCES/PUBKEY 全被算成 scripts/.github/… （实测：投影报
//   「缺少 …/scripts/.github/native-capabilities.txt」）。那是目录归并时的遗留。
const ROOT = path.resolve(__dirname, '../..');
const POS = process.argv.slice(2).filter((a) => !a.startsWith('--'));
const PROJECT_ONLY = process.argv.includes('--project');
const ZIP = POS[0] || '';
const OUT = POS[1] || 'release';
const KEY = POS[2] || 'keys/ota-private.pem';
const CHANNEL = POS[3] || 'canary';
const BASE = (process.env.NATIVE_BASE_URL || 'https://lobcdn.zll.ink').replace(/\/+$/, '');
const PUBKEY = process.env.NATIVE_PUBKEY
  || path.join(ROOT, 'container', 'app', 'src', 'main', 'assets', 'supply', 'component-public.pem');
// 事实源（两个，别再找已删的 NativeAssetRegistry.kt —— 那个文件已随
// 「内核不预置件清单」那轮清理删掉了）：
//   CAPS     —— .github/native-capabilities.txt：<档位> <libName> <id>
//   VERIFY   —— scripts/component-verify.json：每件的 version/entry/form
const VERIFY = path.join(ROOT, 'scripts', 'component-verify.json');
const SOURCES = path.join(ROOT, 'scripts', 'component-sources.json');
const CAPS = path.join(ROOT, '.github', 'native-capabilities.txt');
const PIN = path.join(ROOT, '.github', 'native-capabilities-pin.json');
const ABI = process.env.ABI || 'arm64-v8a';
const TTL_MS = 30 * 86400_000;

const problems = [];
const bad = (m) => problems.push(m);

function stripComments(src) {
  return src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');
}

function parseRegistry() {
  // libName 与 id 的对应来自 CAPS（它就是那张清单）；
  // version/entry 来自 VERIFY。两者都是仓内文件，不需要 Kotlin 注册表。
  if (!fs.existsSync(CAPS)) { bad('缺少 ' + CAPS + ' —— 底座件清单没了'); return []; }
  let vtab = {};
  try {
    vtab = (JSON.parse(fs.readFileSync(VERIFY, 'utf8')).criteria) || {};
  } catch (e) {
    bad('读不了 ' + VERIFY + '：' + e.message);
  }
  const out = [];
  for (const line of fs.readFileSync(CAPS, 'utf8').split('\n')) {
    const t = line.trim();
    if (!t || t.startsWith('#')) continue;
    const parts = t.split(/\s+/);
    if (parts.length < 3) continue;
    const [, libName, id] = parts;
    const v = vtab[id] || {};
    out.push({
      id,
      libName,
      // installName：落位后的裸名。VERIFY 没有这一格时退回 libName ——
      // 那是同一件事的两种叫法，不要因此报「缺字段」。
      installName: v.installName || libName,
      version: v.version || '',
    });
  }
  if (!out.length) bad('从 ' + CAPS + ' 一件也没解析出来 —— 清单格式变了？');
  return out;
}

function capsIndex() {
  const map = new Map();
  if (!fs.existsSync(CAPS)) { bad('缺少 ' + CAPS + ' —— 底座件档位表没了'); return map; }
  for (const line of fs.readFileSync(CAPS, 'utf8').split('\n')) {
    const t = line.trim();
    if (!t || t.startsWith('#')) continue;
    const [, lib, id] = t.split(/\s+/);
    if (lib && id) map.set(lib, id);
  }
  return map;
}

function pinEntry() {
  try {
    const j = JSON.parse(fs.readFileSync(PIN, 'utf8'));
    const pins = j.pins || {};
    const keys = Object.keys(pins);
    if (!keys.length) return null;
    const k = keys[keys.length - 1];
    return { fingerprint: k, ...pins[k] };
  } catch (e) {
    return null;
  }
}

function zipHas(zip, rel) {
  if (!zip || !fs.existsSync(zip)) return false;
  const r = require('node:child_process').spawnSync('unzip', ['-l', zip], { encoding: 'utf8' });
  if (r.status !== 0) return false;
  return r.stdout.split('\n').some((l) => l.trim().endsWith(rel));
}

function versionString() {
  try {
    const v = JSON.parse(fs.readFileSync(path.join(ROOT, 'version.json'), 'utf8'));
    const s = v.shell || {};
    return `${s.versionName || '0'}+${s.versionCode || 0}`;
  } catch (e) {
    return '0';
  }
}

function revisionForManifest() {
  const raw = process.env.LOBOS_COMPONENT_REVISION || '';
  if (!/^[1-9][0-9]*$/.test(raw)) {
    throw new Error('LOBOS_COMPONENT_REVISION 必须是正整数（来自发布 tag component-<channel>-<revision>），读到: ' + (raw || '空'));
  }
  return Number(raw);
}

const PIN_KEY = {
  bash: 'bash',
  busybox: 'busybox',
  zlib: 'zlib',
  openssl: 'openssl',
  crypto: 'openssl',
  curl: 'curl',
  ripgrep: 'ripgrep',
  jq: 'jq',
  libcxx: '@ndkVersion',
};

function versionOf(id) {
  let tab = {}, vtab = {};
  try {
    tab = JSON.parse(fs.readFileSync(SOURCES, 'utf8'));
  } catch (e) {
    bad('钉值表读不出（' + SOURCES + '）：' + e.message);
  }
  try {
    vtab = JSON.parse(fs.readFileSync(VERIFY, 'utf8')).criteria || {};
  } catch (e) {
    bad('判据表读不出（' + VERIFY + '）：' + e.message);
  }
  // 自写件（flock/posix/ptyprobe/ptysession）不在 PIN_KEY 里 ——
  // 那个映射只列「钉值表里查得到上游版本」的那些。它们是我们自己写的 C，
  // 版本从 1.0.0 起，记在 component-verify.json（gen-component-meta.js 同源）。
  // 查找顺序照 gen-component-meta.js 第 50 行：先 verify 再 sources。
  const vv = (vtab[id] || {}).version;
  if (vv && String(vv).trim()) return String(vv).trim();
  const key = PIN_KEY[id];
  if (!key) return null;
  if (key === '@ndkVersion') return (tab.ndkVersion || '').trim() || null;
  const v = ((tab.sources || {})[key] || {}).version;
  return v && String(v).trim() ? String(v).trim() : null;
}

function main() {
  const reg = parseRegistry();
  const byId = capsIndex();
  const pin = pinEntry();

  const versioned = [];
  const noVersion = [];
  for (const e of reg) {
    const v = versionOf(e.id);
    if (v) versioned.push({ e, version: v });
    else noVersion.push(e.id);
  }
  if (!versioned.length) {
    bad('钉值表里一件版本都取不到 —— 清单会是空的（空清单等于「都不可更新」且看不出来）。' +
      '检查 PIN_KEY 的映射与 ' + SOURCES);
  }
  for (const id of noVersion) {
    console.error('  [bundled-only] ' + id + ' 钉值表里没有它的版本 → 不进 OTA 清单，只随 APK 打包');
  }

  const components = [];
  for (const { e, version } of versioned) {
    const capsId = byId.get(e.libName);
    if (capsId && capsId !== e.id) {
      bad('注册表 id=' + e.id + ' 与档位表 id=' + capsId + ' 对不上（libName=' + e.libName + '）—— 两份清单会互相认错');
    }
    const c = {
      id: e.id,
      version,
      entry: e.installName || e.libName,
      libName: e.libName,
      source: 'apk',
    };
    if (pin && zipHas(ZIP, e.libName)) {
      const zipName = path.basename(ZIP);
      c.source = 'ota';
      c.url = BASE + '/' + CHANNEL + '/native/' + zipName;
      c.sha256 = pin.sha256;
      c.pin = pin.tag;
    }
    components.push(c);
  }

  if (PROJECT_ONLY) {
    process.stdout.write(JSON.stringify(components, null, 2) + '\n');
    for (const c of components) {
      console.error('  - ' + c.id + '@' + c.version + ' entry=' + c.entry + ' 来源=' + c.source);
    }
    if (problems.length) {
      for (const p of problems) console.error('::error title=底座件清单投影失败::' + p);
      process.exit(1);
    }
    return;
  }

  const man = {
    schema: 1,
    channel: CHANNEL,
    revision: revisionForManifest(),
    version: versionString(),
    sequence: Math.floor(Date.now() / 1000),
    expiresEpochMs: Date.now() + TTL_MS,
    fingerprint: pin ? pin.fingerprint : null,
    components,
  };
  const body = JSON.stringify(man, null, 2) + '\n';
  const key = fs.readFileSync(KEY, 'utf8');
  const sig = crypto.sign(null, Buffer.from(body, 'utf8'), key).toString('base64');
  const ok = crypto.verify(null, Buffer.from(body, 'utf8'), fs.readFileSync(PUBKEY, 'utf8'), Buffer.from(sig, 'base64'));
  if (!ok) {
    console.error('::error title=签名不配对::私钥与 APK 焊死的公钥不是一对 —— 这份清单设备验不过，拒绝发布。');
    process.exit(1);
  }
  fs.mkdirSync(OUT, { recursive: true });
  fs.writeFileSync(path.join(OUT, 'native-manifest.json'), body);
  fs.writeFileSync(path.join(OUT, 'native-manifest.json.sig'), sig + '\n');
  console.log('[native] channel=' + CHANNEL + ' revision=' + man.revision + ' components=' + components.length
    + '（可 OTA ' + components.filter((c) => c.source === 'ota').length + ' 件）');
  console.log('[native] 签名自检通过: ' + path.join(OUT, 'native-manifest.json'));
  if (problems.length) {
    for (const p of problems) console.error('::warning title=底座件清单有问题::' + p);
  }
}

try { main(); } catch (e) {
  console.error('::error title=底座件清单发布失败::' + (e && e.message));
  process.exit(1);
}