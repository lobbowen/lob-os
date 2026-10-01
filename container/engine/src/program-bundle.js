'use strict';

const fs = require('fs');
const path = require('path');
const { createZip } = require('./zip');
const { signManifest } = require('./sign');
const { sha256 } = require('./verify');

const DEFAULT_ENTRY = 'bin/panel';
const DEFAULT_ABI = 'node24-arm64-android35';
const DEFAULT_ENGINES = { node: '>=24 <25' };

function readSourceManifest(srcDir) {
  try {
    return JSON.parse(fs.readFileSync(path.join(srcDir, 'manifest.json'), 'utf8'));
  } catch (_e) {
    return null;
  }
}

const CARRIED_FIELDS = ['id', 'name', 'role', 'engines', 'entry', 'args', 'ports', 'capabilities',
  'env', 'lifecycle', 'storage', 'resource', 'ui', 'requires', 'programs', 'requiresProtocol'];

function buildKernelJson(o) {
  const src = (o && o.src) || null;
  const pick = (k) => (o && o[k] !== undefined ? o[k] : (src ? src[k] : undefined));
  const out = { version: o && o.version };
  for (const k of CARRIED_FIELDS) {
    const v = pick(k);
    if (v !== undefined) out[k] = v;
  }
  out.abi = (o && o.abi) || DEFAULT_ABI;
  out.engines = out.engines || DEFAULT_ENGINES;
  out.requires = out.requires || [];
  out.programs = out.programs || [];
  out.requiresProtocol = Number(out.requiresProtocol || 0);
  const idVal = out.id || out.name;
  const displayVal = out.displayName || (out.name && out.name !== idVal ? out.name : undefined);
  if (idVal !== undefined) {
    out.id = idVal;
    out.name = idVal;
  }
  if (displayVal !== undefined) out.displayName = displayVal;
  return out;
}

const EXCLUDED_SEGMENTS = new Set([
  'node_modules',
  '.git',
  '.github',
  '.pnpm-store',
  '__pycache__',
  '.cache',
  '.turbo',
  '.parcel-cache',
  'coverage',
]);

const EXCLUDED_DIRS = new Set([
  'test',
  'tests',
  '__tests__',
  'docs',
]);

const EXCLUDED_EXT = ['.md', '.map', '.log', '.tsbuildinfo'];

function isExcluded(rel) {
  const segs = rel.split('/');
  if (segs.some((s) => EXCLUDED_SEGMENTS.has(s))) return true;
  if (segs.length > 1 && EXCLUDED_DIRS.has(segs[0])) return true;
  if (EXCLUDED_EXT.some((e) => rel.endsWith(e))) return true;
  return false;
}

function collectFiles(srcDir) {
  const out = new Map();
  let skipped = 0;
  (function walk(d) {
    for (const e of fs.readdirSync(d, { withFileTypes: true })) {
      const full = path.join(d, e.name);
      const rel = path.relative(srcDir, full).split(path.sep).join('/');
      if (isExcluded(rel)) { skipped += 1; continue; }
      if (e.isDirectory()) walk(full);
      else out.set(rel, fs.readFileSync(full));
    }
  })(srcDir);
  if (skipped) {
    process.stderr.write('[program-bundle] 已跳过 ' + skipped + ' 个被排除的路径\n');
  }
  return out;
}

function packBundle(o) {
  const src = readSourceManifest(o.srcDir);
  const legacy = src === null;
  const manifestJson = buildKernelJson(Object.assign({}, o, { src }));
  if (legacy) {
    manifestJson.name = manifestJson.name || manifestJson.id || 'lobos-program';
    manifestJson.id = manifestJson.id || manifestJson.name;
    manifestJson.entry = manifestJson.entry || DEFAULT_ENTRY;
    if (!manifestJson.args) manifestJson.args = [];
  } else {
    if (!manifestJson.id) {
      throw new Error('程序清单缺少 id/name（<程序源目录>/manifest.json）；内核不猜程序身份');
    }
    if (!manifestJson.entry) {
      throw new Error('程序清单缺少 entry（' + o.srcDir + '/manifest.json）；内核不再替应用猜入口');
    }
    if (!manifestJson.args) {
      throw new Error('程序清单缺少 args（可为空数组，' + o.srcDir + '/manifest.json）；内核不再替应用猜启动参数');
    }
  }
  manifestJson.signature = signManifest(o.privateKeyPem, manifestJson);

  const files = collectFiles(o.srcDir);
  const prefix = `program/${manifestJson.version}/`;
  const zipFiles = new Map();
  for (const [rel, buf] of files) zipFiles.set(prefix + rel, buf);
  zipFiles.set(prefix + 'program-manifest.json', Buffer.from(JSON.stringify(manifestJson, null, 2)));

  const zipBuf = createZip(zipFiles, { compress: true });

  const HARD_LIMIT = 8 * 1024 * 1024;
  const WARN_LIMIT = 2 * 1024 * 1024;
  if (zipBuf.length > HARD_LIMIT) {
    throw new Error(
      '内核包体积 ' + (zipBuf.length / 1048576).toFixed(1) + ' MB 超过硬上限 ' +
      (HARD_LIMIT / 1048576) + ' MB。最可能的原因：EXCLUDED_SEGMENTS 漏了某个目录。' +
      '（本包收录 ' + files.size + ' 个文件，请检查内核源码目录里是否有 node_modules / 构建缓存）'
    );
  }
  if (zipBuf.length > WARN_LIMIT) {
    process.stderr.write(
      '[program-bundle] 警告：内核包 ' + (zipBuf.length / 1048576).toFixed(1) +
      ' MB 偏大（软阈值 ' + (WARN_LIMIT / 1048576) + ' MB，收录 ' + files.size + ' 个文件）\n'
    );
  }

  const manifest = {
    id: manifestJson.id,
    name: manifestJson.name || manifestJson.id,
    role: manifestJson.role,
    version: manifestJson.version,
    abi: manifestJson.abi,
    engines: manifestJson.engines,
    requires: manifestJson.requires,
    requiresProtocol: manifestJson.requiresProtocol,
    url: o.url || '',
    sha256: sha256(zipBuf),
    signature: manifestJson.signature,
  };
  return { zipBuf, manifestJson, manifest, fileCount: files.size };
}

module.exports = {
  DEFAULT_ENTRY, DEFAULT_ABI, DEFAULT_ENGINES,
  buildKernelJson, collectFiles, packBundle,
  isExcluded, EXCLUDED_SEGMENTS, EXCLUDED_DIRS, EXCLUDED_EXT,
};
