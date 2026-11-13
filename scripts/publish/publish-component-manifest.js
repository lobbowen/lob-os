#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const cp = require('node:child_process');
const crypto = require('node:crypto');








const ROOT = path.resolve(__dirname, '../..');
const POS = process.argv.slice(2).filter((a) => !a.startsWith('--'));
const PROJECT_ONLY = process.argv.includes('--project');


const DECLARED_ONLY = process.argv.includes('--declared-only');
const DIST = POS[0] || 'dist';
const OUT = POS[1] || 'release';
const KEY = POS[2] || 'keys/ota-private.pem';
const CHANNEL = POS[3] || 'canary';
const BASE = (process.env.COMPONENT_BASE_URL || 'https://lobcdn.zll.ink').replace(/\/+$/, '');
const PUBKEY = process.env.COMPONENT_PUBKEY
  || path.join(ROOT, 'container', 'app', 'src', 'main', 'assets', 'supply/component-public.pem');
const VERIFY = path.join(ROOT, 'scripts', 'component-sources.json');
const TTL_MS = 30 * 86400_000;

function entryOf(name) {
  return cp.execFileSync('bash', [path.join(ROOT, 'scripts', 'registry', 'read-component-entry.sh'), name], { encoding: 'utf8' }).trim();
}

function containerFormOf(file) {
  const FMT_SH = path.join(ROOT, 'scripts', 'toolchain', 'fmt.sh');
  if (!fs.existsSync(FMT_SH)) {
    throw new Error('判别源不在: ' + FMT_SH + ' —— 容器判别源必须唯一');
  }
  const r = cp.spawnSync('bash', ['-c', '. "$1"; fmt_of "$2"', 'bash', FMT_SH, file], { encoding: 'utf8' });
  if (r.status !== 0) {
    throw new Error('判别源读不了 ' + file + ': ' + String(r.stderr || '').slice(0, 200));
  }
  return String(r.stdout || '').trim();
}

function pieceIdOf(zipPath) {
  try {
    const raw = cp.execFileSync('unzip', ['-p', zipPath, 'component-meta.json'], {
      encoding: 'utf8', maxBuffer: 4 * 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe'],
    });
    if (!raw) return '';
    return String(JSON.parse(raw).id || '');
  } catch (e) {
    return '';
  }
}

function innerZipCandidates(file) {
  const work = fs.mkdtempSync(path.join(require('node:os').tmpdir(), 'lob-inner-'));
  try {
    const form = containerFormOf(file);
    if (form === 'gzip') {
      cp.execFileSync('tar', ['xzf', file, '-C', work], { stdio: ['ignore', 'pipe', 'pipe'] });
    } else if (form === 'zip' || form === 'zip64') {
      cp.execFileSync('unzip', ['-q', '-o', file, '-d', work], { stdio: ['ignore', 'pipe', 'pipe'] });
    } else {
      return [];
    }
    const out = [];
    const walk = (dir, depth) => {
      if (depth > 2) return;
      let kids = [];
      try { kids = fs.readdirSync(dir, { withFileTypes: true }); } catch (e) { return; }
      for (const k of kids) {
        const p = path.join(dir, k.name);
        if (k.isDirectory()) walk(p, depth + 1);
        else if (k.isFile() && /\.zip$/.test(k.name)) out.push(p);
      }
    };
    walk(work, 0);
    
    const zips = [];
    for (const p of out) {
      const fd = fs.openSync(p, 'r');
      try {
        const b = Buffer.alloc(4);
        const n = fs.readSync(fd, b, 0, 4, 0);
        if (n >= 4 && b[0] === 0x50 && b[1] === 0x4b) zips.push(p);
      } finally {
        fs.closeSync(fd);
      }
    }

    const picked = [];
    for (const p of zips) {
      let meta = null;
      try {
        meta = cp.execFileSync('unzip', ['-p', p, 'component-meta.json'], {
          encoding: 'utf8', maxBuffer: 4 * 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe'],
        });
      } catch (e) {
        meta = null;
      }
      if (!meta) continue;
      let id = '';
      try { id = String(JSON.parse(meta).id || ''); } catch (e) { id = ''; }
      if (!id) continue;
      picked.push({ path: p, id });
    }
    return picked;
  } catch (e) {
    return [];
  } finally {
    try { fs.rmSync(work, { recursive: true, force: true }); } catch (e) {  }
  }
}

