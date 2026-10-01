'use strict';

const fs = require('node:fs');
const path = require('node:path');
const readline = require('node:readline');
const adb = require('./index');

const RESULT_PREFIX = 'LOBOS_ADB_RESULT ';

let serveMode = false;
function logInfo(message) {
  (serveMode ? process.stderr : process.stdout).write(message + '\n');
}

function parseArgs(argv) {
  const out = { _: [] };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a.startsWith('--')) { out[a.slice(2)] = argv[++i]; }
    else out._.push(a);
  }
  return out;
}

function migrateFrom(srcDir) {
  try {
    if (!srcDir || !fs.existsSync(path.join(srcDir, 'adbkey.pem'))) return;
    if (fs.existsSync(adb.keyPath())) return;
    fs.mkdirSync(adb.dir(), { recursive: true, mode: 0o700 });
    fs.copyFileSync(path.join(srcDir, 'adbkey.pem'), adb.keyPath());
    fs.chmodSync(adb.keyPath(), 0o600);
    for (const [src, dst] of [['adbkey.name', adb.namePath()], ['state.json', adb.statePath()]]) {
      const s = path.join(srcDir, src);
      if (fs.existsSync(s)) fs.copyFileSync(s, dst);
    }
    logInfo('migrated adb credentials from ' + srcDir);
  } catch (e) {
    logInfo('migrate skipped: ' + e.message);
  }
}

async function dispatch(method, params) {
  const p = params || {};
  switch (method) {
    case 'status':
      return { ok: true, status: adb.status() };
    case 'forget':
      adb.forget();
      return { ok: true };
    case 'pair': {
      if (!p.host || !p.pairPort || !p.code) throw new Error('pair 需要 host/pairPort/code');
      const r = await adb.pair({
        host: p.host, pairPort: Number(p.pairPort), code: String(p.code),
        connectPort: p.connectPort ? Number(p.connectPort) : undefined,
        timeoutMs: p.timeoutMs,
      });
      return { ok: true, guid: r.guid, status: adb.status() };
    }
    case 'shell': {
      if (p.cmd === undefined) throw new Error('shell 需要 cmd');
      const r = await adb.shell({
        cmd: p.cmd, host: p.host,
        connectPort: p.connectPort ? Number(p.connectPort) : undefined,
        timeoutMs: p.timeoutMs,
      });
      return { ok: true, out: r.out, logs: r.logs };
    }
    case 'channel':
      return adb.channel();
    case 'shutdown':
      return { ok: true };
    default:
      throw new Error('未知方法: ' + String(method) + '（可用：status/pair/shell/forget/channel/shutdown）');
  }
}

function paramsFromArgs(args, timeoutMs) {
  const sub = args._[0];
  const p = {};
  if (sub === 'pair') {
    p.host = args.host;
    p.pairPort = args['pair-port'];
    p.code = args.code;
    if (args['connect-port']) p.connectPort = Number(args['connect-port']);
    p.timeoutMs = timeoutMs;
  } else if (sub === 'shell') {
    p.cmd = args.cmd;
    p.host = args.host;
    if (args['connect-port']) p.connectPort = Number(args['connect-port']);
    p.timeoutMs = timeoutMs;
  }
  return p;
}

function writeFrame(obj) {
  process.stdout.write(JSON.stringify(obj) + '\n');
}

async function serve() {
  const rl = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });
  const inFlight = new Set();

  rl.on('line', (line) => {
    const text = line.trim();
    if (!text) return;
    let req;
    try { req = JSON.parse(text); }
    catch (e) {
      writeFrame({ jsonrpc: '2.0', id: null, error: { code: -32700, message: 'JSON 解析失败: ' + e.message } });
      return;
    }
    const id = (req && req.id !== undefined) ? req.id : null;
    if (!req || typeof req.method !== 'string') {
      writeFrame({ jsonrpc: '2.0', id, error: { code: -32600, message: '缺 method' } });
      return;
    }
    const task = Promise.resolve()
      .then(() => dispatch(req.method, req.params))
      .then((result) => writeFrame({ jsonrpc: '2.0', id, result }))
      .catch((err) => writeFrame({
        jsonrpc: '2.0', id, error: { code: -32000, message: String((err && err.message) || err) },
      }));
    inFlight.add(task);
    task.then(() => inFlight.delete(task), () => inFlight.delete(task));
    if (req.method === 'shutdown') rl.close();
  });

  await new Promise((resolve) => rl.on('close', resolve));
  await Promise.allSettled([...inFlight]);
  await adb.closeAll().catch(() => {});
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const sub = args._[0];
  serveMode = sub === 'serve';
  migrateFrom(args['migrate-from']);
  const timeoutMs = args['timeout-ms'] ? Number(args['timeout-ms']) : undefined;

  if (sub === 'serve') { await serve(); return { __serve: true }; }
  if (!sub) throw new Error('缺子命令（可用：status/pair/shell/forget/serve）');
  return await dispatch(sub, paramsFromArgs(args, timeoutMs));
}

main()
  .then(async (res) => {
    if (res && res.__serve) return;
    await adb.closeAll().catch(() => {});
    process.stdout.write(RESULT_PREFIX + JSON.stringify(res) + '\n');
    process.exitCode = 0;
  })
  .catch(async (err) => {
    if (serveMode) {
      process.stderr.write('adb-client serve 启动失败: ' + String((err && err.message) || err) + '\n');
      process.exitCode = 1;
      return;
    }
    await adb.closeAll().catch(() => {});
    process.stdout.write(RESULT_PREFIX + JSON.stringify({ ok: false, error: String((err && err.message) || err) }) + '\n');
    process.exitCode = 1;
  });
