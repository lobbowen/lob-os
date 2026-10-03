#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const crypto = require('node:crypto');
const { execFileSync } = require('node:child_process');
const { ZipInputStream } = (() => { try { return require('node:zlib'); } catch (e) { return {}; } })();

const MANIFEST_URL = process.argv[2];
const ROOT_ARG = process.argv[3];
const ROOT = ROOT_ARG
  ? path.resolve(ROOT_ARG)
  : path.join(os.homedir(), 'usr', 'lib', 'toolchain');
const PREFIX = path.dirname(path.dirname(ROOT));
const BIN = path.join(PREFIX, 'bin');
const FETCH_TIMEOUT_MS = 30000;

if (!MANIFEST_URL) {
  console.error('用法: node tools/rehearse-supply.js <清单URL> [落点根]');
  process.exit(2);
}

function httpGet(url) {
  const { execFileSync: ex } = require('node:child_process');
  return ex('curl', ['-fsS', '--max-time', String(Math.ceil(FETCH_TIMEOUT_MS / 1000)), url], { maxBuffer: 256 * 1024 * 1024 });
}

function listEntries(zipPath) {
  const r = require('node:child_process').spawnSync('unzip', ['-l', zipPath], { maxBuffer: 256 * 1024 * 1024 });
  if (r.status !== 0) throw new Error('unzip -l 失败: ' + String(r.stderr || '').slice(0, 160));
  const out = [];
  for (const line of r.stdout.toString('utf8').split('\n')) {
    const m = /^\s*\d+\s+\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}\s+(.+)$/.exec(line);
    if (m) out.push(m[1].trim());
  }
  if (!out.length) throw new Error('unzip -l 没解析出条目名（BusyBox 版 unzip 的输出格式可能不同）');
  return out;
}

function unzipInto(zipPath, dest) {
  fs.mkdirSync(dest, { recursive: true });
  const destRoot = fs.realpathSync(dest);
  for (const name of listEntries(zipPath)) {
    if (!name) continue;
    const out = path.resolve(dest, name);
    if (out !== destRoot && !out.startsWith(destRoot + path.sep)) {
      throw new Error('包条目路径越界（疑似目录穿越）: ' + name);
    }
    const isDir = name.endsWith('/');
    if (isDir) { fs.mkdirSync(out, { recursive: true }); continue; }
    fs.mkdirSync(path.dirname(out), { recursive: true });
    const buf = ex_unzip(['-p', zipPath, name]);
    fs.writeFileSync(out, buf);
    chmodIfExecutable(out);
  }
}

function ex_unzip(args) {
  const r = require('node:child_process').spawnSync('unzip', args, { maxBuffer: 256 * 1024 * 1024 });
  if (r.status !== 0) throw new Error('unzip ' + args.join(' ') + ' 失败: ' + String(r.stderr || '').slice(0, 160));
  return r.stdout;
}

function chmodIfExecutable(f) {
  const fd = fs.openSync(f, 'r');
  const head = Buffer.alloc(4);
  fs.readSync(fd, head, 0, 4, 0);
  fs.closeSync(fd);
  const magic = head.toString('latin1');
  if (head[0] === 0x7f && magic.slice(1) === 'ELF') fs.chmodSync(f, 0o755);
  else if (magic.startsWith('#!')) fs.chmodSync(f, 0o755);
}

function applyLinkFarm(root) {
  const farm = path.join(root, 'link-farm.txt');
  if (!fs.existsSync(farm)) return 0;
  const inside = fs.realpathSync(root) + path.sep;
  let applied = 0;
  for (const line of fs.readFileSync(farm, 'utf8').split('\n')) {
    if (!line || line.startsWith('#')) continue;
    const parts = line.split('\t');
    if (parts.length !== 2) continue;
    const rel = parts[0].trim(), target = parts[1].trim();
    if (!rel || !target) continue;
    try {
      const dst = path.join(root, rel);
      const resolved = fs.realpathSync(path.join(path.dirname(dst), target));
      if (!resolved.startsWith(inside) || resolved === fs.realpathSync(root)) continue;
      fs.mkdirSync(path.dirname(dst), { recursive: true });
      try { fs.unlinkSync(dst); } catch (e) { }
      fs.symlinkSync(target, dst);
      applied++;
    } catch (e) { }
  }
  return applied;
}

