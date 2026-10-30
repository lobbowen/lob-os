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

// 最小 zip 读取器：只解 stored(0) 与 deflate(8)，够读件包。
function readZip(buf) {
  let eocd = -1;
  for (let i = buf.length - 22; i >= 0 && i >= buf.length - 22 - 65536; i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error('不是 zip（找不到中央目录结束记录）');
  const count = buf.readUInt16LE(eocd + 10);
  let off = buf.readUInt32LE(eocd + 16);
  const entries = new Map();
  for (let n = 0; n < count; n++) {
    if (buf.readUInt32LE(off) !== 0x02014b50) throw new Error('中央目录项坏了');
    const method = buf.readUInt16LE(off + 10);
    const csize = buf.readUInt32LE(off + 20);
    const usize = buf.readUInt32LE(off + 24);
    const nameLen = buf.readUInt16LE(off + 28);
    const extraLen = buf.readUInt16LE(off + 30);
    const commentLen = buf.readUInt16LE(off + 32);
    const extAttr = buf.readUInt32LE(off + 38);
    const localOff = buf.readUInt32LE(off + 42);
    const name = buf.toString('utf8', off + 46, off + 46 + nameLen);
    const lho = localOff;
    if (buf.readUInt32LE(lho) !== 0x04034b50) throw new Error('本地文件头坏了: ' + name);
    const lNameLen = buf.readUInt16LE(lho + 26);
    const lExtraLen = buf.readUInt16LE(lho + 28);
    const dataStart = lho + 30 + lNameLen + lExtraLen;
    const raw = buf.subarray(dataStart, dataStart + csize);
    entries.set(name, { method, usize, extAttr, raw });
    off += 46 + nameLen + extraLen + commentLen;
  }
  return {
    names: () => Array.from(entries.keys()),
    has: (n) => entries.has(n),
    bytes: (n) => {
      const e = entries.get(n);
      if (!e) return null;
      if (e.method === 0) return Buffer.from(e.raw);
      if (e.method === 8) return zlib.inflateRawSync(e.raw);
      throw new Error('不支持的压缩方式 ' + e.method + '（条目 ' + n + '）');
    },
    isSymlink: (n) => {
      const e = entries.get(n);
      if (!e) return false;
      const mode = (e.extAttr >>> 16) & 0xf000;
      return mode === 0xa000;
    },
  };
}

function fetchToFile(url) {
  // GitHub 的 release 资产直链会先给 302，跟随重定向后才是真正的字节。
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
        const chunks = [];
        res.on('data', (c) => chunks.push(c));
        res.on('end', () => resolve(Buffer.concat(chunks)));
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
    fs.writeFileSync(zipPath, await fetchToFile(url));
    fs.writeFileSync(sidePath, await fetchToFile(url + '.sha256'));
  }
  const zipBytes = fs.readFileSync(zipPath);
  const sideText = fs.readFileSync(sidePath, 'utf8').trim().split(/\s+/)[0] || '';
  return { url, zipPath, sidePath, zipBytes, sideSha256: sideText };
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
      const real = sha256(got.zipBytes);
      rec.sha256 = real;
      rec.url = got.url;
      rec.bytes = got.zipBytes.length;
      rec.sidecarSha256 = got.sideSha256;
      rec.realSha256 = real;
      rec.sha256MatchesSidecar = real === got.sideSha256;
      if (!rec.sha256MatchesSidecar) {
        bad(c.id + '：下载字节的 sha256 与 release 上的 ' + asset + '.sha256 边车不一致');
      }
      const zip = readZip(got.zipBytes);
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
      capsLibNameFoundInArtifact: rec.capsLibNameFoundInArtifact,
      artifactLibraryPath: rec.artifactLibraryPath,
      entryInArtifact: rec.entryInArtifact,
      entryInArtifactExists: rec.entryInArtifactExists,
      kind: rec.kind,
      declared: rec.declared,
      tag: rec.tag,
      zip: rec.zip,
      sha256: rec.sha256,
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
