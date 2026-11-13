#!/usr/bin/env node
'use strict';

// 底座件 pin 记录的重生成器 —— 恢复仓内那「可生产的那一半」。
//
// 背景（读 .github/native-capabilities-pin.json 的那一份在 scripts/publish/publish-native-manifest.js）：
//   · 第 28 行读这个文件；第 82-93 行 pinEntry() 取 j.pins 这一张字典，
//     并**只取最后一个键**（keys[keys.length-1]），把它展开成 { fingerprint, ...那个值 }。
//   · 第 191 行用它：ZIP 里有那一件的 libName 时，该件改成 source=ota，
//     url 指向 BASE/<channel>/native/<zip 名>，sha256 取 pin.sha256，pin 记 pin.tag。
//   => 读取方要求 pin 的**值**里有 tag / zip / sha256 / abi 这四格。
//
// 这份文件以前由 .github/workflows/pin-capabilities.yml 产（那时它打的是

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const https = require('node:https');
const zlib = require('node:zlib');
const { spawnSync } = require('node:child_process');

const ROOT = path.resolve(__dirname, '../..');
const CAPS = path.join(ROOT, '.github', 'native-capabilities.txt');
const SOURCES = path.join(ROOT, 'scripts', 'component-sources.json');
const CACHE_KEY = path.join(ROOT, 'scripts', 'toolchain', 'cache-key.sh');
const DEFAULT_OUT = path.join(ROOT, '.github', 'native-capabilities-pin.json');
const ABI = process.env.ABI || 'arm64-v8a';
const GITHUB_REPO = 'lobbowen/lob-os';
const RELEASE_HOST = 'https://github.com';

const ARGS = process.argv.slice(2);
function argValue(flag) {
  const i = ARGS.indexOf(flag);
  return i >= 0 && ARGS[i + 1] ? ARGS[i + 1] : null;
}
const OUT = argValue('--out') || DEFAULT_OUT;
const CACHE_DIR = argValue('--cache-dir') || path.join(ROOT, 'work', 'pin-cache');
const OFFLINE = ARGS.includes('--offline');
const PRINT_ONLY = ARGS.includes('--print');

const problems = [];
const bad = (m) => problems.push(m);

function sh(cmd, args) {
  const r = spawnSync(cmd, args, { encoding: 'utf8' });
  if (r.status !== 0) {
    throw new Error((r.stderr || '').trim() || (cmd + ' ' + args.join(' ') + ' 退出码 ' + r.status));
  }
  return (r.stdout || '').trim();
}

