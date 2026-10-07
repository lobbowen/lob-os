#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');

const DIST = path.resolve(process.argv[2] || 'dist');
const PORT = Number(process.argv[3] || 8099);
const CHANNEL = (() => {
  const i = process.argv.indexOf('--channel');
  return i > 0 ? process.argv[i + 1] : 'canary';
})();

if (!fs.existsSync(DIST)) {
  console.error('[serve] dist 不存在: ' + DIST);
  process.exit(2);
}

const zips = fs.readdirSync(DIST).filter((f) => /^component-.*\.zip$/.test(f));
if (!zips.length) {
  console.error('[serve] ' + DIST + ' 下没有 component-*.zip');
  process.exit(2);
}

const pieces = [];
for (const f of zips) {
  const m = /^component-([a-z0-9-]+)-([0-9][^-]*)-([0-9a-f]{12})-android-arm64\.zip$/.exec(f);
  if (!m) { console.error('[serve] 文件名不符合内容寻址契约: ' + f); process.exit(2); }
  pieces.push({ file: f, name: m[1], version: m[2], claimed: m[3] });
}
pieces.sort((a, b) => a.name.localeCompare(b.name));

function entryOf(name) {
  const v = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'scripts', 'component-verify.json'), 'utf8'));
  const e = (v.criteria || {})[name];
  if (!e || !e.entry) {
    console.error('[serve] 件 ' + name + ' 在 component-verify.json 里没有 entry 声明');
    process.exit(2);
  }
  return e;
}

function aliasesOf(name, zipPath, entry) {
  let raw = null;
  try {
    raw = require('node:child_process').execFileSync('unzip', ['-p', zipPath, 'package.json'], { maxBuffer: 64 * 1024 * 1024 });
  } catch (e) {
    if (e.status === 11 || /filename not matched/.test(String(e.stderr || ''))) return [];
    throw e;
  }
  let bin;
  try { bin = JSON.parse(raw.toString('utf8')).bin; } catch (e) { return []; }
  if (bin === undefined) return [];
  if (typeof bin === 'string') return [];
  const out = [];
  for (const alias of Object.keys(bin).sort()) {
    if (alias === name) continue;
    const rel = bin[alias];
    if (typeof rel === 'string' && rel.includes('/') && !rel.startsWith('/') && !rel.split('/').includes('..')) {
      out.push({ name: alias, entry: rel });
    }
  }
  return out;
}

const crypto = require('node:crypto');
const BASE = 'http://127.0.0.1:' + PORT;
const tools = pieces.map((p) => {
  const zipPath = path.join(DIST, p.file);
  const buf = fs.readFileSync(zipPath);
  const real = crypto.createHash('sha256').update(buf).digest('hex').slice(0, 12);
  if (real !== p.claimed) {
    console.error('[serve] ' + p.file + ' 文件名里的哈希与实际不符（名 ' + p.claimed + ' vs 实 ' + real + '）');
    process.exit(2);
  }
  const c = entryOf(p.name);
  return {
    name: p.name,
    provider: 'zip',
    version: p.version,
    url: BASE + '/component/' + p.file,
    sha256: crypto.createHash('sha256').update(buf).digest('hex'),
    entry: c.entry,
    aliases: aliasesOf(p.name, zipPath, c.entry),
    kind: 'component',
    layout: (c.aliases && c.aliases.length) ? 'toolset' : 'single',
    abi: 'aarch64',
  };
});

const PRESIGNED = process.argv.includes('--presigned')
  ? process.argv[process.argv.indexOf('--presigned') + 1]
  : null;

const MANIFEST_PATH = '/component-' + CHANNEL + '/component-manifest.json';
const routes = {};
if (PRESIGNED) {
  const man = fs.readFileSync(PRESIGNED, 'utf8');
  const sig = fs.readFileSync(PRESIGNED.replace(/\.json$/, '.json.sig'), 'utf8');
  const parsed = JSON.parse(man);
  console.log('[serve] 用已签好的清单: ' + PRESIGNED);
  console.log('[serve]   revision=' + parsed.revision + ' tools=' + (parsed.tools || []).length
    + ' baseUrl=' + (((parsed.tools || [])[0] || {}).url || '').replace(/\/component\/.*/, ''));
  routes[MANIFEST_PATH] = () => ({ type: 'application/json; charset=utf-8', body: Buffer.from(man, 'utf8') });
  routes[MANIFEST_PATH + '.sig'] = () => ({ type: 'text/plain; charset=utf-8', body: Buffer.from(sig, 'utf8') });
} else {
  const manifest = {
    schema: 1,
    channel: CHANNEL,
    revision: Number(process.env.LOBOS_COMPONENT_REVISION || '1'),
    version: new Date().toISOString().slice(0, 10).replace(/-/g, '.') + '.local',
    sequence: Math.floor(Date.now() / 1000),
    expiresEpochMs: Date.now() + 86400000,
    tools,
  };
  routes[MANIFEST_PATH] = () => ({
    type: 'application/json; charset=utf-8',
    body: Buffer.from(JSON.stringify(manifest, null, 2), 'utf8'),
  });
  routes[MANIFEST_PATH + '.sig'] = () => {
    throw new Error('这个服务不签清单（没有私钥）—— 设备侧会因验签不过拒装。用 --presigned <已签清单路径>');
  };
}

const server = http.createServer((req, res) => {
  const u = decodeURIComponent((req.url || '').split('?')[0]);
  if (routes[u]) {
    const r = routes[u]();
    res.writeHead(200, { 'Content-Type': r.type, 'Cache-Control': 'no-store' });
    res.end(r.body);
    return;
  }
  const m = /^\/component\/(component-.+\.zip)$/.exec(u);
  if (m && pieces.some((p) => p.file === m[1])) {
    const buf = fs.readFileSync(path.join(DIST, m[1]));
    res.writeHead(200, { 'Content-Type': 'application/zip', 'Content-Length': buf.length, 'Cache-Control': 'no-store' });
    res.end(buf);
    return;
  }
  res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
  res.end('not found: ' + u + '\navailable:\n  ' + Object.keys(routes).join('\n  ') + '\n  /component/<件名>\n');
});

server.listen(PORT, '127.0.0.1', () => {
  console.log('[serve] 监听 ' + BASE);
  console.log('[serve] 清单路由: ' + BASE + '/component-' + CHANNEL + '/component-manifest.json');
  console.log('[serve] 件 ' + tools.length + ' 颗: ' + tools.map((t) => t.name + '@' + t.version).join(', '));
  for (const t of tools) {
    console.log('[serve]   ' + t.name + ' → ' + t.entry + '  sha ' + t.sha256.slice(0, 12) + (t.aliases.length ? '  别名 ' + t.aliases.map((a) => a.name).join('+') : ''));
  }
  console.log('[serve] 注意：本模拟器不签清单（设备侧会因验签不过拒装）。它验的是');
  console.log('[serve]       「除验签外的每一环」：清单形状 / sha256 / 解包 / 落位 / 建链 / 复验。');
});