function innerZipOf(file, wantPiece) {
  

  const form = containerFormOf(file);
  const outerId = (form === 'gzip')
    ? (() => {
        const work = fs.mkdtempSync(path.join(require('node:os').tmpdir(), 'lob-outer-'));
        try {
          cp.execFileSync('tar', ['xzf', file, '-C', work], { stdio: ['ignore', 'pipe', 'pipe'] });
          const p = path.join(work, 'component-meta.json');
          if (!fs.existsSync(p)) return '';
          return String(JSON.parse(fs.readFileSync(p, 'utf8')).id || '');
        } catch (e) {
          return '';
        } finally {
          try { fs.rmSync(work, { recursive: true, force: true }); } catch (e) {  }
        }
      })()
    : pieceIdOf(file);
  if (outerId) {
    if (outerId === wantPiece) return file;   
    throw new Error(
      '件 ' + file + ' 的**外层自己**就是那一颗件、它的 id 逐字是 ' + outerId +
      '（要的是 ' + wantPiece + '）—— 判红'
    );
  }
  const picked = innerZipCandidates(file);
  if (!picked.length) {
    throw new Error(
      '件 ' + file + ' 的外层解开之后，里面逐字**没有**一个含 component-meta.json 的件包' +
      '（按内容挑逐字挑不出、按 C-05 不猜、不取第一个）。'
    );
  }
  const hit = wantPiece ? picked.filter((c) => c.id === wantPiece) : picked;
  if (hit.length === 0) {
    throw new Error(
      '件 ' + file + ' 的外层解开之后，里面逐字**没有** id=' + wantPiece + ' 的那一个件包' +
      '（候选逐字是 ' + picked.map((c) => c.id).join(', ') + '）。'
    );
  }
  if (hit.length > 1) {
    throw new Error(
      '件 ' + file + ' 的外层解开之后，里面逐字有 **' + hit.length + '** 个 id=' + wantPiece +
      ' 的件包（01 裁的规则逐字「多过一个候选都判红、不取第一个」）。'
    );
  }
  return hit[0].path;
}

