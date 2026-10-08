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

  const buf = fs.readFileSync(local);
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
    while (offset < buf.length) {
      const chunk = buf.subarray(offset, Math.min(offset + BLOCK, buf.length));
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
      console.log('[qiniu] 块 ' + ctxs.length + ' 完成（' + offset + '/' + buf.length + ' 字节）');
    }
    const cc = (cacheSec !== null && Number.isFinite(cacheSec)) ? '/x:Cache-Control/' + B64URLSTR('max-age=' + cacheSec) : '';
    const mk = host + '/mkfile/' + buf.length + '/key/' + B64URLSTR(key) + cc;
    let done = null, lastErr2 = null;
    for (let a = 1; a <= 3 && !done; a++) {
      try {
        const token = uploadToken(AK, SK, BUCKET, key, 3600);
        done = await post(mk, Buffer.from(ctxs.join(','), 'utf8'), token);
      } catch (e) { lastErr2 = e; if (a < 3) await new Promise((res) => setTimeout(res, 2000 * a)); }
    }
    if (!done) throw new Error('mkfile 三次失败: ' + (lastErr2 && lastErr2.message));
    console.log('[qiniu] 已上传（分片）' + key + '（' + buf.length + ' 字节，' + ctxs.length + ' 块）服务端: ' + JSON.stringify(done).slice(0, 200));
  }

  async function uploadForm() {
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
        if (r.status === 200) { console.log('[qiniu] 已上传 ' + key + '（' + buf.length + ' 字节，' + (Date.now() - t0) + 'ms，第 ' + attempt + ' 次尝试）服务端: ' + text.slice(0, 200)); return; }
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
    const want = crypto.createHash('sha256').update(buf).digest('hex');
    let last = null;
    for (let a = 1; a <= READBACK_ATTEMPTS; a++) {
      let got = null;
      last = null;
      try {
        const res = await fetch(url + '?t=' + Date.now(), {
          headers: { 'cache-control': 'no-cache' }, redirect: 'follow',
          signal: AbortSignal.timeout(180000),
        });
        if (res.status === 200) got = Buffer.from(await res.arrayBuffer());
        else last = new Error('HTTP ' + res.status + ' ' + (await res.text()).slice(0, 120));
      } catch (e) { last = e; }
      if (got) {
        const have = crypto.createHash('sha256').update(got).digest('hex');
        if (have === want) { console.log('[qiniu] 回读 [ok] ' + url + ' bytes=' + got.length + ' sha256=' + have.slice(0, 12) + '…'); return; }
        last = new Error('内容不符 bytes=' + got.length + '/' + buf.length + ' sha256=' + have.slice(0, 12) + '…/' + want.slice(0, 12) + '…');
        break;
      }
      if (a < READBACK_ATTEMPTS) { const w = 5000 * a; console.error('[qiniu] 回读 ' + url + ' 第 ' + a + '/' + READBACK_ATTEMPTS + ' 次未取到（' + (last && last.message) + '），' + w + 'ms 后重试'); await new Promise((res) => setTimeout(res, w)); }
    }
    console.error('::error title=投放未生效::' + key + ' 回读失败：' + (last && last.message) + ' —— 上传应答只证明七牛收了，不证明设备读得到。');
    process.exit(1);
  }

  if (buf.length > 8 * 1024 * 1024) await uploadResumable();
  else await uploadForm();
  await verifyPublic();
}

main().catch((e) => { console.error('[qiniu] FATAL ' + (e && e.message)); process.exit(1); });
