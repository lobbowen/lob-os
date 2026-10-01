'use strict';

const crypto = require('crypto');

function canonical(obj) {
  const { signature, ...rest } = obj;
  return JSON.stringify(rest, Object.keys(rest).sort());
}

function signManifest(privateKeyPem, manifestJson) {
  const data = Buffer.from(canonical(manifestJson), 'utf8');
  const sig = crypto.sign(null, data, privateKeyPem);
  return sig.toString('base64');
}

module.exports = { canonical, signManifest };