function pieceText(zipPath, rel) {
  const form = containerFormOf(zipPath);
  if (form !== 'zip' && form !== 'zip64') {
    throw new Error(
      '件 ' + zipPath + ' 的容器形态逐字是 ' + form + ' —— 而这一格逐字要读件内那一个 package.json，' +
      '形态逐字不对（不是 zip 容器、解不开）。'
    );
  }
  if (!pieceNames(zipPath).includes(rel)) return null;
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

function recipeFingerprintOf(pieceId) {
  try {
    const out = cp.execFileSync('bash', [
      path.join(ROOT, 'scripts', 'toolchain', 'cache-key.sh'), 'key', pieceId
    ], { encoding: 'utf8', maxBuffer: 4 * 1024 * 1024 });
    const m = /src([0-9a-f]{12})/.exec(out);
    return m ? m[1] : '';
  } catch (e) {
    return '';
  }
}

function aliasesOf(name, zipPath, declaredEntry) {
  const raw = pieceText(zipPath, 'package.json');
  if (raw === null) return [];
  if (!raw.length) {
    throw new Error(
      '件 ' + name + ' 里有 package.json 但取出来 0 字节 —— 件坏了。' +
      '先看：unzip -l ' + zipPath
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

  const entryBase = String(declaredEntry).split('/').pop();
  const entryKeys = Object.keys(bin).filter((k) => bin[k] === declaredEntry);
  if (!entryKeys.length) {
    throw new Error('件 ' + name + ' 的 package.json bin 映射里没有任何一项逐字指向仓内声明的入口 ' + declaredEntry +
      '（这一件声明过的键: ' + Object.keys(bin).sort().join(', ') + '）—— 入口只能有一个真相（scripts/registry/read-component-entry.sh 那条纪律）');
  }
  const names = pieceNames(zipPath);
  const out = [];
  for (const alias of Object.keys(bin).sort()) {
    if (alias === name || alias === entryBase) continue;
    const rel = bin[alias];
    
    

    if (rel === declaredEntry) continue;
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

function sha256File(file) {
  const h = crypto.createHash('sha256');
  const fd = fs.openSync(file, 'r');
  try {
    const chunk = Buffer.allocUnsafe(1024 * 1024);
    for (;;) {
      const n = fs.readSync(fd, chunk, 0, chunk.length, null);
      if (n <= 0) break;
      h.update(chunk.subarray(0, n));
    }
  } finally {
    fs.closeSync(fd);
  }
  return h.digest('hex');
}

function toolsFromDist() {
  if (!fs.existsSync(DIST)) throw new Error('dist 目录不存在: ' + DIST);
  const out = [];
  for (const f of fs.readdirSync(DIST)) {
    const m = /^component-([a-z0-9-]+)-([0-9][^-]*)-([0-9a-f]{12})-android-arm64\.zip$/.exec(f);
    if (!m) continue;
    const name = m[1];
    const ver = m[2];
    const claimed = m[3];
    const zipPath = path.join(DIST, f);
    const bytes = fs.statSync(zipPath).size;
    if (bytes === 0) throw new Error('0 字节产物: ' + f);
    const digest = sha256File(zipPath);
    const real = digest.slice(0, 12);
    if (real !== claimed) throw new Error('文件名里的内容哈希与实际不符: ' + f + '（名 ' + claimed + ' vs 实 ' + real + '）');
    const entry = entryOf(name);
    
    
    
    
    

    const recipeFp = recipeFingerprintOf(name);
    out.push({
      name, provider: 'zip', version: ver,
      url: BASE + '/component/' + f,
      sha256: digest,
      
      recipeFingerprint: recipeFp,
      entry,
      aliases: aliasesOf(name, zipPath, entry),
    });
  }
  return out;
}

function missingPieces() {
  const out = [];
  const p = path.join(ROOT, 'dist', '.fetch-missing.tsv');
  if (!fs.existsSync(p)) return out;
  let raw = '';
  try { raw = fs.readFileSync(p, 'utf8'); } catch (e) { return out; }
  for (const line of raw.split('\n')) {
    if (!line.trim()) continue;
    const parts = line.split('\t');
    const name = String(parts[0] || '').trim();
    if (!name) continue;
    out.push({
      name,
      reason: String(parts[1] || '').trim() || 'unknown',
      detail: String(parts[2] || '').trim(),
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
  const raw = process.env.LOBOS_COMPONENT_REVISION || '';
  if (!/^[1-9][0-9]*$/.test(raw)) {
    

    if (DECLARED_ONLY) {
      console.error('::notice title=缺 revision::--declared-only 那一档逐字不要求 revision（一趟逐字不是发布轮）');
      return 0;
    }
    throw new Error('LOBOS_COMPONENT_REVISION 必须是正整数（它来自发布 tag `component-<channel>-<revision>`），读到: ' + (raw || '空'));
  }
  return Number(raw);
}

function criteria() {
  const j = JSON.parse(fs.readFileSync(VERIFY, 'utf8'));
  return j.sources || {};
}

function main() {
  const crit = criteria();
  const tools = toolsFromDist().sort((a, b) => a.name.localeCompare(b.name));
  if (!tools.length) throw new Error('清单为空：dist 下没有一颗按命名契约产出的件');
  for (const t of tools) {
    
    
    
    

    const v = crit[t.name];
    if (!v) {
      throw new Error('钉值表里没有这一件: ' + t.name + '（在 scripts/component-sources.json 里补）');
    }

    if (!t.entry) {
      

      process.stderr.write('  - ' + t.name + ' 纯库件（没有程序那一侧、entry 留空）\n');
    }
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

  const missing = missingPieces();
  if (missing.length) {
    for (const m of missing) {
      process.stderr.write('  - ' + m.name + ' 缺件（' + m.reason + '）：' + m.detail + '\n');
    }
  }
  const man = {
    schema: 1,
    channel: CHANNEL,
    revision: revisionForManifest(),
    version: versionString(),
    sequence: Math.floor(Date.now() / 1000),
    expiresEpochMs: Date.now() + TTL_MS,
    tools,
    
    missing,
  };
  const body = JSON.stringify(man, null, 2) + '\n';
  
  
  
  
  
  
  
  
  
  
  
  if (DECLARED_ONLY) {
    process.stdout.write(body);
    return;
  }
  const key = fs.readFileSync(KEY, 'utf8');
  const sig = crypto.sign(null, Buffer.from(body, 'utf8'), key).toString('base64');
  const ok = crypto.verify(null, Buffer.from(body, 'utf8'), fs.readFileSync(PUBKEY, 'utf8'), Buffer.from(sig, 'base64'));
  if (!ok) { console.error('::error title=签名不配对::私钥与 APK 焊死的公钥不是一对 —— 这份清单设备验不过，拒绝发布。'); process.exit(1); }
  fs.mkdirSync(OUT, { recursive: true });
  fs.writeFileSync(path.join(OUT, 'component-manifest.json'), body);
  fs.writeFileSync(path.join(OUT, 'component-manifest.json.sig'), sig + '\n');
  console.log('[component] channel=' + CHANNEL + ' revision=' + man.revision + ' version=' + man.version + ' sequence=' + man.sequence + ' tools=' + tools.length);
  for (const t of tools) {
    const als = t.aliases.length ? ' 别名 ' + t.aliases.map((a) => a.name + '→' + a.entry).join(',') : '';
    console.log('  - ' + t.name + '@' + t.version + ' ' + t.provider + ' ' + t.sha256.slice(0, 12) + '… 入口 ' + t.entry + als);
  }
  console.log('[component] 签名自检通过（与 APK 公钥配对）: ' + path.join(OUT, 'component-manifest.json'));
}

module.exports = { aliasesOf, assertNameUniqueness, pieceText, pieceNames };

if (require.main === module) {
  try { main(); } catch (e) { console.error('::error title=清单发布失败::' + (e && e.message)); process.exit(1); }
}
