#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const B64URL = (buf) => Buffer.from(buf).toString('base64').replace(/\+/g, '-').replace(/\//g, '_');

function uploadToken(ak, sk, bucket, key, ttlSec) {
  const policy = {
    scope: bucket + ':' + key,
    deadline: Math.floor(Date.now() / 1000) + (ttlSec || 3600),
    insertOnly: 0,
  };
  const encodedPolicy = B64URL(JSON.stringify(policy));
  const sign = crypto.createHmac('sha1', sk).update(encodedPolicy).digest();
  return ak + ':' + B64URL(sign) + ':' + encodedPolicy;
}

function multipart(fields, fileField, fileName, fileBuf, mime) {
  const B = '----lobos' + crypto.randomBytes(8).toString('hex');
  const parts = [];
  for (const [k, v] of Object.entries(fields)) {
    parts.push(Buffer.from('--' + B + '\r\nContent-Disposition: form-data; name="' + k + '"\r\n\r\n' + v + '\r\n'));
  }
  parts.push(Buffer.from('--' + B + '\r\nContent-Disposition: form-data; name="' + fileField +
    '"; filename="' + fileName + '"\r\nContent-Type: ' + (mime || 'application/octet-stream') + '\r\n\r\n'));
  parts.push(fileBuf);
  parts.push(Buffer.from('\r\n--' + B + '--\r\n'));
  return { body: Buffer.concat(parts), contentType: 'multipart/form-data; boundary=' + B };
}

// 缺陷名（C-102）**把整包读进内存** —— 而这一族它为什么有两边：
//   一边是「上传要那些字节」，
//   另一边是「那些字节**要多大**才拿得动」。
// Node 24 对单次 readFileSync 超过 2 GiB 直接抛 ERR_FS_FILE_TOO_LARGE，
// 而件（llvmtoolchain 那一颗 8414536031 字节）就是那种大小。
// 处置：分片上传本来就按块读文件（每次 4 MiB），所以**不需要**整包在内存里：
//   · 字节数取 stat；
//   · 每一块用 fs.openSync + readSync 现读现传；
//   · 回读校验用流式哈希（本地那份），远端那份照旧整块读（它是一个 HTTP 响应体，
//     不是文件 —— 那一格本来就只能整块拿，且它的大小受远端限制）。
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

// 从文件读一段（不把整份读进内存）
function readChunk(fd, pos, len) {
  const b = Buffer.allocUnsafe(len);
  let got = 0;
  while (got < len) {
    const n = fs.readSync(fd, b, got, len - got, pos + got);
    if (n <= 0) break;
    got += n;
  }
  return b.subarray(0, got);
}

