#!/usr/bin/env node
'use strict';

/**
 * 底座件（原生件）清单 —— 发布侧。
 *
 * 与商店清单（publish-userland-manifest.js）**并列但分流**：
 *   · 商店件       落 programs/<id>/<版本>/，由 PackageInstaller 装
 *   · 底座件(原生件) 落 $PREFIX/lib/toolchain/<id>/<版本>/，由 NativeAssetUpdater 装
 * 两者用**同一把 Ed25519 钥匙**（assets/supply/userland-public.pem）——
 * 两套信任根意味着两处要轮换、两处可能只更新一处，那是最坏形态。
 *
 * 清单里每一条对应 NativeAssetRegistry 里的一个件。三种来源：
 *   1. APK 原件（jniLibs）—— 不发 URL，设备已在用；列出来是为了让设备知道
 *      「本地这份是多少版本，比对时不必再猜」
 *   2. 固化包（native-cap-<sha256>-arm64-v8a.zip）—— 有 URL 与 sha256，可 OTA 更新
 *   3. 两份都有 → 取版本较新的那份
 *
 * 用法：
 *   node scripts/publish-native-manifest.js --project            （投影，不签名）
 *   node scripts/publish-native-manifest.js <zip> <out> <key> <channel>
 * 环境：
 *   NATIVE_BASE_URL  下载基址（默认与商店同一个对象存储）
 *   NATIVE_PUBKEY    公钥路径（默认 assets/supply/ota-public.pem）
 *   LOBOS_USERLAND_REVISION  清单 revision（发布轮必填，正整数）
 */

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

/** 去掉 Kotlin 注释再解析，避免注释里的字符串干扰。 */
function stripComments(src) {
  return src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');
}

/**
 * 从 NativeAssetRegistry.kt 读出：id / libName / installName / version。
 *
 * 不猜字段名、不猜条目边界：注册表是唯一事实源，本函数只是把它读出来。
 * 读不到的条目直接判红 —— 清单少一条就等于那一件永远不能 OTA 更新，
 * 而那种缺失是静默的（设备照样跑，只是永远停在 APK 那份）。
 */
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
    // 从 '(' 起做括号配平，取出这一条的实参文本
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

/** 档位表里的 libName → id（用于把固化包里的字节对上注册表的条目）。 */
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

/** 固化包里有哪些字节、sha256 多少。 */
function pinEntry() {
  try {
    const j = JSON.parse(fs.readFileSync(PIN, 'utf8'));
    const pins = j.pins || {};
    const keys = Object.keys(pins);
    if (!keys.length) return null;
    // 多条固化记录时取最后一条（本仓的做法是同一指纹一条）
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

  // 只有「有版本号」的件才进清单 —— version 空串的语义是「随 APK、不单独更新」，
  // 让它出现在清单里会让人以为它能被 OTA。
  const withVersion = reg.filter((e) => e.version && e.version.trim());
  if (!withVersion.length) {
    bad('注册表里没有一件声明了 version —— 底座件清单会是空的（空清单等于「都不可更新」且看不出来）');
  }

  const components = [];
  for (const e of withVersion) {
    // 清单里的 id 必须是档位表里的 id（两者要对得上，否则设备认不出）
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
    // 固化包里有这份字节 → 可 OTA 更新
    if (pin && zipHas(ZIP, e.libName)) {
      const zipName = path.basename(ZIP);
      c.source = 'ota';
      c.url = BASE + '/' + CHANNEL + '/native/' + zipName;
      c.sha256 = pin.sha256;     // 固化包整体校验值（逐字节验包，见 README）
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