'use strict';

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const METHOD_STORED = 0;
const METHOD_DEFLATE = 8;

const ZIP_BUDGET = {
  entryInflated: 64 * 1024 * 1024,
  totalInflated: 128 * 1024 * 1024,
  entries: 5000,
};

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

function createZip(files, opts) {
  const compress = !!(opts && opts.compress);
  const chunks = [];
  const central = [];
  let offset = 0;
  for (const [name, data] of files) {
    const nameBuf = Buffer.from(name, 'utf8');
    const crc = crc32(data);
    let method = METHOD_STORED;
    let payload = data;
    if (compress && data.length > 512) {
      const def = zlib.deflateRawSync(data, { level: 6 });
      if (def.length < data.length) { method = METHOD_DEFLATE; payload = def; }
    }
    if (name.endsWith('/')) { method = METHOD_STORED; payload = Buffer.alloc(0); }

    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(method === METHOD_DEFLATE ? 20 : 20, 4);
    local.writeUInt16LE(0, 6);
    local.writeUInt16LE(method, 8);
    local.writeUInt16LE(0, 10);
    local.writeUInt16LE(0, 12);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(payload.length, 18);
    local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(nameBuf.length, 26);
    local.writeUInt16LE(0, 28);
    chunks.push(local, nameBuf, payload);

    const cen = Buffer.alloc(46);
    cen.writeUInt32LE(0x02014b50, 0);
    cen.writeUInt16LE(20, 4);
    cen.writeUInt16LE(20, 6);
    cen.writeUInt16LE(0, 8);
    cen.writeUInt16LE(method, 10);
    cen.writeUInt16LE(0, 12);
    cen.writeUInt16LE(0, 14);
    cen.writeUInt32LE(crc, 16);
    cen.writeUInt32LE(payload.length, 20);
    cen.writeUInt32LE(data.length, 24);
    cen.writeUInt16LE(nameBuf.length, 28);
    cen.writeUInt16LE(0, 30);
    cen.writeUInt16LE(0, 32);
    cen.writeUInt16LE(0, 34);
    cen.writeUInt16LE(0, 36);
    cen.writeUInt32LE(0, 38);
    cen.writeUInt32LE(offset, 42);
    central.push(Buffer.concat([cen, nameBuf]));
    offset += local.length + nameBuf.length + payload.length;
  }
  const centralBuf = Buffer.concat(central);
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(0, 4);
  eocd.writeUInt16LE(0, 6);
  eocd.writeUInt16LE(files.size, 8);
  eocd.writeUInt16LE(files.size, 10);
  eocd.writeUInt32LE(centralBuf.length, 12);
  eocd.writeUInt32LE(offset, 16);
  eocd.writeUInt16LE(0, 20);
  return Buffer.concat([...chunks, centralBuf, eocd]);
}

function _readEocd(buf) {
  let eocdOff = -1;
  const from = Math.max(0, buf.length - 22 - 65535);
  for (let i = buf.length - 22; i >= from; i -= 1) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocdOff = i; break; }
  }
  if (eocdOff < 0) throw new Error('无效的 zip：找不到 EOCD');
  const count = buf.readUInt16LE(eocdOff + 10);
  const cdOffset = buf.readUInt32LE(eocdOff + 16);
  if (count === 0xFFFF || cdOffset === 0xFFFFFFFF) {
    throw new Error('不支持 zip64 格式的程序包（条目数或中央目录偏移溢出 32 位）');
  }
  return { cdOffset, count };
}