async function main() {
  const argv = process.argv.slice(2);
  const local = argv[0];
  const key = argv[1];
  const cacheArg = argv.find((a) => a.startsWith('--cache-control='));
  const cacheSec = cacheArg ? Number(cacheArg.split('=')[1]) : null;

  const AK = process.env.QINIU_AK;
  const SK = process.env.QINIU_SK;
  const BUCKET = process.env.QINIU_BUCKET;
  const HOST = process.env.QINIU_UPLOAD_HOST || 'https://up.qiniup.com';
  const PUBLIC_BASE = (process.env.QINIU_PUBLIC_BASE || '').replace(/\/+$/, '');
  const READBACK_ATTEMPTS = Math.max(1, Number(process.env.QINIU_READBACK_ATTEMPTS) || 5);
  if (!local || !key) { console.error('用法: upload-qiniu.js <本地文件> <远端 key>'); process.exit(2); }
  if (!AK || !SK || !BUCKET) { console.error('[qiniu] 缺少 QINIU_AK / QINIU_SK / QINIU_BUCKET'); process.exit(2); }
  if (!PUBLIC_BASE) { console.error('[qiniu] 缺少 QINIU_PUBLIC_BASE —— 没有回读锚就不许声称投放生效'); process.exit(2); }
  if (!fs.existsSync(local)) { console.error('[qiniu] 文件不存在: ' + local); process.exit(2); }

  const bytes = fs.statSync(local).size;
  const B64URLSTR = (s) => B64URL(Buffer.from(s, 'utf8'));
  const ATTEMPTS = 3;
  const PER_ATTEMPT_MS = 900000;

  async function post(url, body, token) {
    const r = await fetch(url, {
      method: 'POST',
      headers: { 'Authorization': 'UpToken ' + token, 'Content-Type': 'application/octet-stream' },
      body: body,
      signal: AbortSignal.timeout(PER_ATTEMPT_MS),
    });
    const text = await r.text();
    if (r.status !== 200) throw new Error('status=' + r.status + ' ' + text.slice(0, 200));
    try { return JSON.parse(text); } catch (e) { throw new Error('响应不是 JSON: ' + text.slice(0, 120)); }
  }

  async function uploadResumable() {
    const BLOCK = 4 * 1024 * 1024;
    let host = HOST;
    let offset = 0;
    const ctxs = [];
    const fd = fs.openSync(local, 'r');
    try {
      while (offset < bytes) {
        const want = Math.min(BLOCK, bytes - offset);
        const chunk = readChunk(fd, offset, want);
        if (chunk.length === 0) throw new Error('读到文件末尾之前就断了（offset=' + offset + '）');
        const url = host + '/mkblk/' + chunk.length;
        let got = null, lastErr = null;
        for (let a = 1; a <= 3 && !got; a++) {
          try {
            const token = uploadToken(AK, SK, BUCKET, key, 3600);
            const r = await post(url, chunk, token);
            if (!r || !r.ctx) throw new Error('缺 ctx: ' + JSON.stringify(r).slice(0, 150));
            got = r;
          } catch (e) {
            lastErr = e;
            if (a < 3) { const w = 2000 * a; console.error('[qiniu] 块 ' + offset + ' 第 ' + a + '/3 次失败（' + e.message + '），' + w + 'ms 后重试'); await new Promise((res) => setTimeout(res, w)); }
          }
        }
        if (!got) throw new Error('块 ' + offset + ' 三次失败: ' + (lastErr && lastErr.message));
        ctxs.push(got.ctx);
        if (got.host) host = 'https://' + String(got.host).replace(/^https?:\/\//, '');
        offset += chunk.length;
        console.log('[qiniu] 块 ' + ctxs.length + ' 完成（' + offset + '/' + bytes + ' 字节）');
      }
    } finally {
      fs.closeSync(fd);
    }
    const cc = (cacheSec !== null && Number.isFinite(cacheSec)) ? '/x:Cache-Control/' + B64URLSTR('max-age=' + cacheSec) : '';
    const mk = host + '/mkfile/' + bytes + '/key/' + B64URLSTR(key) + cc;
    let done = null, lastErr2 = null;
    for (let a = 1; a <= 3 && !done; a++) {
      try {
        const token = uploadToken(AK, SK, BUCKET, key, 3600);
        done = await post(mk, Buffer.from(ctxs.join(','), 'utf8'), token);
      } catch (e) { lastErr2 = e; if (a < 3) await new Promise((res) => setTimeout(res, 2000 * a)); }
    }
    if (!done) throw new Error('mkfile 三次失败: ' + (lastErr2 && lastErr2.message));
    console.log('[qiniu] 已上传（分片）' + key + '（' + bytes + ' 字节，' + ctxs.length + ' 块）服务端: ' + JSON.stringify(done).slice(0, 200));
  }

  async function uploadForm() {
    // 这一支走整块表单上传，所以那一块**必须**在内存里 —— 而它只在文件不大于
    // 8 MiB 时才走（分界就在下面那一句），所以读得下。
    if (bytes > 8 * 1024 * 1024) {
      throw new Error('不该走到这一支：文件 ' + bytes + ' 字节 > 8 MiB（这一支整块在内存里）');
    }
    const buf = fs.readFileSync(local);
    let lastErr = null;
    for (let attempt = 1; attempt <= ATTEMPTS; attempt++) {
      const token = uploadToken(AK, SK, BUCKET, key, 3600);
      const fields = { key: key, token: token };
      if (cacheSec !== null && Number.isFinite(cacheSec)) fields['x:Cache-Control'] = 'max-age=' + cacheSec;
      const mp = multipart(fields, 'file', path.basename(local), buf);
      const t0 = Date.now();
      try {
        const r = await fetch(HOST, { method: 'POST', headers: { 'Content-Type': mp.contentType }, body: mp.body, signal: AbortSignal.timeout(PER_ATTEMPT_MS) });
        const text = await r.text();
        if (r.status === 200) { console.log('[qiniu] 已上传 ' + key + '（' + bytes + ' 字节，' + (Date.now() - t0) + 'ms，第 ' + attempt + ' 次尝试）服务端: ' + text.slice(0, 200)); return; }
        if (r.status >= 400 && r.status < 500) { console.error('[qiniu] 上传失败（4xx 不重试）key=' + key + ' status=' + r.status + ' body=' + text.slice(0, 300)); process.exit(1); }
        lastErr = new Error('status=' + r.status + ' body=' + text.slice(0, 200));
      } catch (e) { lastErr = e; }
      if (attempt < ATTEMPTS) { const wait = 3000 * Math.pow(2, attempt - 1); console.error('[qiniu] 第 ' + attempt + '/' + ATTEMPTS + ' 次失败（' + (lastErr && lastErr.message) + '），' + wait + 'ms 后重试'); await new Promise((res) => setTimeout(res, wait)); }
    }
    console.error('[qiniu] 连续 ' + ATTEMPTS + ' 次失败：key=' + key + ' last=' + (lastErr && lastErr.message));
    process.exit(1);
  }

  async function verifyPublic() {
    const url = PUBLIC_BASE + '/' + key.split('/').map(encodeURIComponent).join('/');
    const want = sha256File(local);
    let last = null;
    for (let a = 1; a <= READBACK_ATTEMPTS; a++) {
      let got = null;
      let gotBytes = 0;
      last = null;
      try {
        // 缺陷名（C-102）**回读那一格把响应体整块收进内存** ——
        // 而那一格原来写的是 `got = Buffer.from(await res.arrayBuffer());`。
        // 响应体就是那一份件（8.4 GB 那一颗）=> 这一格自己就把内存撑爆了
        // （实测报错逐字：`data is too long`）。
        // 而这一格要答的是「远端那份与本地那份是不是同一份字节」——
        // 那只需要**流式**哈希，不需要把远端那份留在内存里。
        const res = await fetch(url + '?t=' + Date.now(), {
          headers: { 'cache-control': 'no-cache' }, redirect: 'follow',
          signal: AbortSignal.timeout(180000),
        });
        if (res.status === 200) {
          const h = crypto.createHash('sha256');
          let n = 0;
          for await (const c of res.body) {
            h.update(c);
            n += c.length;
          }
          got = h.digest('hex');
          gotBytes = n;
        } else {
          last = new Error('HTTP ' + res.status + ' ' + (await res.text()).slice(0, 120));
        }
      } catch (e) { last = e; }
      if (got) {
        if (got === want) { console.log('[qiniu] 回读 [ok] ' + url + ' bytes=' + gotBytes + ' sha256=' + got.slice(0, 12) + '…'); return; }
        last = new Error('内容不符 bytes=' + gotBytes + '/' + bytes + ' sha256=' + got.slice(0, 12) + '…/' + want.slice(0, 12) + '…');
        break;
      }
      if (a < READBACK_ATTEMPTS) { const w = 5000 * a; console.error('[qiniu] 回读 ' + url + ' 第 ' + a + '/' + READBACK_ATTEMPTS + ' 次未取到（' + (last && last.message) + '），' + w + 'ms 后重试'); await new Promise((res) => setTimeout(res, w)); }
    }
    console.error('::error title=投放未生效::' + key + ' 回读失败：' + (last && last.message) + ' —— 上传应答只证明七牛收了，不证明设备读得到。');
    process.exit(1);
  }

  if (bytes > 8 * 1024 * 1024) await uploadResumable();
  else await uploadForm();
  await verifyPublic();
}

main().catch((e) => { console.error('[qiniu] FATAL ' + (e && e.message)); process.exit(1); });