function farmBroken(root) {
  const farm = path.join(root, 'link-farm.txt');
  if (!fs.existsSync(farm)) return 0;
  let broken = 0;
  for (const line of fs.readFileSync(farm, 'utf8').split('\n')) {
    if (!line || line.startsWith('#')) continue;
    const rel = line.split('\t')[0].trim();
    if (!rel) continue;
    const f = path.join(root, rel);
    try {
      const st = fs.lstatSync(f);
      if (!st.isSymbolicLink()) { broken++; continue; }
      fs.statSync(f);
    } catch (e) { broken++; }
  }
  return broken;
}

function linkEntry(root, name, entryRel, aliases) {
  const entry = path.join(root, entryRel);
  fs.mkdirSync(BIN, { recursive: true });
  const link = path.join(BIN, name);
  try { fs.unlinkSync(link); } catch (e) { }
  fs.symlinkSync(entry, link);
  for (const a of aliases || []) {
    const la = path.join(BIN, a.name);
    try { fs.unlinkSync(la); } catch (e) { }
    fs.symlinkSync(path.join(root, a.entry), la);
  }
}

const main = () => {
  console.log('[rehearse] 清单: ' + MANIFEST_URL);
  const body = httpGet(MANIFEST_URL);
  const man = JSON.parse(body.toString('utf8'));
  const expires = Number(man.expiresEpochMs || 0);
  if (!(expires > 0)) {
    console.log('[rehearse] 清单缺 expiresEpochMs（必填字段缺失即拒绝）');
    process.exit(1);
  }
  if (Date.now() > expires) {
    console.log('[rehearse] 清单已过期（过期于 ' + new Date(expires).toISOString() + '），拒装');
    process.exit(1);
  }
  const tools = man.tools || [];
  console.log('[rehearse] 声明 ' + tools.length + ' 件，channel=' + man.channel + ' revision=' + man.revision
    + '，清单 ' + Math.round((expires - Date.now()) / 86400000) + ' 天后过期');
  fs.mkdirSync(ROOT, { recursive: true });

  const short = [];
  let available = 0;
  for (const t of tools) {
    const name = t.name, url = t.url, want = t.sha256, entryRel = t.entry || ('bin/' + name);
    if (!name || !url || !want) { short.push(name + '（字段缺失）'); continue; }
    const marker = path.join(ROOT, '.' + name + '.ok');
    const root = path.join(ROOT, name);
    if (fs.existsSync(marker) && fs.readFileSync(marker, 'utf8').trim() === want && fs.existsSync(path.join(root, entryRel))) {
      const broken = farmBroken(root);
      if (broken > 0) { short.push(name + '（农场 ' + broken + ' 条不可解析）'); continue; }
      linkEntry(root, name, entryRel, t.aliases);
      if (!fs.existsSync(path.join(BIN, name))) { short.push(name + '（真名调不到）'); continue; }
      const entryReal = fs.statSync(path.join(BIN, name));
      if (!entryReal.isFile()) { short.push(name + '（真名不是普通文件，链可能悬空）'); continue; }
      for (const a of t.aliases || []) {
        if (!fs.existsSync(path.join(BIN, a.name))) { short.push(name + '（别名 ' + a.name + ' 不可用）'); break; }
      }
      if (/^blob:/.test(String(fs.readFileSync(path.join(BIN, name), 'utf8').slice(0, 4)))) {
        short.push(name + '（真名链指向的不是可执行内容）'); continue;
      }
      const entrySha = crypto.createHash('sha256').update(fs.readFileSync(path.join(BIN, name))).digest('hex');
      const shaFile = path.join(ROOT, '.' + name + '.entry.sha256');
      if (fs.existsSync(shaFile) && fs.readFileSync(shaFile, 'utf8').trim() !== entrySha) {
        short.push(name + '（落位入口内容与上次不符（sha ' + entrySha.slice(0, 12) + '）—— 装完被改动过，删 .' + name + '.ok 触发重装）');
        continue;
      }
      available++;
      console.log('[rehearse]   ' + name + ' 命中 marker，复验通过（入口 ' + entryRel + '，sha ' + entrySha.slice(0, 12) + '）');
      continue;
    }
    const staging = path.join(ROOT, '.' + name + '.staging');
    try {
      const bytes = httpGet(url);
      const got = crypto.createHash('sha256').update(bytes).digest('hex');
      if (got !== want) { short.push(name + '（sha256 不符 ' + got.slice(0, 12) + ' != ' + want.slice(0, 12) + '）'); continue; }
      const tmpZip = path.join(os.tmpdir(), name + '.zip');
      fs.writeFileSync(tmpZip, bytes);
      fs.rmSync(staging, { recursive: true, force: true });
      unzipInto(tmpZip, staging);
      fs.unlinkSync(tmpZip);
      if (!fs.existsSync(path.join(staging, entryRel))) { short.push(name + '（缺入口 ' + entryRel + '）'); continue; }
      applyLinkFarm(staging);
      const brokenNew = farmBroken(staging);
      const retired = path.join(ROOT, '.' + name + '.retired');
      const hadOld = fs.existsSync(root) && fs.statSync(root).isDirectory();
      if (hadOld) {
        fs.rmSync(retired, { recursive: true, force: true });
        try { fs.renameSync(root, retired); } catch (e) {
          fs.rmSync(staging, { recursive: true, force: true });
          short.push(name + '（旧件挪不开，已保留原样）');
          continue;
        }
      }
      try {
        fs.renameSync(staging, root);
      } catch (e) {
        fs.rmSync(staging, { recursive: true, force: true });
        if (hadOld) {
          try { fs.renameSync(retired, root); short.push(name + '（落位失败，已回退到旧版）'); }
          catch (e2) { short.push(name + '（落位失败且回退失败）'); }
        } else {
          short.push(name + '（落位失败）');
        }
        continue;
      }
      fs.rmSync(retired, { recursive: true, force: true });
      fs.writeFileSync(marker, want);
      if (brokenNew > 0) { short.push(name + '（农场 ' + brokenNew + ' 条没建成）'); continue; }
      linkEntry(root, name, entryRel, t.aliases);
      const link = path.join(BIN, name);
      if (!fs.existsSync(link)) { short.push(name + '（真名调不到）'); continue; }
      for (const a of t.aliases || []) {
        if (!fs.existsSync(path.join(BIN, a.name))) { short.push(name + '（别名 ' + a.name + ' 不可用）'); break; }
      }
      const entrySha = crypto.createHash('sha256').update(fs.readFileSync(link)).digest('hex');
      fs.writeFileSync(path.join(ROOT, '.' + name + '.entry.sha256'), entrySha);
      available++;
      console.log('[rehearse]   ' + name + ' 装成：' + fs.statSync(link).size + ' 字节入口=' + entryRel + ' sha=' + entrySha.slice(0, 12));
    } catch (e) {
      fs.rmSync(staging, { recursive: true, force: true });
      short.push(name + '（' + (e.message || e).toString().slice(0, 120) + '）');
    }
  }

  const balanced = short.length === 0;
  console.log('[rehearse] 对账：声明 ' + tools.length + ' 件，可用 ' + available + ' 件 —— ' + (balanced ? '平' : '不平'));
  if (!balanced) for (const s of short) console.log('[rehearse]   缺: ' + s);
  console.log('[rehearse] 落点 ' + ROOT + '，真名入口 ' + BIN);
  process.exit(balanced ? 0 : 1);
};

main();
