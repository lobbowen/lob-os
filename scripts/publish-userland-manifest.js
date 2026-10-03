#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const cp = require('node:child_process');
const crypto = require('node:crypto');

const ROOT = path.resolve(__dirname, '..');
const POS = process.argv.slice(2).filter((a) => !a.startsWith('--'));
const PROJECT_ONLY = process.argv.includes('--project');
const DIST = POS[0] || 'dist';
const OUT = POS[1] || 'release';
const KEY = POS[2] || 'keys/ota-private.pem';
const CHANNEL = POS[3] || 'canary';
const BASE = (process.env.USERLAND_BASE_URL || 'https://hubcdn.zll.ink').replace(/\/+$/, '');
const PUBKEY = path.join(ROOT, 'container', 'app', 'src', 'main', 'assets', 'ota-public.pem');
const VERIFY = path.join(__dirname, 'userland-verify.json');
const TTL_MS = 30 * 86400_000;

function entryOf(name) {
  return cp.execFileSync('bash', [path.join(ROOT, 'scripts', 'read-userland-entry.sh'), name], { encoding: 'utf8' }).trim();
}

function pieceText(zipPath, rel) {
  try {
    return cp.execFileSync('unzip', ['-p', zipPath, rel], {
      encoding: 'buffer', maxBuffer: 64 * 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe'],
    });
  } catch (e) {
    if (e.status === 11 || /filename not matched/.test(String(e.stderr || ''))) return null;
    throw e;
  }
}

function pieceNames(zipPath) {
  const r = cp.spawnSync('unzip', ['-l', zipPath], { encoding: 'utf8' });
  if (r.status !== 0) {
    throw new Error('unzip -l 读不了件 ' + zipPath + ': ' + String(r.stderr || '').slice(0, 200));
  }
  const out = [];
  for (const line of String(r.stdout || '').split('\n')) {
    const m = /^\s*\d+\s+\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}\s+(.+)$/.exec(line);
    if (m) out.push(m[1].trim());
  }
  if (!out.length) throw new Error('unzip -l 没解析出条目名（输出格式异常）: ' + zipPath);
  return out;
}

function aliasesOf(name, zipPath, declaredEntry) {
  const raw = pieceText(zipPath, 'package.json');
  if (raw === null) return [];
  if (!raw.length) {
    throw new Error(
      '件 ' + name + ' 的根 package.json 取到了 0 字节 —— ' +
      'unzip -p 对不存在的条目可能返回 0 且输出空（BusyBox 版 unzip 就是这样，' +
      'GNU unzip 返回 11）。先确认件里到底有没有 package.json：unzip -l ' + zipPath
    );
  }
  let bin;
  try {
    bin = JSON.parse(raw.toString('utf8')).bin;
  } catch (e) {
    throw new Error('件 ' + name + ' 的根 package.json 不是合法 JSON: ' + e.message);
  }
  if (bin === undefined) return [];
  if (typeof bin === 'string') {
    if (bin !== declaredEntry) {
      throw new Error('件 ' + name + ' 的 package.json bin 是字符串 ' + bin + '，与仓内声明的入口 ' + declaredEntry + ' 分叉');
    }
    return [];
  }
  if (bin[name] !== declaredEntry) {
    throw new Error('件 ' + name + ' 的 package.json bin 映射里 ' + name + ' → ' + String(bin[name]) +
      '，与仓内声明的入口 ' + declaredEntry + ' 分叉 —— 入口只能有一个真相（scripts/read-userland-entry.sh 那条纪律）');
  }
  const names = pieceNames(zipPath);
  const out = [];
  for (const alias of Object.keys(bin).sort()) {
    if (alias === name) continue;
    const rel = bin[alias];
    if (typeof rel !== 'string' || !rel.includes('/') || rel.startsWith('/') || rel.split('/').includes('..')) {
      throw new Error('件 ' + name + ' 的别名 ' + alias + ' 的件内入口不是合规相对路径: ' + String(rel));
    }
    if (!names.includes(rel)) {
      throw new Error('件 ' + name + ' 声明的别名 ' + alias + ' 指向件内不存在的 ' + rel);
    }
    out.push({ name: alias, entry: rel });
  }
  return out;
}

function assertNameUniqueness(tools) {
  const owner = new Map();
  const claim = (name, who) => {
    const prev = owner.get(name);
    if (prev !== undefined) {
      throw new Error('名字冲突: ' + name + ' 同时属于 ' + prev + ' 与 ' + who + ' —— 后落位会覆盖前一颗');
    }
    owner.set(name, who);
  };
  for (const t of tools) {
    claim(t.name, '件 ' + t.name + ' 的本名');
    for (const a of t.aliases || []) claim(a.name, '件 ' + t.name + ' 的别名 ' + a.name);
  }
}