function extractZip(buf, destDir) {
  const { cdOffset, count } = _readEocd(buf);
  if (count > ZIP_BUDGET.entries) throw new Error('zip 条目数 ' + count + ' 超上限 ' + ZIP_BUDGET.entries + '（疑似恶意包）');
  const names = [];
  let inflatedTotal = 0;
  let p = cdOffset;
  for (let n = 0; n < count; n += 1) {
    if (buf.readUInt32LE(p) !== 0x02014b50) throw new Error('无效的 zip：中央目录损坏');
    const method = buf.readUInt16LE(p + 10);
    const crc = buf.readUInt32LE(p + 16);
    const compSize = buf.readUInt32LE(p + 20);
    const uncompSize = buf.readUInt32LE(p + 24);
    const nameLen = buf.readUInt16LE(p + 28);
    const extraLen = buf.readUInt16LE(p + 30);
    const commentLen = buf.readUInt16LE(p + 32);
    const localOffset = buf.readUInt32LE(p + 42);
    const name = buf.toString('utf8', p + 46, p + 46 + nameLen);

    if (name.length > 512) throw new Error('zip 条目名过长（' + name.length + ' 字节）: ' + name.slice(0, 64));
    if (name.startsWith('/') || /^[a-zA-Z]:[\\/]/.test(name)) throw new Error('zip 条目名为绝对路径: ' + name);

    const lhNameLen = buf.readUInt16LE(localOffset + 26);
    const lhExtraLen = buf.readUInt16LE(localOffset + 28);
    const dataStart = localOffset + 30 + lhNameLen + lhExtraLen;
    const raw = buf.subarray(dataStart, dataStart + compSize);

    names.push(name);

    if (name.endsWith('/')) {
      const dirOut = path.join(destDir, name);
      const dirRel = path.relative(path.resolve(destDir), path.resolve(dirOut));
      if (dirRel.startsWith('..') || path.isAbsolute(dirRel)) {
        throw new Error('zip 目录条目路径越界（疑似目录穿越攻击）: ' + name);
      }
      fs.mkdirSync(dirOut, { recursive: true });
      p += 46 + nameLen + extraLen + commentLen;
      continue;
    }

    if (uncompSize > ZIP_BUDGET.entryInflated) {
      throw new Error('zip 条目声明解压超 ' + ZIP_BUDGET.entryInflated + ' 字节（疑似 zip 炸弹）: ' + name);
    }
    let data;
    if (method === METHOD_STORED) {
      data = Buffer.from(raw);
    } else if (method === METHOD_DEFLATE) {
      try {
        data = zlib.inflateRawSync(raw, { maxOutputLength: ZIP_BUDGET.entryInflated });
      } catch (e) {
        throw new Error('zip 条目 Deflate 解压失败: ' + name + ' (' + e.message + ')');
      }
    } else {
      throw new Error(
        'zip 条目使用了不支持的压缩方式 method=' + method + '（只支持 0=Stored / 8=Deflate）: ' + name
      );
    }
    inflatedTotal += data.length;
    if (inflatedTotal > ZIP_BUDGET.totalInflated) {
      throw new Error('zip 累计解压超预算 ' + ZIP_BUDGET.totalInflated + ' 字节（疑似 zip 炸弹）: ' + name);
    }

    const out = path.join(destDir, name);
    const rel = path.relative(path.resolve(destDir), path.resolve(out));
    if (rel.startsWith('..') || path.isAbsolute(rel)) {
      throw new Error('zip 条目路径越界（疑似目录穿越攻击）: ' + name);
    }
    if (crc32(data) !== crc) throw new Error('zip 条目 CRC 校验失败: ' + name);
    fs.mkdirSync(path.dirname(out), { recursive: true });
    fs.writeFileSync(out, data);

    p += 46 + nameLen + extraLen + commentLen;
  }
  return { entries: names.length, names };
}

function listZip(buf) {
  const { cdOffset, count } = _readEocd(buf);
  const out = [];
  let p = cdOffset;
  for (let n = 0; n < count; n += 1) {
    if (buf.readUInt32LE(p) !== 0x02014b50) throw new Error('无效的 zip：中央目录损坏');
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

function readEntry(buf, wantName) {
  const { cdOffset, count } = _readEocd(buf);
  let p = cdOffset;
  for (let n = 0; n < count; n += 1) {
    if (buf.readUInt32LE(p) !== 0x02014b50) throw new Error('无效的 zip：中央目录损坏');
    const method = buf.readUInt16LE(p + 10);
    const crc = buf.readUInt32LE(p + 16);
    const compSize = buf.readUInt32LE(p + 20);
    const nameLen = buf.readUInt16LE(p + 28);
    const extraLen = buf.readUInt16LE(p + 30);
    const commentLen = buf.readUInt16LE(p + 32);
    const localOffset = buf.readUInt32LE(p + 42);
    const name = buf.toString('utf8', p + 46, p + 46 + nameLen);
    if (name === wantName) {
      const lhNameLen = buf.readUInt16LE(localOffset + 26);
      const lhExtraLen = buf.readUInt16LE(localOffset + 28);
      const dataStart = localOffset + 30 + lhNameLen + lhExtraLen;
      const raw = buf.subarray(dataStart, dataStart + compSize);
      let data;
      if (method === METHOD_STORED) data = Buffer.from(raw);
      else if (method === METHOD_DEFLATE) data = zlib.inflateRawSync(raw);
      else throw new Error('不支持的压缩方式 method=' + method + ': ' + name);
      if (crc32(data) !== crc) throw new Error('zip 条目 CRC 校验失败: ' + name);
      return data;
    }
    p += 46 + nameLen + extraLen + commentLen;
  }
  return null;
}

module.exports = {
  createZip, extractZip, crc32, listZip, readEntry,
  METHOD_STORED, METHOD_DEFLATE,
  ZIP_BUDGET,
};
