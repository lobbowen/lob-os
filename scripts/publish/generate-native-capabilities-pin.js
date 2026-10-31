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
// 「把 jniLibs 里那些 .so 收成一个 zip」的原生件包）。那个 workflow 与它的
// 指纹脚本 scripts/verify/native-capabilities-fingerprint.sh 都已经删了，
// 于是 pin 记录只剩历史、没有在产的那一半。本脚本就是补回那一半。
//
// 本脚本做什么：
//   1. 读仓内声明源：.github/native-capabilities.txt（档位表）与
//      scripts/component-sources.json（每件的 version/installName/entryName）。
//   2. 用 scripts/toolchain/cache-key.sh 的 tag/asset 子命令算出每一件当前
//      应有的 release tag 与 asset 名（这两个子命令自己会去读钉值表）。
//   3. 从 GitHub Releases 下载那一份 asset 与它的 .sha256 边车，
//      本地实算下载字节的 sha256，与边车逐字比对。
//   4. 从 zip 里取 component-meta.json 与 piece-shape.json，
//      并把每一件**每一个文件**的真实字节取出、实算 sha256，与 piece-shape
//      里记的那一格比对（软链条目单独记账，它存的是链接目标文本）。
//   5. 写出 .github/native-capabilities-pin.json，每一条 pin 带来源与取证记录。
//
// 取不到可核事实的那一条，写成 missing 并附原因，不编 sha256。
//
// zip 的读法：本脚本自带一个最小 zip 读取器（只解 stored 与 deflate），
// 不调 unzip —— 因为 unzip 的 -p 把条目名当通配符，busybox 里那一条
// 「bin/[」会被当成未闭合的字符组而取不出（实测返回空）。
//
// 用法：
//   node scripts/publish/generate-native-capabilities-pin.js
//   node scripts/publish/generate-native-capabilities-pin.js --out <路径> --print
//   node scripts/publish/generate-native-capabilities-pin.js --cache-dir <目录> [--offline]
//
// 本脚本不需要任何密钥，也不会签名任何东西。

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

// 缺陷名（C-102）**把整包读进内存** —— 而这一族它为什么有两边：
//   一边是「算内容哈希要那一份字节」，
//   另一边是「读那份字节要一次读多少」。
// Node 24 对单次 readFileSync 超过 2 GiB 直接抛 ERR_FS_FILE_TOO_LARGE，
// 而件（llvmtoolchain 那一颗 8414536031 字节）就是那种大小。
// 处置：哈希流式算；zip 按需读（中央目录从尾部读，条目按偏移读），
// 而不再把整包当一个 Buffer 拿着。
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

// 从文件按需读的最小 zip 读取器：只解 stored(0) 与 deflate(8)，够读件包。
// 与 readZipFile 同一套对外接口，但**不**把整包读进内存：
//   · 中央目录从文件尾部找 EOCD，按偏移整段读（那一段是目录，不是内容）；
//   · 条目内容按本地文件头算出的偏移现读现解，只留一个条目。
// 而 zip64（单包 >4 GiB 或条目数 >65535 时 zip 才写它）也认：
//   EOCD 里那几格是 0xffffffff/0xffff 时改读 zip64 EOCD；
//   条目里偏移/大小是 0xffffffff 时改读中央目录的 zip64 扩展字段。
//  => 而那**容器形态**逐字**按字节**判（=> 而那判别源唯一：scripts/toolchain/fmt.sh）
//   => 而那 zip64 那一档逐字**也**认（=> 而那它逐字是 zip 那一档的中央目录变体、
//     => 而那它逐字要靠尾部哨兵、=> 而那它逐字**分不出**头四字节）。
//   => 而那**判别源唯一**这一格逐字**跨语言**：=> 而那 shell 侧逐字是 fmt.sh、
//     => 而那 Node 侧逐字**调**它（=> 而那**同一判别式**、=> 而那文件不是同一份；
//       => 而那一格**归 01**在 S607-01 里再裁一句）。
function containerFormOf(file) {
  const FMT = path.join(ROOT, 'scripts', 'toolchain', 'fmt.sh');
  if (!fs.existsSync(FMT)) { bad('判别源不在：' + FMT + ' —— 容器判别源必须唯一'); return 'unknown'; }
  const r = spawnSync('bash', ['-c', '. "$1"; fmt_of "$2"', 'bash', FMT, file], { encoding: 'utf8' });
  if (r.status !== 0) { bad('判别源读不了 ' + file + '：' + String(r.stderr || '').slice(0, 200)); return 'unknown'; }
  return String(r.stdout || '').trim() || 'unknown';
}

// 那一份的**头四字节**（=> 而那它逐字是「那**头**是什么」那一格）。
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

// 那**外层**的中央目录条目数（=> 而那读不到时逐字是 -1、=> 而那 -1 答的是「算不出来」）。
function outerEntryCount(file) {
  try {
    const z = readZipFile(file);
    const n = z.names().length;
    z.close();
    return n;
  } catch (e) { return -1; }
}

