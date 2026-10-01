#!/usr/bin/env node
'use strict';

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const zlib = require('zlib');

const RESULT_PREFIX = 'LOBOS_VERIFY_RESULT ';

function info(msg) { process.stderr.write('[verify] ' + msg + '\n'); }

function emit(obj) {
  process.stdout.write(RESULT_PREFIX + JSON.stringify(obj) + '\n');
  process.exit(obj.ok ? 0 : 1);
}

function fail(reason, detail, extra) {
  emit(Object.assign({ ok: false, reason, detail: detail || '' }, extra || {}));
}

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (a === '--zip') out.zip = argv[++i];
    else if (a === '--pubkey') out.pubkey = argv[++i];
    else if (a === '--sha256') out.sha256 = argv[++i];
    else if (a === '--version') out.version = argv[++i];
    else if (a === '--shell-protocol') out.shellProtocol = argv[++i];
    else if (a === '--manifest') out.manifest = argv[++i];
  }
  return out;
}

const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n += 1) {
    let c = n;
    for (let k = 0; k < 8; k += 1) c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
    t[n] = c >>> 0;
  }
  return t;
})();

function crc32(buf) {
  let crc = 0xFFFFFFFF;
  for (let i = 0; i < buf.length; i += 1) crc = (crc >>> 8) ^ CRC_TABLE[(crc ^ buf[i]) & 0xFF];
  return (crc ^ 0xFFFFFFFF) >>> 0;
}

function readEocd(buf) {
  let eocdOff = -1;
  const from = Math.max(0, buf.length - 22 - 65535);
  for (let i = buf.length - 22; i >= from; i -= 1) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocdOff = i; break; }
  }
  if (eocdOff < 0) throw new Error('找不到 EOCD（不是有效 zip）');
  const count = buf.readUInt16LE(eocdOff + 10);
  const cdOffset = buf.readUInt32LE(eocdOff + 16);
  if (count === 0xFFFF || cdOffset === 0xFFFFFFFF) throw new Error('不支持 zip64');
  return { cdOffset, count };
}

function listZip(buf) {
  const { cdOffset, count } = readEocd(buf);
  const out = [];
  let p = cdOffset;
  for (let n = 0; n < count; n += 1) {
    if (buf.readUInt32LE(p) !== 0x02014b50) throw new Error('中央目录损坏');
    const method = buf.readUInt16LE(p + 10);
    const crc = buf.readUInt32LE(p + 16);
    const compSize = buf.readUInt32LE(p + 20);
    const usize = buf.readUInt32LE(p + 24);
    const nameLen = buf.readUInt16LE(p + 28);
    const extraLen = buf.readUInt16LE(p + 30);
    const commentLen = buf.readUInt16LE(p + 32);
    const localOffset = buf.readUInt32LE(p + 42);
    const name = buf.toString('utf8', p + 46, p + 46 + nameLen);
    out.push({ name, method, crc, compSize, usize, localOffset });
    p += 46 + nameLen + extraLen + commentLen;
  }
  return out;
}

function readEntry(buf, entry) {
  const off = entry.localOffset;
  if (buf.readUInt32LE(off) !== 0x04034b50) throw new Error('局部头损坏: ' + entry.name);
  const lhNameLen = buf.readUInt16LE(off + 26);
  const lhExtraLen = buf.readUInt16LE(off + 28);
  const dataStart = off + 30 + lhNameLen + lhExtraLen;
  const raw = buf.subarray(dataStart, dataStart + entry.compSize);
  let data;
  if (entry.method === 0) data = Buffer.from(raw);
  else if (entry.method === 8) data = zlib.inflateRawSync(raw);
  else throw new Error('不支持的压缩方式 method=' + entry.method + ': ' + entry.name);
  if (crc32(data) !== entry.crc) throw new Error('CRC 校验失败: ' + entry.name);
  return data;
}

function checkZipIntegrity(buf) {
  const entries = listZip(buf);
  for (const e of entries) {
    const norm = path.posix.normalize(e.name);
    if (norm.startsWith('..') || path.posix.isAbsolute(norm)) {
      throw new Error('条目路径越界（疑似目录穿越）: ' + e.name);
    }
    if (e.name.endsWith('/')) continue;
    readEntry(buf, e);
  }
  return entries;
}

