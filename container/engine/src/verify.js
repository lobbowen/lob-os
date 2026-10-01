'use strict';

const crypto = require('crypto');
const { canonical } = require('./sign');

function verifyManifest(publicKeyPem, manifestJson, signatureB64) {
  if (!signatureB64) return false;
  try {
    const data = Buffer.from(canonical(manifestJson), 'utf8');
    const sig = Buffer.from(signatureB64, 'base64');
    return crypto.verify(null, data, publicKeyPem, sig);
  } catch (_e) {
    return false;
  }
}

function sha256(buf) {
  return crypto.createHash('sha256').update(buf).digest('hex');
}

module.exports = { verifyManifest, sha256 };
