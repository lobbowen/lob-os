#!/usr/bin/env node
'use strict';

const fs = require('fs');
const path = require('path');
const { packBundle, DEFAULT_ABI } = require('../container/engine/src/program-bundle');
const { loadPrivateKey } = require('../container/engine/src/keys');
const { sha256 } = require('../container/engine/src/verify');

function main() {
  const srcDir = process.argv[2];
  const version = process.argv[3];
  const abi = process.argv[4] || process.env.PROGRAM_ABI || DEFAULT_ABI;
  const urlBase = process.argv[5] || process.env.OTA_URL_BASE || '';

  if (!srcDir || !version) {
    console.error('用法: scripts/build-program-bundle.js <program-src-dir> <version> [abi] [url-base]');
    console.error('环境变量: LOBOS_BUNDLE_OUT_DIR 可指定输出目录（默认 <repo>/release）');
    process.exit(2);
  }
  if (!fs.existsSync(srcDir)) {
    console.error('内核源码目录不存在: ' + srcDir);
    process.exit(1);
  }

  const privateKey = loadPrivateKey();
  const url = urlBase ? urlBase.replace(/\/$/, '') + `/program-${version}.zip` : '';
  const requiresProtocol = process.env.LOBOS_PROGRAM_REQUIRES_PROTOCOL || '0';
  const requires = (() => {
    try {
      const mf = JSON.parse(fs.readFileSync(path.join(srcDir, "manifest.json"), "utf8"));
      return Array.isArray(mf.requires) ? mf.requires : [];
    } catch (_e) { return []; }
  })();
  const { zipBuf, manifest } = packBundle({
    srcDir, version, abi, privateKeyPem: privateKey, url, requiresProtocol, requires,
  });

  const actualSha = sha256(zipBuf);
  const manifestOut = Object.assign({}, manifest, { sha256: actualSha });

  const outDir = process.env.LOBOS_BUNDLE_OUT_DIR
    ? path.resolve(process.env.LOBOS_BUNDLE_OUT_DIR)
    : path.resolve(__dirname, '..', '..', '..', 'release');
  fs.mkdirSync(outDir, { recursive: true });
  const zipPath = path.join(outDir, `program-${version}.zip`);
  fs.writeFileSync(zipPath, zipBuf);
  const manifestPath = path.join(outDir, 'program-manifest.json');
  fs.writeFileSync(manifestPath, JSON.stringify(manifestOut, null, 2));

  console.log('内核包: ' + zipPath + ' (' + zipBuf.length + ' bytes)');
  console.log('清单  : ' + manifestPath);
  console.log('sha256: ' + actualSha);
  console.log('签名  : ' + manifest.signature.slice(0, 32) + '…');
  console.log('url   : ' + url);
}

main();
