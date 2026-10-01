#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { signManifest } = require(path.join(__dirname, '..', 'container', 'engine', 'src', 'sign'));

const [, , manifestPath, keyPath, rolloutArg] = process.argv;
if (!manifestPath || !keyPath) {
  console.error('用法: sign-program-manifest.js <manifest.json> <private-key.pem> [rolloutPercent]');
  process.exit(2);
}

const key = fs.readFileSync(keyPath, 'utf8');
const m = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));

m.manifestSchema = 1;
m.sequence = Math.floor(Date.now() / 1000);

const days = Number(process.env.LOBOS_MANIFEST_TTL_DAYS || 30);
const expMs = Date.now() + (Number.isFinite(days) ? days : 30) * 86400_000;
m.expires = new Date(expMs).toISOString();
m.expiresEpochMs = expMs;

const rp = Number(rolloutArg || process.env.LOBOS_ROLLOUT_PERCENT || 100);
m.rolloutPercent = Math.max(0, Math.min(100, Number.isFinite(rp) ? rp : 100));

m.signature = signManifest(key, m);
fs.writeFileSync(manifestPath, JSON.stringify(m, null, 2) + '\n');

console.log('[manifest] schema=' + m.manifestSchema + ' sequence=' + m.sequence +
  ' rollout=' + m.rolloutPercent + '% expires=' + m.expires);
console.log('[manifest] signature=' + m.signature.slice(0, 24) + '…');