function sha256(buf) {
  return crypto.createHash('sha256').update(buf).digest('hex');
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

function containerFormOf(file) {
  const FMT = path.join(ROOT, 'scripts', 'toolchain', 'fmt.sh');
  if (!fs.existsSync(FMT)) { bad('判别源不在：' + FMT + ' —— 容器判别源必须唯一'); return 'unknown'; }
  const r = spawnSync('bash', ['-c', '. "$1"; fmt_of "$2"', 'bash', FMT, file], { encoding: 'utf8' });
  if (r.status !== 0) { bad('判别源读不了 ' + file + '：' + String(r.stderr || '').slice(0, 200)); return 'unknown'; }
  return String(r.stdout || '').trim() || 'unknown';
}

function magicOf(file) {
  try {
    const fd = fs.openSync(file, 'r');
    try {
      const b = Buffer.alloc(4);
      const n = fs.readSync(fd, b, 0, 4, 0);
      if (n < 4) return '';
      return b.toString('hex');
    } finally { fs.closeSync(fd); }
  } catch (e) { return ''; }
}

function outerEntryCount(file) {
  try {
    const z = readZipFile(file);
    const n = z.names().length;
    z.close();
    return n;
  } catch (e) { return -1; }
}

function pickInnerZip(file, form) {
  const want = path.basename(file).replace(/\.zip$/, '');
  const work = fs.mkdtempSync(path.join(require('node:os').tmpdir(), 'pin-inner-'));
  try {
    // 那**外层自己**是不是那一颗件
    const outerId = outerMetaId(file, form, work);
    if (outerId) {
      return outerId === want ? { path: file, outerEntries: outerEntryCount(file) } : null;
    }

    const inners = [];
    const walk = (dir, depth) => {
      if (depth > 2) return;
      let kids = [];
      try { kids = fs.readdirSync(dir, { withFileTypes: true }); } catch (e) { return; }
      for (const k of kids) {
        const p = path.join(dir, k.name);
        if (k.isDirectory()) walk(p, depth + 1);
        else if (k.isFile() && /\.zip$/.test(k.name)) inners.push(p);
      }
    };
    walk(work, 0);
    const hits = [];
    for (const p of inners) {
      let id = '';
      try {
        const z = readZipFile(p);
        id = z.has('component-meta.json') ? (JSON.parse(z.bytes('component-meta.json').toString('utf8')).id || '') : '';
        z.close();
      } catch (e) { id = ''; }
      if (id === want) hits.push(p);
    }
    if (hits.length !== 1) {
      if (hits.length === 0) bad(want + '：外层套内层那一族里逐字挑不出内层那一个（候选 ' + inners.length + ' 个）');
      else bad(want + '：外层套内层那一族里逐字有 ' + hits.length + ' 个内层候选 —— 多过候选判红，不取第一个');
      return null;
    }
    return { path: hits[0], outerEntries: outerEntryCount(file) };
  } catch (e) {
    bad('挑内层失败（' + file + '）：' + e.message);
    return null;
  } finally {
    try { fs.rmSync(work, { recursive: true, force: true }); } catch (e) { /* 清理失败不影响判别 */ }
  }
}

function outerMetaId(file, form, work) {
  try {
    if (form === 'gzip') {
      const r = spawnSync('tar', ['xzf', file, '-C', work], { encoding: 'utf8' });
      if (r.status !== 0) return '';
      const p = path.join(work, 'component-meta.json');
      if (!fs.existsSync(p)) return '';
      return String(JSON.parse(fs.readFileSync(p, 'utf8')).id || '');
    }
    if (form === 'zip' || form === 'zip64') {
      const z = readZipFile(file);
      const id = z.has('component-meta.json') ? String(JSON.parse(z.bytes('component-meta.json').toString('utf8')).id || '') : '';
      z.close();
      return id;
    }
  } catch (e) { return ''; }
  return '';
}

function readZipFile(file) {
  const fd = fs.openSync(file, 'r');
  const size = fs.fstatSync(fd).size;
  const readAt = (pos, len) => {
    if (len <= 0) return Buffer.alloc(0);
    const b = Buffer.allocUnsafe(len);
    let got = 0;
    while (got < len) {
      const n = fs.readSync(fd, b, got, len - got, pos + got);
      if (n <= 0) break;
      got += n;
    }
    return got === len ? b : b.subarray(0, got);
  };

  // 尾部找 EOCD（0x06054b50）
  const tailLen = Math.min(size, 65536 + 22);
  const tail = readAt(size - tailLen, tailLen);
  let eocd = -1;
  for (let i = tail.length - 22; i >= 0; i--) {
    if (tail.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) { fs.closeSync(fd); throw new Error('不是 zip（找不到中央目录结束记录）'); }
  let count = tail.readUInt16LE(eocd + 10);
  let cdSize = tail.readUInt32LE(eocd + 12);
  let cdOff = tail.readUInt32LE(eocd + 16);

  // zip64：EOCD 那几格是哨兵值时，真实的在 zip64 EOCD 里
  if (count === 0xffff || cdSize === 0xffffffff || cdOff === 0xffffffff) {
    const loc = eocd >= 20 ? tail.readUInt32LE(eocd - 20) : 0;
    if (loc !== 0x07064b50) { fs.closeSync(fd); throw new Error('zip64 哨兵在位，但找不到 zip64 定位器'); }
    const z64Off = Number(tail.readBigUInt64LE(eocd - 12));
    const z64 = readAt(z64Off, 56);
    if (z64.readUInt32LE(0) !== 0x06064b50) { fs.closeSync(fd); throw new Error('zip64 结束记录坏了'); }
    count = Number(z64.readBigUInt64LE(32));
    cdSize = Number(z64.readBigUInt64LE(40));
    cdOff = Number(z64.readBigUInt64LE(48));
  }

  const cd = readAt(cdOff, cdSize);
  const entries = new Map();
  let off = 0;
  for (let n = 0; n < count; n++) {
    if (off + 46 > cd.length || cd.readUInt32LE(off) !== 0x02014b50) {
      fs.closeSync(fd);
      throw new Error('中央目录项坏了');
    }
    const method = cd.readUInt16LE(off + 10);
    let csize = cd.readUInt32LE(off + 20);
    let usize = cd.readUInt32LE(off + 24);
    const nameLen = cd.readUInt16LE(off + 28);
    const extraLen = cd.readUInt16LE(off + 30);
    const commentLen = cd.readUInt16LE(off + 32);
    const extAttr = cd.readUInt32LE(off + 38);
    let localOff = cd.readUInt32LE(off + 42);
    const name = cd.toString('utf8', off + 46, off + 46 + nameLen);
    // zip64 扩展字段（头两个字节是 0x0001）
    if (csize === 0xffffffff || usize === 0xffffffff || localOff === 0xffffffff) {
      let e = off + 46 + nameLen;
      const eEnd = e + extraLen;
      while (e + 4 <= eEnd) {
        const hid = cd.readUInt16LE(e);
        const hsz = cd.readUInt16LE(e + 2);
        if (hid === 0x0001) {
          let p = e + 4;
          if (usize === 0xffffffff) { usize = Number(cd.readBigUInt64LE(p)); p += 8; }
          if (csize === 0xffffffff) { csize = Number(cd.readBigUInt64LE(p)); p += 8; }
          if (localOff === 0xffffffff) { localOff = Number(cd.readBigUIntLE(p)); }
          break;
        }
        e += 4 + hsz;
      }
    }
    const lho = localOff;
    const lh = readAt(lho, 30);
    if (lh.length < 30 || lh.readUInt32LE(0) !== 0x04034b50) {
      fs.closeSync(fd);
      throw new Error('本地文件头坏了: ' + name);
    }
    const lNameLen = lh.readUInt16LE(26);
    const lExtraLen = lh.readUInt16LE(28);
    const dataStart = lho + 30 + lNameLen + lExtraLen;
    entries.set(name, { method, usize, extAttr, dataStart, csize });
    off += 46 + nameLen + extraLen + commentLen;
  }

  return {
    names: () => Array.from(entries.keys()),
    has: (n) => entries.has(n),
    bytes: (n) => {
      const e = entries.get(n);
      if (!e) return null;
      if (e.csize > 256 * 1024 * 1024) {
        throw new Error('条目 ' + n + ' 压缩后仍有 ' + e.csize + ' 字节 —— 这一次读不下（这一份不整包读，改成按条目读）');
      }
      const raw = readAt(e.dataStart, e.csize);
      if (e.method === 0) return raw;
      if (e.method === 8) return zlib.inflateRawSync(raw);
      throw new Error('不支持的压缩方式 ' + e.method + '（条目 ' + n + '）');
    },
    isSymlink: (n) => {
      const e = entries.get(n);
      if (!e) return false;
      const mode = (e.extAttr >>> 16) & 0xf000;
      return mode === 0xa000;
    },
    close: () => { try { fs.closeSync(fd); } catch (e) {} },
  };
}

function downloadTo(url, dest) {
  return new Promise((resolve, reject) => {
    const go = (u, hops) => {
      if (hops > 8) { reject(new Error('重定向跳太多次: ' + u)); return; }
      https.get(u, { headers: { 'User-Agent': 'lobos-pin-generator' } }, (res) => {
        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
          res.resume();
          go(new URL(res.headers.location, u).toString(), hops + 1);
          return;
        }
        if (res.statusCode !== 200) {
          res.resume();
          reject(new Error('HTTP ' + res.statusCode + ' ' + u));
          return;
        }
        const out = fs.createWriteStream(dest);
        res.pipe(out);
        res.on('error', reject);
        out.on('error', reject);
        out.on('finish', () => out.close(() => resolve()));
      }).on('error', reject);
    };
    go(url, 0);
  });
}

function parseCaps() {
  if (!fs.existsSync(CAPS)) { bad('缺少 ' + CAPS + ' —— 底座件档位表没了'); return []; }
  const out = [];
  for (const line of fs.readFileSync(CAPS, 'utf8').split('\n')) {
    const t = line.trim();
    if (!t || t.startsWith('#')) continue;
    const parts = t.split(/\s+/);
    if (parts.length < 3) continue;
    out.push({ tier: parts[0], capsLibName: parts[1], id: parts[2] });
  }
  if (!out.length) bad('从 ' + CAPS + ' 一条也没解析出来 —— 档位表格式变了？');
  return out;
}

function sourceEntry(id) {
  try {
    const tab = JSON.parse(fs.readFileSync(SOURCES, 'utf8'));
    return {
      sources: (tab.sources || {})[id] || {},
      ndkVersion: tab.ndkVersion || '',
      llvmVersion: tab.llvmVersion || '',
    };
  } catch (e) {
    bad('读不了 ' + SOURCES + '：' + e.message);
    return { sources: {}, ndkVersion: '', llvmVersion: '' };
  }
}

async function fetchAsset(tag, asset, cacheDir) {
  const url = RELEASE_HOST + '/' + GITHUB_REPO + '/releases/download/' + tag + '/' + asset;
  const zipPath = path.join(cacheDir, asset);
  const sidePath = zipPath + '.sha256';
  if (OFFLINE) {
    if (!fs.existsSync(zipPath)) throw new Error('离线模式，缓存里没有 ' + asset);
    if (!fs.existsSync(sidePath)) throw new Error('离线模式，缓存里没有 ' + asset + '.sha256');
  } else {
    fs.mkdirSync(cacheDir, { recursive: true });
    await downloadTo(url, zipPath);
    await downloadTo(url + '.sha256', sidePath);
  }

  const bytes = fs.statSync(zipPath).size;
  const digest = sha256File(zipPath);
  const sideText = fs.readFileSync(sidePath, 'utf8').trim().split(/\s+/)[0] || '';
  return { url, zipPath, sidePath, bytes, digest, sideSha256: sideText };
}

function fileRecords(zip, shape) {
  const out = [];
  const files = (shape && shape.files) || [];
  for (const f of files) {
    if (!zip.has(f.path)) {
      out.push({ path: f.path, declaredBytes: f.bytes, declaredSha256: f.sha256 || '', verified: 'no', reason: 'zip 里没有这个路径' });
      continue;
    }
    const buf = zip.bytes(f.path);
    if (zip.isSymlink(f.path)) {
      // 软链条目：zip 里存的是链接目标文本，不是那份字节。
      out.push({
        path: f.path,
        bytes: buf.length,
        sha256: sha256(buf),
        form: f.form || '',
        role: f.role || '',
        verified: 'symlink-target-text',
        linkTarget: buf.toString('utf8').replace(/\0+$/, ''),
        resolvedFileSha256: f.sha256 || '',
        reason: 'zip 里这一条是软链，存的是链接目标文本；它指向的那份字节的 sha256 记在 resolvedFileSha256',
      });
      continue;
    }
    const real = sha256(buf);
    out.push({
      path: f.path,
      bytes: buf.length,
      sha256: real,
      form: f.form || '',
      role: f.role || '',
      verified: real === f.sha256 && buf.length === f.bytes ? 'sha256-matched-piece-shape' : 'sha256-mismatch',
      reason: real === f.sha256 && buf.length === f.bytes ? '' : '与 piece-shape.json 里记的那一格对不上',
    });
  }
  return out;
}

function fingerprintOf(rec) {
  const canonical = JSON.stringify({
    id: rec.id,
    tier: rec.tier,
    capsLibName: rec.capsLibName,
    version: rec.declared.version,
    installName: rec.declared.installName,
    entryName: rec.declared.entryName,
    tag: rec.tag,
    zip: rec.zip,
    sha256: rec.sha256,
    // 那**层**那一格逐字进指纹（那 sha256 那一格逐字记的是哪一层、
    // 那**变了层**逐字得**判得出**）。
    sidecarLayer: rec.sidecarLayer,
    outerSha256: rec.outerSha256,
    capsEntryKind: rec.capsEntryKind,
    abi: rec.abi,
  });
  return sha256(Buffer.from(canonical, 'utf8'));
}

async function main() {
  const caps = parseCaps();
  const pins = {};
  const missing = [];
  const perPiece = [];

  for (const c of caps) {
    const src = sourceEntry(c.id);
    const declared = {
      version: src.sources.version ? String(src.sources.version) : '',
      installName: src.sources.installName ? String(src.sources.installName) : '',
      entryName: src.sources.entryName ? String(src.sources.entryName) : '',
      upstreamSourceSha256: src.sources.sha256 ? String(src.sources.sha256) : '',
    };
    const rec = {
      id: c.id,
      tier: c.tier,
      capsLibName: c.capsLibName,
      declared,
      abi: ABI,
      tag: '',
      zip: '',
      sha256: '',
    };
    try {
      const tag = sh('bash', [CACHE_KEY, 'tag', c.id]);
      const asset = sh('bash', [CACHE_KEY, 'asset', c.id]);
      rec.tag = tag;
      rec.zip = asset;
      const got = await fetchAsset(tag, asset, CACHE_DIR);
      const real = got.digest;
      rec.sha256 = real;
      rec.url = got.url;
      rec.bytes = got.bytes;
      rec.sidecarSha256 = got.sideSha256;
      rec.realSha256 = real;
      rec.sha256MatchesSidecar = real === got.sideSha256;
      if (!rec.sha256MatchesSidecar) {
        bad(c.id + '：下载字节的 sha256 与 release 上的 ' + asset + '.sha256 边车不一致');
      }

      rec.sha256Layer = 'inner';
      rec.innerSha256 = real;
      // 那**容器形态**逐字**按字节**判（判别源唯一：scripts/toolchain/fmt.sh）
      // 那 zip64 那一档逐字**也**认（它逐字是 zip 那一档的中央目录变体）。
      // 形态逐字**三层**：外层容器 / 外层是不是 zip / 外层是不是 zip64。
      rec.outerForm = containerFormOf(got.zipPath);
      rec.outerSha256 = got.digest;      //那**外层**那一份的 sha256（那 sha256 逐字是那**下载**下来那一份的）
      rec.outerBytes = got.bytes;
      rec.outerMagic = magicOf(got.zipPath);
      // 那**内层**那一份（那「外层套内层」那一族）：
      //     而那 sha256 逐字要记**内层**那一份的（那 pin 记的是**内层**那一个件包），
      // 那**同层才比**（那 sidecarLayer 逐字记的是「那 sha256 记的是哪一层」）。
      const inner = pickInnerZip(got.zipPath, rec.outerForm);
      rec.sidecarLayer = inner ? 'inner' : 'outer';
      rec.outerEntries = inner ? inner.outerEntries : (rec.outerForm === 'zip' || rec.outerForm === 'zip64' ? outerEntryCount(got.zipPath) : -1);
      const zipPathForFacts = inner ? inner.path : got.zipPath;
      const zip = readZipFile(zipPathForFacts);
      // 那 **sha256** 那一格逐字**按层**给（Lead 那一裁逐字「pin 记内层 zip

      if (inner) {
        rec.sha256 = sha256File(inner.path);
        rec.innerBytes = fs.statSync(inner.path).size;
        rec.innerZip = path.basename(inner.path);
      } else {
        rec.sha256 = real;
        rec.innerBytes = 0;
        rec.innerZip = '';
      }
      const metaBuf = zip.has('component-meta.json') ? zip.bytes('component-meta.json') : null;
      const shapeBuf = zip.has('piece-shape.json') ? zip.bytes('piece-shape.json') : null;
      let meta = null;
      let shape = null;
      if (metaBuf) { try { meta = JSON.parse(metaBuf.toString('utf8')); } catch (e) { bad(c.id + '：component-meta.json 解析失败'); } }
      if (shapeBuf) { try { shape = JSON.parse(shapeBuf.toString('utf8')); } catch (e) { bad(c.id + '：piece-shape.json 解析失败'); } }
      if (!meta || !shape) bad(c.id + '：zip 里取不出 component-meta.json 或 piece-shape.json');
      rec.componentMeta = meta ? {
        id: meta.id,
        version: meta.version,
        entry: meta.entry,
        role: meta.role,
        buildSha: meta.buildSha,
        buildRun: meta.buildRun,
        sourceSha256: meta.sourceSha256 || '',
      } : null;
      const files = shape ? fileRecords(zip, shape) : [];
      zip.close();
      rec.files = files;
      const unmatched = files.filter((f) => f.verified === 'sha256-mismatch' || f.verified === 'no');
      rec.filesVerified = unmatched.length === 0;
      if (unmatched.length) bad(c.id + '：' + unmatched.length + ' 个文件与 piece-shape 里记的 sha256 对不上');
      rec.kind = shape ? (shape.kind || '') : '';
      rec.entryInArtifact = shape && shape.entry ? shape.entry : '';
      // 档位表里那一列 libName 是老流程（jniLibs 里那几个 .so）留下的名字，
      // 与现在件包里的真实路径不一定同名。这里如实记下对照结果。
      const libHit = files.find((f) => f.path === c.capsLibName || f.path.endsWith('/' + c.capsLibName));
      rec.capsLibNameFoundInArtifact = !!libHit;
      // 那 **capsEntryKind** 那一格（是 C-979 逐字要的那一格）：
      //     「`capsLibName` 这一格装的是「**在件包里能命中那一项的字符串**」；
      //       库形态 = `.so` 名，程序形态 = **件包内相对路径**（`bin/git`、`bin/bash`、`bin/rg`），**不用裸名**；
      //       另加一格 `capsEntryKind`（`library` / `program`）标明形态」
      // 那**形态**逐字由那件包的**入口**答（那 entry 逐字带 `bin/` 或 `lib/` 前缀）。
      // 那 01 裁的 S606-01-ALIAS-BIN-KEY-RULING.md §9.1 逐字：
      //     「llvm 那一件的 capsEntryKind 逐字应是 program，它的 capsLibName 逐字应是 bin/clang

      const entryKind = rec.entryInArtifact
        ? (rec.entryInArtifact.startsWith('lib/') ? 'library' : (rec.entryInArtifact.startsWith('bin/') ? 'program' : 'unknown'))
        : (rec.kind === 'library' ? 'library' : (rec.kind === 'program' ? 'program' : 'unknown'));
      rec.capsEntryKind = entryKind;
      // 那 capsLibName 那一格逐字**不改名、改语义**：
      // 一格逐字答的是「**这一件逐字提供哪个字节名**」（Lead 本机实读逐字：
      //       「`capsLibName` 这一格装的是「**在件包里能命中那一项的字符串**」」），
      //**不是**「这一件的入口是什么」。
      // 一格逐字**不是**「程序形态相对路径」那条**普遍规则**
      //     （Lead 判：busybox 那一件逐字是**程序形态**、
      // 它逐字**真的提供** libbusybox.so 那个字节

      rec.capsLibNameIsBare = !!(c.capsLibName && !c.capsLibName.includes('/'));
      if (!rec.capsLibNameFoundInArtifact) {
        bad(c.id + '：档位表第二列逐字是 ' + c.capsLibName +
          '，件包里逐字**命中不到**它（是「档位表与产物分家」那一格）' +
          '（该提供的是 ' + (rec.artifactLibraryPath || rec.entryInArtifact || '(空)') + '）');
      }
      const realLib = files.find((f) => f.role === 'library' && f.verified === 'sha256-matched-piece-shape');
      rec.artifactLibraryPath = realLib ? realLib.path : '';
      rec.entryInArtifactExists = shape && shape.entry ? zip.has(shape.entry) : false;
    } catch (e) {
      rec.missing = true;
      rec.missingReason = e.message;
      missing.push({ id: c.id, tier: c.tier, capsLibName: c.capsLibName, reason: e.message });
      bad(c.id + '：取不到可核事实 —— ' + e.message);
    }
    perPiece.push(rec);
  }

  for (const rec of perPiece) {
    if (rec.missing) continue;
    const fp = fingerprintOf(rec);
    pins[fp] = {
      id: rec.id,
      tier: rec.tier,
      capsLibName: rec.capsLibName,
      // 那 **capsEntryKind** 那一格（按 C-979 逐字「另加一格 capsEntryKind

      capsEntryKind: rec.capsEntryKind,
      // 那 capsLibName 逐字**是不是裸名**那一格（程序形态逐字该是件包内相对路径）。
      capsLibNameIsBare: rec.capsLibNameIsBare,
      capsLibNameFoundInArtifact: rec.capsLibNameFoundInArtifact,
      artifactLibraryPath: rec.artifactLibraryPath,
      entryInArtifact: rec.entryInArtifact,
      entryInArtifactExists: rec.entryInArtifactExists,
      kind: rec.kind,
      declared: rec.declared,
      tag: rec.tag,
      zip: rec.zip,
      sha256: rec.sha256,
      // 那 **outerSha256** 那一格（它逐字是那**外层**那一份的 sha256）、
      // 那 **sidecarLayer** 那一格（它逐字记的是「那 sha256 记的是哪一层」：
      //     inner = 那 sha256 记的是**内层**那一个件包；outer = 记的是**外层**那一份）。
      // 按 Lead 那一裁逐字「**同层才比**」比边车那一份逐字要比**同层**那一份。
      outerSha256: rec.outerSha256,
      outerBytes: rec.outerBytes,
      outerForm: rec.outerForm,
      outerMagic: rec.outerMagic,
      outerEntries: rec.outerEntries,
      sidecarLayer: rec.sidecarLayer,
      sha256Layer: rec.sha256Layer,
      innerSha256: rec.innerSha256,
      innerBytes: rec.innerBytes,
      innerZip: rec.innerZip,
      abi: rec.abi,
      url: rec.url,
      bytes: rec.bytes,
      sidecarSha256: rec.sidecarSha256,
      realSha256: rec.realSha256,
      sha256MatchesSidecar: rec.sha256MatchesSidecar,
      buildSha: rec.componentMeta ? rec.componentMeta.buildSha : '',
      buildRun: rec.componentMeta ? rec.componentMeta.buildRun : '',
      files: rec.files,
      filesVerified: rec.filesVerified,
    };
  }

  const out = {
    schema: 2,
    note: '本文件是生成物，不要手改。' +
      '本文件由 scripts/publish/generate-native-capabilities-pin.js 从当前已发布的 base 筐件产物重新生成。' +
      '每一条 pin 的 sha256 是下载 GitHub Release 上那一份 asset 后本地实算，并与同一 release 的 .sha256 边车逐字比对通过；' +
      'files 里每一格 sha256 是从 zip 里把那份字节取出来实算的。' +
      '本文件不是可发布的清单（projection, not a publishable manifest），也不含任何签名。' +
      'generatedAt 是生成时刻：每一次运行都会写出一个新的时间戳，所以这个文件每跑一次就变一次。' +
      'diff 里出现 generatedAt 一行的变化属于正常，其余任何字段的变化才需要人去看。',
    generatedBy: 'scripts/publish/generate-native-capabilities-pin.js',
    generatedAt: new Date().toISOString(),
    generatedAtNote: 'generatedAt 是生成时刻，每次运行都会变 —— 这个文件是生成物，不要手改。',
    abi: ABI,
    source: {
      declaredInputs: [
        '.github/native-capabilities.txt（档位表：tier / libName / id）',
        'scripts/component-sources.json（每件的 version / installName / entryName / 上游源码 sha256）',
        'scripts/toolchain/cache-key.sh tag <id>（release tag）',
        'scripts/toolchain/cache-key.sh asset <id>（asset 名）',
      ],
      artifactsFrom: 'GitHub Releases（' + GITHUB_REPO + '），每个 base-<id> tag 下的 <asset> 与 <asset>.sha256 边车',
      sha256Provenance: '边车给出的 sha256 与本地对下载字节实算的 sha256 逐字相同（两值都记在每条 pin 的 sidecarSha256 与 realSha256 里）；' +
        '逐文件 sha256 由 zip 里的真实字节实算得到。',
      reader: {
        file: 'scripts/publish/publish-native-manifest.js',
        pinPathLine: 28,
        readerLines: '82-93',
        useLines: '191-197',
        shape: 'pins 是字典；读取方取最后一个键，值里要有 tag / zip / sha256 / abi 四格',
        knownGap: '读取方只认字典里的最后一条 pin，且要求那一份 zip 同时含各件的 libName；' +
          '当前没有这样一份已发布的聚合 zip，所以这份文件里的逐件 pin 里只有最后一条会被现有读取方采用。' +
          '而在 CI 里调用它时 ZIP 传的是空串（build-component.yml 第 110 行），于是 pin 命中恒假，' +
          '这一份文件眼下只起「可核事实的登记」作用，还不构成 OTA 通道。',
      },
    },
    pieces: perPiece.map((r) => ({
      id: r.id,
      tier: r.tier,
      capsLibName: r.capsLibName,
      version: r.declared.version,
      tag: r.tag,
      zip: r.zip,
      sha256: r.sha256,
      missing: !!r.missing,
      missingReason: r.missingReason || '',
      fingerprint: r.missing ? '' : fingerprintOf(r),
    })),
    missing,
    pins,
  };

  const body = JSON.stringify(out, null, 2) + '\n';
  if (PRINT_ONLY) {
    process.stdout.write(body);
  } else {
    fs.writeFileSync(OUT, body);
    console.log('[pin] 已写出 ' + path.relative(ROOT, OUT) + '：pin ' + Object.keys(pins).length + ' 条，missing ' + missing.length + ' 条');
  }
  for (const m of missing) console.error('  [missing] ' + m.id + ' —— ' + m.reason);
  if (problems.length) {
    for (const p of problems) console.error('::error title=底座件 pin 重生成有问题::' + p);
    process.exit(1);
  }
}

main().catch((e) => {
  console.error('::error title=底座件 pin 重生成失败::' + (e && e.message));
  process.exit(1);
});