function toolsFromDist() {
  if (!fs.existsSync(DIST)) throw new Error('dist 目录不存在: ' + DIST);
  const out = [];
  for (const f of fs.readdirSync(DIST)) {
    const m = /^userland-([a-z0-9-]+)-([0-9][^-]*)-([0-9a-f]{12})-android-arm64\.zip$/.exec(f);
    if (!m) continue;
    const name = m[1];
    const ver = m[2];
    const claimed = m[3];
    const zipPath = path.join(DIST, f);
    const buf = fs.readFileSync(zipPath);
    if (buf.length === 0) throw new Error('0 字节产物: ' + f);
    const real = crypto.createHash('sha256').update(buf).digest('hex').slice(0, 12);
    if (real !== claimed) throw new Error('文件名里的内容哈希与实际不符: ' + f + '（名 ' + claimed + ' vs 实 ' + real + '）');
    const entry = entryOf(name);
    out.push({
      name, provider: 'zip', version: ver,
      url: BASE + '/userland/' + f,
      sha256: crypto.createHash('sha256').update(buf).digest('hex'),
      entry,
      aliases: aliasesOf(name, zipPath, entry),
    });
  }
  return out;
}

function versionString() {
  const d = new Date().toISOString().slice(0, 10).replace(/-/g, '.');
  const run = process.env.GITHUB_RUN_NUMBER || '0';
  return d + '.' + run;
}

function revisionForManifest() {
  const raw = process.env.LOBOS_USERLAND_REVISION || '';
  if (!/^[1-9][0-9]*$/.test(raw)) {
    throw new Error('LOBOS_USERLAND_REVISION 必须是正整数（它来自发布 tag `userland-<channel>-<revision>`），读到: ' + (raw || '空'));
  }
  return Number(raw);
}

function criteria() {
  const j = JSON.parse(fs.readFileSync(VERIFY, 'utf8'));
  return j.criteria || {};
}

function main() {
  const crit = criteria();
  const tools = toolsFromDist().sort((a, b) => a.name.localeCompare(b.name));
  if (!tools.length) throw new Error('清单为空：dist 下没有一颗按命名契约产出的件');
  for (const t of tools) {
    const v = crit[t.name];
    if (!v || typeof v.node !== 'string' || v.node.length < 20) {
      throw new Error('件没有能力判据，不许发布: ' + t.name + '（在 scripts/userland-verify.json 里补）');
    }
    t.verify = { criterion: v.criterion, node: v.node };
    t.kind = t.kind || 'component';
    t.layout = t.layout || 'toolset';
    t.abi = t.abi || 'aarch64';
    if (!t.name || !t.version || !t.sha256) throw new Error('件缺字段(name/version/sha256): ' + JSON.stringify(t).slice(0, 120));
  }
  assertNameUniqueness(tools);
  if (PROJECT_ONLY) {
    process.stdout.write(JSON.stringify(tools, null, 2) + '\n');
    return;
  }
  const man = {
    schema: 1,
    channel: CHANNEL,
    revision: revisionForManifest(),
    version: versionString(),
    sequence: Math.floor(Date.now() / 1000),
    expiresEpochMs: Date.now() + TTL_MS,
    tools,
  };
  const body = JSON.stringify(man, null, 2) + '\n';
  const key = fs.readFileSync(KEY, 'utf8');
  const sig = crypto.sign(null, Buffer.from(body, 'utf8'), key).toString('base64');
  const ok = crypto.verify(null, Buffer.from(body, 'utf8'), fs.readFileSync(PUBKEY, 'utf8'), Buffer.from(sig, 'base64'));
  if (!ok) { console.error('::error title=签名不配对::私钥与 APK 焊死的公钥不是一对 —— 这份清单设备验不过，拒绝发布。'); process.exit(1); }
  fs.mkdirSync(OUT, { recursive: true });
  fs.writeFileSync(path.join(OUT, 'userland-manifest.json'), body);
  fs.writeFileSync(path.join(OUT, 'userland-manifest.json.sig'), sig + '\n');
  console.log('[userland] channel=' + CHANNEL + ' revision=' + man.revision + ' version=' + man.version + ' sequence=' + man.sequence + ' tools=' + tools.length);
  for (const t of tools) {
    const als = t.aliases.length ? ' 别名 ' + t.aliases.map((a) => a.name + '→' + a.entry).join(',') : '';
    console.log('  - ' + t.name + '@' + t.version + ' ' + t.provider + ' ' + t.sha256.slice(0, 12) + '… 判据 ' + t.verify.node.length + ' 字 入口 ' + t.entry + als);
  }
  console.log('[userland] 签名自检通过（与 APK 公钥配对）: ' + path.join(OUT, 'userland-manifest.json'));
}

module.exports = { aliasesOf, assertNameUniqueness, pieceText, pieceNames };

if (require.main === module) {
  try { main(); } catch (e) { console.error('::error title=清单发布失败::' + (e && e.message)); process.exit(1); }
}
