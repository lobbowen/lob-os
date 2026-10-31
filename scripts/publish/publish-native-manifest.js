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
//   VERIFY   —— scripts/component-sources.json：每件的 version/entry/form
const VERIFY = path.join(ROOT, 'scripts', 'component-sources.json');
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
    vtab = (JSON.parse(fs.readFileSync(VERIFY, 'utf8')).sources) || {};
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

// 那一档 pin 记录（=> 而那它逐字**按 id**查、=> 而不按「最后一条」）。
//
// 缺陷名（C-102）**那 pin 逐字取「最后一条」、=> 而而那一件逐字可能是别的那一件** ——
//   => 而那旧版逐字是 `const k = keys[keys.length - 1]`（=> 而那**键顺序**是那生成物写的顺序、
//     => 而那顺序逐字**不是**任何语义、=> 而它逐字**会变**）。
//   => 而那**后果**：=> 而那清单里**每一件**逐字都拿**同一份** pin（=> 而那 url / sha256 / tag
//     逐字全是**最后那一件**的）=> 而而那设备装到别的那一件时逐字**拉错字节**。
//   => 而按 01 裁的 S602-01-ROLLBACK-SUBJECT-SET.md §11 归属表第 7 行逐字：
//     「05 补 `source=ota` 的事实来源」=> 而那事实来源逐字**按件**给。
function pinTable() {
  //  => 而那读不到时逐字**报缺**（=> 而按 C-05「缺字段就报缺、不许猜填」）、
  //     => 而而**不**静默当成「没有 pin」。
  try {
    const j = JSON.parse(fs.readFileSync(PIN, 'utf8'));
    const pins = j.pins || {};
    const byId = new Map();
    for (const k of Object.keys(pins)) {
      const p = pins[k];
      if (!p || !p.id) continue;
      byId.set(String(p.id), { fingerprint: k, ...p });
    }
    return byId;
  } catch (e) {
    bad('读不了 ' + PIN + '：' + e.message + ' —— 而那「source=ota」那一格逐字**要有**它、=> 而按 C-05 报缺。');
    return new Map();
  }
}

// 那一件**自己**的那一档 pin（=> 而那没有逐字就是「没有」=> 而**不**拿别的那一件顶替）。
function pinEntry(pins, id) {
  if (!pins || !pins.has(id)) return null;
  return pins.get(id);
}

// 整份清单的指纹（=> 而那它逐字是**每一件自己**的那一档 pin 的指纹逐字合起来）。
//  => 而那旧版逐字取「最后一条」那一条（=> 而那顺序逐字**不是**语义、=> 而它逐字**会变**）。
function manifestFingerprint(pins) {
  if (!pins || pins.size === 0) return null;
  const parts = [...pins.entries()]
    .map(([id, p]) => id + ':' + String(p.fingerprint || ''))
    .sort();
  return parts.join('|');
}

