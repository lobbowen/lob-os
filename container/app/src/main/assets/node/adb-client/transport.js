'use strict';

const net = require('node:net');
const tls = require('node:tls');
const crypto = require('node:crypto');
const x509 = require('./x509');

const VERSION = 0x01000000;
const STLS_VERSION = 0x01000000;
const MAX_PAYLOAD = 256 * 1024;
const HEADER_SIZE = 24;

const RECONNECT_BASE_MS = 500;
const RECONNECT_MAX_MS = 8000;
const CONNECT_TIMEOUT_MS = 8000;
const CLOSE_GRACE_MS = 600;
const LOG_CAP = 200;

function checksum(p) { let s = 0; for (const b of p) s = (s + b) >>> 0; return s >>> 0; }
function commandInt(cmd) {
  return cmd.charCodeAt(0) | (cmd.charCodeAt(1) << 8) | (cmd.charCodeAt(2) << 16) | (cmd.charCodeAt(3) << 24);
}
function makePacket(cmd, a0, a1, payload) {
  payload = payload || Buffer.alloc(0);
  const h = Buffer.alloc(HEADER_SIZE);
  h.write(cmd, 0, 'ascii');
  h.writeUInt32LE(a0 >>> 0, 4);
  h.writeUInt32LE(a1 >>> 0, 8);
  h.writeUInt32LE(payload.length, 12);
  h.writeUInt32LE(checksum(payload), 16);
  h.writeUInt32LE((commandInt(cmd) ^ 0xffffffff) >>> 0, 20);
  return Buffer.concat([h, payload]);
}

function fingerprint(o) {
  const pem = o && o.key && o.key.privatePem;
  if (!pem) return 'nokey';
  return crypto.createHash('sha1').update(pem).digest('hex').slice(0, 16);
}
function keyOf(o) { return o.host + ':' + o.port; }

const pool = new Map();

class AdbConnection {
  constructor(o) {
    this.host = o.host;
    this.port = o.port;
    this.key = o.key;
    this.pemFingerprint = fingerprint(o);
    this.connectTimeoutMs = o.connectTimeoutMs || CONNECT_TIMEOUT_MS;
    this.socket = null;
    this.raw = null;
    this.buf = Buffer.alloc(0);
    this.ready = false;
    this.connecting = null;
    this.tlsStarted = false;
    this.handshakeDone = false;
    this.nextLocalId = 1;
    this.pending = new Map();
    this.closeWaiters = new Map();
    this.logs = [];
    this.backoffMs = 0;
    this.nextAttemptAt = 0;
    this.down = true;
    this.downHandled = false;
    this.dialTimer = null;
    this.dialResolve = null;
    this.dialReject = null;
    this.intentionalClose = false;
  }

  log(m) {
    this.logs.push(m);
    if (this.logs.length > LOG_CAP) this.logs.splice(0, this.logs.length - LOG_CAP);
  }

  snapshotLogs(extra) { return this.logs.concat(extra || []); }

  ensureConnected(deadlineMs) {
    if (this.ready && !this.down) return Promise.resolve();
    if (this.connecting) return this.connecting;
    const now = Date.now();
    const waitMs = Math.max(0, this.nextAttemptAt - now);
    if (deadlineMs && now + waitMs >= deadlineMs) {
      return Promise.reject(new Error('adb 通道处于重连退避中（' + waitMs + 'ms 后重试）'));
    }
    const run = () => this._dial();
    const p = waitMs > 0 ? new Promise((r) => setTimeout(r, waitMs)).then(run) : run();
    this.connecting = p;
    const clear = () => { if (this.connecting === p) this.connecting = null; };
    p.then(clear, clear);
    return p;
  }

