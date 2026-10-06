#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const ROOT = path.resolve(__dirname, '..');
const POS = process.argv.slice(2).filter((a) => !a.startsWith('--'));
const PROJECT_ONLY = process.argv.includes('--project');
const ZIP = POS[0] || '';
const OUT = POS[1] || 'release';
const KEY = POS[2] || 'keys/ota-private.pem';
const CHANNEL = POS[3] || 'canary';
const BASE = (process.env.NATIVE_BASE_URL || 'https://lobcdn.zll.ink').replace(/\/+$/, '');
const PUBKEY = process.env.NATIVE_PUBKEY
  || path.join(ROOT, 'container', 'app', 'src', 'main', 'assets', 'supply', 'userland-public.pem');
const REGISTRY = path.join(ROOT, 'container', 'app', 'src', 'main', 'java', 'lobos', 'native', 'NativeAssetRegistry.kt');
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
  if (!fs.existsSync(REGISTRY)) {
    bad('找不到注册表 ' + REGISTRY + ' —— 底座件清单的唯一事实源没了');
    return [];
  }
  const body = stripComments(fs.readFileSync(REGISTRY, 'utf8'));
  const out = [];
  const re = /NativeExecutable\(/g;
  let m;
  while ((m = re.exec(body)) !== null) {
    let i = re.lastIndex - 1, depth = 0, end = -1;
    for (; i < body.length; i++) {
      if (body[i] === '(') depth++;
      else if (body[i] === ')') { depth--; if (depth === 0) { end = i; break; } }
    }
    if (end < 0) continue;
    const a = body.slice(re.lastIndex, end);
    const str = (f) => { const x = new RegExp('\\b' + f + '\\s*=\\s*"([^"]*)"').exec(a); return x ? x[1] : ''; };
    const id = str('id');
    if (!id) { re.lastIndex = end; continue; }
    out.push({
      id,
      libName: str('libName'),
      installName: str('installName'),
      version: str('version'),
    });
    re.lastIndex = end;
  }
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
  const raw = process.env.LOBOS_USERLAND_REVISION || '';
  if (!/^[1-9][0-9]*$/.test(raw)) {
    throw new Error('LOBOS_USERLAND_REVISION 必须是正整数（来自发布 tag userland-<channel>-<revision>），读到: ' + (raw || '空'));
  }
  return Number(raw);
}

function main() {
  const reg = parseRegistry();
  const byId = capsIndex();
  const pin = pinEntry();

  const withVersion = reg.filter((e) => e.version && e.version.trim());
  if (!withVersion.length) {
    bad('注册表里没有一件声明了 version —— 底座件清单会是空的（空清单等于「都不可更新」且看不出来）');
  }

  const components = [];
  for (const e of withVersion) {
    const capsId = byId.get(e.libName);
    if (capsId && capsId !== e.id) {
      bad('注册表 id=' + e.id + ' 与档位表 id=' + capsId + ' 对不上（libName=' + e.libName + '）—— 两份清单会互相认错');
    }
    const c = {
      id: e.id,
      version: e.version,
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