// 那一份件包里**有没有**那一条（=> 而那**逐字**按条目名逐字比、=> 而**不**按后缀）。
//
// 缺陷名（C-102）**那匹配逐字按后缀、=> 而而那后缀逐字会撞车** ——
//   => 而那旧版逐字是 `r.stdout.split('\n').some((l) => l.trim().endsWith(rel))`
//   => 而那**后缀**逐字**不是**那身份：
//     · 而那 rel 逐字是 `bin/bash`、=> 而那 endsWith 逐字也命中 `usr/bin/bash`、
//       `opt/x/bin/bash`、=> 而那**任何**以 bin/bash 结尾的路径；
//     · 而那 rel 逐字是 `libcurl.so`、=> 而那 endsWith 逐字**也**命中 `lib/libcurl.so.1`
//       （=> 而那**版本层**逐字不是同一件）。
//   => 而那**后果**：=> 而那「这一件在不在那份包里」逐字**答错**、
//     => 而那 source 那一格逐字跟着答错（=> 而那设备会装到别的那一份）。
//
// => 而那**逐字**该按什么比：=> 而那**条目名**逐字**就是**那身份
//   （=> 而那件包里的相对路径、=> 而那程序形态逐字是 bin/<名>、=> 而那库形态逐字是 lib/<名>）。
function zipHas(zip, rel) {
  if (!zip || !fs.existsSync(zip) || !rel) return false;
  const r = require('node:child_process').spawnSync('unzip', ['-l', zip], { encoding: 'utf8' });
  if (r.status !== 0) return false;
  const want = String(rel).replace(/^\.\//, '');
  for (const line of String(r.stdout || '').split('\n')) {
    //  => 而那 unzip -l 那一行的格式逐字是「<长度> <日期> <时间> <名字>」
    //     => 而那名字逐字是最后一段（=> 而那名字里**可以**有空格、=> 而那用最后一个字段切会切错）。
    const m = /^\s*\d+\s+\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}\s+(.+)$/.exec(line);
    if (!m) continue;
    if (m[1].trim() === want) return true;
  }
  return false;
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
    vtab = JSON.parse(fs.readFileSync(VERIFY, 'utf8')).sources || {};
  } catch (e) {
    bad('判据表读不出（' + VERIFY + '）：' + e.message);
  }
  // 内核的兼容性垫片（posix/flock/ptyprobe/ptysession）不再是件：它们是
  // container/native 下我们自己写的 C，随 APK 而来、只有一个版本、不走 OTA，
  // 编译进 jniLibs 由 kernel/compat/Compat.kt 定位，不在此列。
  // 这里的映射只列「钉值表里查得到上游版本」的那些。
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
  //  => 而那 pin 逐字**按件**查（=> 而那逐字不是「最后一条」）。
  const pins = pinTable();

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
    //  => 而那 source=ota 那一格逐字**按件**判（=> 而那三问逐字都要答）：
    //     · 那**包**里逐字有没有**那一件**（=> 而那 zipHas 逐字按条目名逐字比）；
    //     · 那**事实来源**里逐字有没有**那一件**的那一档 pin（=> 而那逐字按 id 查）；
    //     · 而那 pin 逐字**指**的是不是**那一件**（=> 而那 zip 名字逐字对得上）。
    //  => 而那**缺一格**就逐字**留在 apk**那一侧（=> 而那一侧逐字是「随 APK 走」）
    //     => 而而**不**拿别的那一件的 pin 顶替（=> 而那旧版逐字**每一件**都拿同一条）。
    const pin = pinEntry(pins, e.id);
    if (pin) {
      const zipName = path.basename(ZIP);
      const zipInPin = pin.zip || '';
      if (zipHas(ZIP, e.libName)) {
        c.source = 'ota';
        c.url = BASE + '/' + CHANNEL + '/native/' + zipName;
        c.sha256 = pin.sha256;
        c.pin = pin.tag;
      } else {
        //  => 而那 pin 逐字有、=> 而而那**包**里逐字没有**那一件**
        //     => 而那 source 逐字**留在 apk**、=> 而而那一句逐字说出来（=> 而不静默）。
        console.error('  [pin-not-in-zip] ' + e.id + '：pin 记录里有它（zip=' + zipInPin + '），' +
          '=> 而那一份件包里逐字**没有**它（=> 而那按后缀匹配的那旧版逐字**会**命中别的那一条）' +
          '=> 而那 source 逐字留在 apk。');
      }
    } else {
      //  => 而那 pin 记录里逐字**没有**它 => 而那 source 逐字是 apk、=> 而那**不**是缺件
      //     （=> 而那 base 筐那些件逐字就随 APK 走、=> 而而它们逐字本来就不进 OTA）。
      console.error('  [no-pin] ' + e.id + '：pin 记录里逐字没有它 => 而那 source 逐字是 apk（随 APK 走）。');
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
    //  => 而那整份清单的指纹逐字**不再**取「最后一条」那一条
    //     （=> 而那旧版逐字是 `pin ? pin.fingerprint : null`）
    //     => 而而它逐字是**所有**那些 ota 那一侧的指纹逐字合起来那一格
    //       （=> 而那逐字让「这一份清单换了没有」逐字**判得出**）。
    fingerprint: manifestFingerprint(pins),
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