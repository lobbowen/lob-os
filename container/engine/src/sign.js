'use strict';

const crypto = require('crypto');

function canonical(obj) {
  const { signature, ...rest } = obj;
  return JSON.stringify(canonValue(rest));
}

function canonValue(v) {
  if (Array.isArray(v)) return v.map(canonValue);
  if (v && typeof v === 'object') {
    const out = {};
    for (const k of Object.keys(v).sort()) out[k] = canonValue(v[k]);
    return out;
  }
  return v;
}

function signManifest(privateKeyPem, manifestJson) {
  const data = Buffer.from(canonical(manifestJson), 'utf8');
  const sig = crypto.sign(null, data, privateKeyPem);
  return sig.toString('base64');
}

module.exports = { canonical, signManifest };