  _dial() {
    return new Promise((resolve, reject) => {
      this.down = false;
      this.downHandled = false;
      this.ready = false;
      this.tlsStarted = false;
      this.handshakeDone = false;
      this.intentionalClose = false;
      this.buf = Buffer.alloc(0);
      let settled = false;
      const clearTimer = () => { if (this.dialTimer) { clearTimeout(this.dialTimer); this.dialTimer = null; } };
      const finishOk = () => {
        if (settled) return; settled = true; clearTimer();
        this.ready = true; this.down = false;
        this.backoffMs = 0; this.nextAttemptAt = 0;
        this.dialResolve = null;
        this.dialReject = null;
        resolve();
      };
      const finishErr = (e) => {
        if (settled) return; settled = true; clearTimer();
        this.dialResolve = null;
        this.dialReject = null;
        this._socketDown(e);
        reject(e);
      };
      this.dialResolve = finishOk;
      this.dialReject = finishErr;

      let raw;
      try { raw = net.connect({ host: this.host, port: this.port }); }
      catch (e) { finishErr(e); return; }
      this.raw = raw;
      this.socket = raw;
      this.log('connecting ' + this.host + ':' + this.port);
      this.dialTimer = setTimeout(
        () => finishErr(new Error('adb 连接超时（' + this.connectTimeoutMs + 'ms）@' + this.host + ':' + this.port)),
        this.connectTimeoutMs,
      );
      const onError = (e) => { if (this.ready) this._socketDown(e); else finishErr(e); };
      raw.on('connect', () => {
        try { raw.write(makePacket('CNXN', VERSION, MAX_PAYLOAD, Buffer.from('host::\u0000', 'utf8'))); this.log('sent CNXN'); }
        catch (e) { finishErr(e); }
      });
      raw.on('data', (d) => this._feed(d));
      raw.on('error', onError);
      raw.on('close', () => { if (!this.ready) finishErr(new Error('adb 连接在握手完成前被关闭')); });
    });
  }

  _feed(d) {
    this.buf = Buffer.concat([this.buf, d]);
    while (this.buf.length >= HEADER_SIZE) {
      const cmd = this.buf.toString('ascii', 0, 4);
      const a0 = this.buf.readUInt32LE(4);
      const a1 = this.buf.readUInt32LE(8);
      const dataLen = this.buf.readUInt32LE(12);
      if (this.buf.length < HEADER_SIZE + dataLen) break;
      const payload = Buffer.from(this.buf.subarray(HEADER_SIZE, HEADER_SIZE + dataLen));
      this.buf = this.buf.subarray(HEADER_SIZE + dataLen);
      try { this._onPacket(cmd, a0, a1, payload); }
      catch (e) { this.log('packet error: ' + e.message); }
    }
  }

  _onPacket(cmd, a0, a1, payload) {
    if (cmd === 'STLS') { this._upgradeTls(); return; }
    if (cmd === 'CNXN') {
      if (this.handshakeDone) return;
      this.handshakeDone = true;
      this.log('CNXN banner=' + payload.toString('utf8').replace(/\u0000/g, ''));
      const done = this.dialResolve;
      if (done) done();
      return;
    }
    if (cmd === 'OKAY') {
      const entry = this.pending.get(a1);
      if (entry) entry.remoteId = a0;
      const waiter = this.closeWaiters.get(a1);
      if (waiter) { this.closeWaiters.delete(a1); waiter(); }
      return;
    }
    if (cmd === 'WRTE') {
      const entry = this.pending.get(a1);
      if (entry) {
        entry.remoteId = a0;
        entry.out = Buffer.concat([entry.out, payload]);
        entry.logs.push('WRTE len=' + payload.length);
      }
      this._send(makePacket('OKAY', a1, a0, Buffer.alloc(0)));
      return;
    }
    if (cmd === 'CLSE') {
      const entry = this.pending.get(a1);
      if (entry) {
        this.pending.delete(a1);
        entry.remoteId = a0;
        entry.ok();
      }
      return;
    }
  }

  _upgradeTls() {
    if (this.tlsStarted) return;
    this.tlsStarted = true;
    this._send(makePacket('STLS', STLS_VERSION, 0, Buffer.alloc(0)));
    const raw = this.raw;
    const leftover = this.buf;
    this.buf = Buffer.alloc(0);
    if (raw) raw.removeAllListeners('data');
    if (leftover && leftover.length) this.log('TLS 升级前缓冲残余 ' + leftover.length + 'B（按既有实现丢弃）');
    const cred = x509.selfSignedFromKey('adb', this.key.privatePem);
    const t = tls.connect({
      socket: raw, key: cred.keyPem, cert: cred.certPem,
      rejectUnauthorized: false, minVersion: 'TLSv1.3', maxVersion: 'TLSv1.3',
    });
    this.socket = t;
    const onError = (e) => {
      if (this.ready) { this._socketDown(e); return; }
      const r = this.dialReject;
      if (r) r(e);
      else this._socketDown(e);
    };
    t.on('secureConnect', () => { this.log('TLS established'); t.on('data', (d) => this._feed(d)); });
    t.on('error', onError);
    t.on('close', () => { if (this.ready && !this.intentionalClose) this._socketDown(new Error('TLS 连接关闭')); });
  }

  _send(pkt) {
    const s = this.socket;
    if (!s || s.destroyed) return;
    try { s.write(pkt); } catch (e) { this._socketDown(e); }
  }

