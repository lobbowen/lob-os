'use strict';

const fs = require('node:fs');
const path = require('node:path');
const adbkey = require('./adbkey');
const pairing = require('./pairing');
const transport = require('./transport');

const KEY_FILE = 'adbkey.pem';
const NAME_FILE = 'adbkey.name';
const STATE_FILE = 'state.json';

function dir() {
  const d = process.env.LOBOS_ADB_DIR;
  if (!d) throw new Error('LOBOS_ADB_DIR 未注入（adb-client 必须由容器指定凭据目录）');
  return d;
}
function keyPath() { return path.join(dir(), KEY_FILE); }
function namePath() { return path.join(dir(), NAME_FILE); }
function statePath() { return path.join(dir(), STATE_FILE); }
function ensureDir() { fs.mkdirSync(dir(), { recursive: true, mode: 0o700 }); }

function applyStoredName(key, fallback) {
  try {
    if (fs.existsSync(namePath())) { key.name = fs.readFileSync(namePath(), 'utf8').trim() || fallback; return key; }
    key.name = fallback;
    fs.writeFileSync(namePath(), key.name, { mode: 0o600 });
  } catch (e) {   }
  return key;
}

function readKey() {
  if (!fs.existsSync(keyPath())) return null;
  return applyStoredName(adbkey.loadOrCreate(keyPath()), 'lobos@device');
}

function ensureKey(name) {
  ensureDir();
  return applyStoredName(adbkey.loadOrCreate(keyPath(), name || 'lobos@device'), name || 'lobos@device');
}

function readState() { try { return JSON.parse(fs.readFileSync(statePath(), 'utf8')); } catch (e) { return null; } }
function writeState(s) { ensureDir(); fs.writeFileSync(statePath(), JSON.stringify(s, null, 2), { mode: 0o600 }); }
function forget() { try { fs.unlinkSync(statePath()); } catch (e) {   } }

function status() {
  const st = readState();
  let pubkey = null;
  const key = readKey();
  if (key) pubkey = adbkey.pubkeyString(key);
  return {
    keyPath: keyPath(),
    pubkey: pubkey,
    paired: !!st,
    host: (st && st.host) || null,
    connectPort: (st && st.connectPort) || null,
    guid: (st && st.guid) || null,
    name: (st && st.name) || null,
    pairedAt: (st && st.pairedAt) || null,
  };
}

async function pair(o) {
  const key = ensureKey(o && o.name);
  const r = await pairing.pair({ host: o.host, port: o.pairPort, code: o.code, key: key, timeoutMs: o.timeoutMs });
  if (o.connectPort) {
    writeState({ host: o.host, connectPort: o.connectPort, guid: r.guid, name: key.name, pairedAt: new Date().toISOString() });
  }
  return { guid: r.guid, type: r.type };
}

async function shell(o) {
  let host = o.host;
  let port = o.connectPort;
  if (!host && !port) {
    const live = transport.readyEndpoint();
    if (live) { host = live.host; port = live.port; }
  }
  if (!host || !port) {
    const st = readState();
    host = host || (st && st.host);
    port = port || (st && st.connectPort);
  }
  if (!host || !port) throw new Error('未配对或缺少连接地址（host/connectPort）');
  return transport.shell({ host: host, port: port, key: ensureKey(), cmd: o.cmd, timeoutMs: o.timeoutMs });
}

function channel() {
  const ep = transport.readyEndpoint();
  return { ok: true, ready: !!ep, host: ep ? ep.host : null, port: ep ? ep.port : null };
}

function closeAll() { return transport.closeAll(); }

module.exports = {
  keyPath, namePath, statePath, status, pair, shell, forget, channel, closeAll,
};