// 「外层套内层」那一族：=> 而那**内层**那一个件包（=> 而那按内容挑、=> 而零个或多个都判红）。
//   => 而那**判据**逐字与 scripts/toolchain/fmt.sh 的 fmt_pick_piece **同形**：
//     ① 先判外层自己是不是那一颗件（=> 而那它逐字含 component-meta.json 且那 id 逐字等于要取的那一件）；
//     ② 外层没有才走「外层套内层」（=> 而那按内容挑内层）；
//     ③ gzip / tar 那一档先解 tar 再走 ① 与 ②。
//   => 而那**不取第一个**、=> 而零个或多个逐字**判红**。
function pickInnerZip(file, form) {
  const want = path.basename(file).replace(/\.zip$/, '');
  const work = fs.mkdtempSync(path.join(require('node:os').tmpdir(), 'pin-inner-'));
  try {
    //  => 而那**外层自己**是不是那一颗件
    const outerId = outerMetaId(file, form, work);
    if (outerId) {
      return outerId === want ? { path: file, outerEntries: outerEntryCount(file) } : null;
    }
    //  => 而那「外层套内层」那一档
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

// 那**外层**（或那解开的 tar）的 component-meta.json 里的 id（=> 而那没有逐字是空串）。
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

// 缺陷名（C-102）**同一条 zip 有两条读法**：原来只有一条 —— 把整包 readFileSync
// 进内存再解析。而那一条对 >2 GiB 的件直接抛 ERR_FS_FILE_TOO_LARGE。
// 现在只有一条读法：readZipFile（按需读）。
// 处置不是「两条并存」，而是把整包在内存里的那一版删掉（C-104：同一类东西不许有第二种生成路径）——
// 所以下面没有 readZip(buf) 了。

// 下载到文件（**流式落盘**）—— 而不把响应攒成一个 Buffer。
// 缺陷名（C-102）同族：那一份字节要多大才拿得动。
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
  // 缺陷名（C-102）**把整包读进内存**（同族第一处：这一行原为
  // `const zipBytes = fs.readFileSync(zipPath);`）——
  // Node 24 对单次 readFileSync 超过 2 GiB 直接抛 ERR_FS_FILE_TOO_LARGE，
  // 而件（llvmtoolchain 那一颗 8414536031 字节）就是那种大小。
  // 处置：哈希流式算，字节数取 stat，zip 按需读（readZipFile）。
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
    //  => 而那**层**那一格逐字进指纹（=> 而那 sha256 那一格逐字记的是哪一层、
    //     => 而那**变了层**逐字得**判得出**）。
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
      //  => 而那 **sha256** 那一格逐字记的是**哪一层**（=> 而 Lead 那一裁逐字「pin 记内层 zip
      //     + 显式 outerSha256 + sidecarLayer（同层才比）」）：
      //     · 而那**外层**那一份的 sha256 逐字是那**下载**下来那一份的（=> 而那已记在 outerSha256）；
      //     · 而那**内层**那一个件包的 sha256 逐字**另外**算（=> 而那 sha256 那一格逐字记它）；
      //     · 而那 sidecarLayer 逐字记的是「那 sha256 记的是哪一层」。
      //   => 而那**边车**（.sha256）逐字是那**下载**下来那一份的（=> 而那是**外层**）
      //     => 而所以「同层才比」这一条逐字是：边车逐字比**外层**（=> 而那已核过、
      //     => 而而那 sha256 那一格逐字是内层、=> 而那两者逐字**不是同一层**、
      //     => 而而那**不**能互相顶替）。
      //   => 而那 sha256MatchesSidecar 那一格逐字答的是「那**外层**逐字对不对得上边车」。
      rec.sha256Layer = 'inner';
      rec.innerSha256 = real;
      //  => 而那**容器形态**逐字**按字节**判（=> 而那判别源唯一：scripts/toolchain/fmt.sh）
      //     => 而那 zip64 那一档逐字**也**认（=> 而那它逐字是 zip 那一档的中央目录变体）。
      //  => 而那形态逐字**三层**：外层容器 / 外层是不是 zip / 外层是不是 zip64。
      rec.outerForm = containerFormOf(got.zipPath);
      rec.outerSha256 = got.digest;      //  => 而那**外层**那一份的 sha256（=> 而那 sha256 逐字是那**下载**下来那一份的）
      rec.outerBytes = got.bytes;
      rec.outerMagic = magicOf(got.zipPath);
      //  => 而那**内层**那一份（=> 而那「外层套内层」那一族）：
      //     而那 sha256 逐字要记**内层**那一份的（=> 而那 pin 记的是**内层**那一个件包），
      //     => 而那**同层才比**（=> 而那 sidecarLayer 逐字记的是「那 sha256 记的是哪一层」）。
      const inner = pickInnerZip(got.zipPath, rec.outerForm);
      rec.sidecarLayer = inner ? 'inner' : 'outer';
      rec.outerEntries = inner ? inner.outerEntries : (rec.outerForm === 'zip' || rec.outerForm === 'zip64' ? outerEntryCount(got.zipPath) : -1);
      const zipPathForFacts = inner ? inner.path : got.zipPath;
      const zip = readZipFile(zipPathForFacts);
      //  => 而那 **sha256** 那一格逐字**按层**给（=> 而 Lead 那一裁逐字「pin 记内层 zip
      //     + 显式 outerSha256 + sidecarLayer（同层才比）」）：
      //     · 而那**内层**那一个件包逐字**另外**算 sha256（=> 而那 sha256 那一格逐字是它）；
      //     · 而那**外层**那一份逐字是那**下载**下来那一份的（=> 而那已记在 outerSha256）；
      //     · 而那 sidecarLayer 逐字记的是「那 sha256 记的是哪一层」。
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
      //  => 而那 **capsEntryKind** 那一格（=> 而那是 C-979 逐字要的那一格）：
      //     「`capsLibName` 这一格装的是「**在件包里能命中那一项的字符串**」；
      //       库形态 = `.so` 名，程序形态 = **件包内相对路径**（`bin/git`、`bin/bash`、`bin/rg`），**不用裸名**；
      //       另加一格 `capsEntryKind`（`library` / `program`）标明形态」
      //   => 而那**形态**逐字由那件包的**入口**答（=> 而那 entry 逐字带 `bin/` 或 `lib/` 前缀）。
      //   => 而那 01 裁的 S606-01-ALIAS-BIN-KEY-RULING.md §9.1 逐字：
      //     「llvm 那一件的 capsEntryKind 逐字应是 program，=> 而而它的 capsLibName 逐字应是 bin/clang
      //       （件包内相对路径），=> 而**不是** clang（裸名）」
      //   => 而那**裸名**那一档逐字**不报**（=> 而那档位表第二列逐字若**不带**前缀、
      //     => 而那它逐字**就是**裸名、=> 而而那程序形态那一族逐字**该**改成带前缀）。
      const entryKind = rec.entryInArtifact
        ? (rec.entryInArtifact.startsWith('lib/') ? 'library' : (rec.entryInArtifact.startsWith('bin/') ? 'program' : 'unknown'))
        : (rec.kind === 'library' ? 'library' : (rec.kind === 'program' ? 'program' : 'unknown'));
      rec.capsEntryKind = entryKind;
      //  => 而那 capsLibName 那一格逐字**不改名、改语义**：
      //     => 而那它逐字在**库形态**那一族逐字**就是**那 `.so` 名（=> 而那一档逐字不��）；
      //     => 而那它逐字在**程序形态**那一族逐字**该**是那**件包内相对路径**（=> 而那档位表逐字写的是裸名）。
      if (entryKind === 'program' && c.capsLibName && !c.capsLibName.includes('/')) {
        //  => 而那一格逐字**报出来**（=> 而按 C-05「缺字段就报缺、不许猜填」、
        //     => 而而那档位表那一格逐字**是**裸名、=> 而那它逐字**对不上**件包内相对路径）。
        bad(c.id + '：档位表第二列逐字是裸名 ' + c.capsLibName +
          '，=> 而按 C-979 程序形态逐字该是件包内相对路径（=> 而那一件的入口逐字是 ' +
          (rec.entryInArtifact || '(空)') + '）');
        rec.capsLibNameIsBare = true;
      } else {
        rec.capsLibNameIsBare = false;
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

  // 每一条能取到事实的，写成一条 pin（键是这一件的指纹，值里有 tag/zip/sha256/abi）。
  for (const rec of perPiece) {
    if (rec.missing) continue;
    const fp = fingerprintOf(rec);
    pins[fp] = {
      id: rec.id,
      tier: rec.tier,
      capsLibName: rec.capsLibName,
      //  => 而那 **capsEntryKind** 那一格（=> 而按 C-979 逐字「另加一格 capsEntryKind
      //     （library / program）标明形态」）=> 而那它逐字答「这一件是哪一形态」。
      capsEntryKind: rec.capsEntryKind,
      //  => 而那 capsLibName 逐字**是不是裸名**那一格（=> 而那程序形态逐字该是件包内相对路径）。
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
      //  => 而那 **outerSha256** 那一格（=> 而那它逐字是那**外层**那一份的 sha256）、
      //  => 而那 **sidecarLayer** 那一格（=> 而那它逐字记的是「那 sha256 记的是哪一层」：
      //     inner = 那 sha256 记的是**内层**那一个件包；outer = 记的是**外层**那一份）。
      //  => 而按 Lead 那��裁逐字「**同层才比**」=> 而而那比边车那一份逐字要比**同层**那一份。
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