  _socketDown(err) {
    if (this.downHandled) return;
    this.downHandled = true;
    const wasReady = this.ready;
    this.ready = false;
    this.down = true;
    const s = this.socket, r = this.raw;
    this.socket = null;
    this.raw = null;
    try { if (s) s.destroy(); } catch (e) {   }
    try { if (r && r !== s) r.destroy(); } catch (e) {   }
    const e = err || new Error('adb 连接断开');
    for (const [, entry] of this.pending) {
      try { entry.fail(e); } catch (x) {   }
    }
    this.pending.clear();
    this.closeWaiters.clear();
    if (!this.intentionalClose) {
      this.backoffMs = this.backoffMs > 0 ? Math.min(this.backoffMs * 2, RECONNECT_MAX_MS) : RECONNECT_BASE_MS;
      this.nextAttemptAt = Date.now() + this.backoffMs;
    }
    if (wasReady) this.log('connection down: ' + e.message);
  }

  shell(cmd, timeoutMs) {
    const t = timeoutMs || 15000;
    const deadline = Date.now() + t + this.connectTimeoutMs;
    return this.ensureConnected(deadline).then(() => new Promise((resolve, reject) => {
      if (!this.ready || !this.socket) { reject(new Error('adb 连接不可用')); return; }
      const localId = this.nextLocalId++;
      const entry = { out: Buffer.alloc(0), remoteId: 0, logs: [], settled: false, timer: null };
      entry.ok = () => {
        if (entry.settled) return; entry.settled = true;
        clearTimeout(entry.timer);
        resolve({ out: entry.out.toString('utf8'), logs: this.snapshotLogs(entry.logs) });
      };
      entry.fail = (e) => {
        if (entry.settled) return; entry.settled = true;
        clearTimeout(entry.timer);
        this.pending.delete(localId);
        reject(e);
      };
      entry.timer = setTimeout(() => {
        if (entry.remoteId) this._send(makePacket('CLSE', localId, entry.remoteId, Buffer.alloc(0)));
        entry.fail(new Error('adb shell 超时（' + t + 'ms），输出不完整'));
      }, t);
      this.pending.set(localId, entry);
      entry.logs.push('sent OPEN shell:' + cmd);
      this._send(makePacket('OPEN', localId, 0, Buffer.from('shell:' + cmd + '\u0000', 'utf8')));
    }));
  }

  close(gracefulMs) {
    const grace = gracefulMs == null ? CLOSE_GRACE_MS : gracefulMs;
    this.intentionalClose = true;
    const ids = [...this.pending.keys()];
    if (!ids.length || !this.socket) {
      this.downHandled = false;
      this._socketDown(new Error('adb 连接已关闭'));
      return Promise.resolve();
    }
    const waits = ids.map((id) => new Promise((res) => {
      this.closeWaiters.set(id, res);
      const entry = this.pending.get(id);
      this._send(makePacket('CLSE', id, (entry && entry.remoteId) || 0, Buffer.alloc(0)));
    }));
    return Promise.race([
      Promise.all(waits),
      new Promise((res) => setTimeout(res, grace)),
    ]).then(() => {
      this.downHandled = false;
      this._socketDown(new Error('adb 连接已关闭'));
    });
  }
}

function readyEndpoint() {
  for (const conn of pool.values()) {
    if (conn.ready && !conn.down) return { host: conn.host, port: conn.port };
  }
  return null;
}

function connectionFor(o) {
  const k = keyOf(o);
  const fp = fingerprint(o);
  let conn = pool.get(k);
  if (conn && conn.pemFingerprint !== fp) {
    pool.delete(k);
    conn.close().catch(() => {});
    conn = null;
  }
  if (!conn) { conn = new AdbConnection(o); pool.set(k, conn); }
  return conn;
}

function shell(o) {
  return connectionFor(o).shell(o.cmd, o.timeoutMs);
}

async function shellOnce(o) {
  const conn = new AdbConnection(o);
  try {
    await conn.ensureConnected(Date.now() + (o.timeoutMs || 15000) + (o.connectTimeoutMs || CONNECT_TIMEOUT_MS));
    return await conn.shell(o.cmd, o.timeoutMs);
  } finally {
    await conn.close().catch(() => {});
  }
}

async function closeAll() {
  const conns = [...pool.values()];
  pool.clear();
  await Promise.all(conns.map((c) => c.close().catch(() => {})));
}

module.exports = {
  shell, shellOnce, closeAll, readyEndpoint,
  makePacket, checksum, commandInt, VERSION, MAX_PAYLOAD, HEADER_SIZE,
};