function canonical(obj) {
  const { signature, ...rest } = obj;
  return JSON.stringify(rest, Object.keys(rest).sort());
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  if (!args.zip) fail('bad-args', '缺少 --zip');
  if (!args.pubkey) fail('bad-args', '缺少 --pubkey');
  if (!fs.existsSync(args.zip)) fail('zip-missing', '文件不存在: ' + args.zip);
  if (!fs.existsSync(args.pubkey)) fail('pubkey-missing', '公钥不存在: ' + args.pubkey);

  const zipBuf = fs.readFileSync(args.zip);
  const actualSha = crypto.createHash('sha256').update(zipBuf).digest('hex');
  info('zip=' + args.zip + ' (' + zipBuf.length + ' 字节) sha256=' + actualSha);

  if (args.sha256) {
    if (actualSha !== args.sha256.toLowerCase()) {
      fail('sha256-mismatch',
        '期望 ' + args.sha256 + '，实际 ' + actualSha,
        { actualSha256: actualSha });
    }
    info('sha256 锚点匹配');
  } else {
    info('未提供 --sha256：跳过锚点比对（只做包内自校验）');
  }

  let entries;
  try {
    entries = checkZipIntegrity(zipBuf);
  } catch (e) {
    fail('zip-invalid', e.message);
  }
  info('zip 完整：' + entries.length + ' 个条目');

  const kjEntry = entries.find((e) => !e.name.endsWith('/') && e.name.endsWith('program-manifest.json'));
  if (!kjEntry) fail('no-program-manifest', '包内找不到 program-manifest.json');

  let manifestJson;
  try {
    manifestJson = JSON.parse(readEntry(zipBuf, kjEntry).toString('utf8'));
  } catch (e) {
    fail('program-manifest-bad', 'program-manifest.json 不可解析: ' + e.message);
  }

  const version = manifestJson.version;
  if (!version) fail('no-version', 'program-manifest.json 缺 version 字段');
  info('包内 version=' + version + '，路径=' + kjEntry.name);

  if (kjEntry.name !== 'program/' + version + '/program-manifest.json') {
    fail('path-version-mismatch',
      'program-manifest.json 位于 ' + kjEntry.name + '，但 version=' + version +
      '（约定为 program/<version>/program-manifest.json）');
  }

  if (args.version && args.version !== version) {
    fail('version-mismatch', '期望 version=' + args.version + '，包内=' + version);
  }

  const signature = manifestJson.signature;
  if (!signature) fail('signature-missing', 'program-manifest.json 无 signature 字段');

  let pubKeyPem;
  try {
    pubKeyPem = fs.readFileSync(args.pubkey, 'utf8');
  } catch (e) {
    fail('pubkey-unreadable', e.message);
  }

  if (args.manifest) {
    let mObj;
    try {
      mObj = JSON.parse(fs.readFileSync(args.manifest, 'utf8'));
    } catch (e) {
      fail('manifest-unreadable', '读不到/解析不了 manifest: ' + e.message);
    }
    if (!mObj.signature) fail('manifest-signature-missing', 'manifest 无 signature 字段');
    let mOk = false;
    try {
      mOk = crypto.verify(null, Buffer.from(canonical(mObj), 'utf8'), pubKeyPem,
        Buffer.from(mObj.signature, 'base64'));
    } catch (e) {
      fail('manifest-signature-error', e.message);
    }
    if (!mOk) fail('manifest-signature-invalid', 'manifest ed25519 验签未通过（签名与内容不匹配）');
    info('manifest 验签通过（sequence=' + (mObj.sequence || '?') + '，rollout=' + (mObj.rolloutPercent ?? '?') + '%）');
  }

  let sigOk = false;
  try {
    const data = Buffer.from(canonical(manifestJson), 'utf8');
    const sig = Buffer.from(signature, 'base64');
    if (sig.length !== 64) {
      fail('signature-bad-length', 'ed25519 签名应为 64 字节，实际 ' + sig.length);
    }
    sigOk = crypto.verify(null, data, pubKeyPem, sig);
  } catch (e) {
    fail('signature-verify-error', e.message);
  }
  if (!sigOk) {
    fail('signature-invalid',
      'ed25519 验签未通过（公钥 ' + args.pubkey + '）—— 包不是用配对私钥签的，或 program-manifest.json 被改过');
  }
  info('ed25519 验签通过');

  const reqProto = Number(manifestJson.requiresProtocol || 0);
  const shellProto = Number(args.shellProtocol || 0);
  if (reqProto > 0 && shellProto === 0) {
    fail('shell-protocol-missing',
      '内核声明 requiresProtocol=' + reqProto + '，但校验器未收到 --shell-protocol（调用方必须传入壳实现的协议版本）');
  }
  if (reqProto > shellProto) {
    fail('protocol-unsatisfied',
      '内核要求桥协议 v' + reqProto + '，本壳实现 v' + shellProto + ' —— 该内核包与本壳不兼容');
  }
  if (reqProto > 0) info('桥协议兼容：requires v' + reqProto + ' <= shell v' + shellProto);

  const entryRel = manifestJson.entry || 'bin/panel';
  const entryPath = 'program/' + version + '/' + entryRel;
  const hasEntry = entries.some((e) => e.name === entryPath && !e.name.endsWith('/'));
  if (!hasEntry) {
    info('警告：包内缺少入口 ' + entryPath);
  }

  emit({
    ok: true,
    version,
    reason: null,
    detail: 'sha256=' + actualSha + '，验签通过，' + entries.length + ' 条目',
    entryOk: hasEntry,
    actualSha256: actualSha,
  });
}

try {
  main();
} catch (e) {
  fail('verifier-crash', (e && e.stack) ? e.stack.split('\n').slice(0, 3).join(' | ') : String(e));
}
