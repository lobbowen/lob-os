'use strict';

const crypto = require('node:crypto');

function derLen(n) {
  if (n < 0x80) return Buffer.from([n]);
  const b = []; let t = n;
  while (t > 0) { b.unshift(t & 0xff); t = Math.floor(t / 256); }
  return Buffer.from([0x80 | b.length, ...b]);
}
function tlv(tag, content) { return Buffer.concat([Buffer.from([tag]), derLen(content.length), content]); }
function seq(items) { return tlv(0x30, Buffer.concat(items)); }
function setOf(items) { return tlv(0x31, Buffer.concat(items)); }
function integer(bytes) {
  let b = Buffer.from(bytes);
  let i = 0; while (i < b.length - 1 && b[i] === 0) i++;
  b = b.subarray(i);
  if (b.length === 0) b = Buffer.from([0]);
  if (b[0] & 0x80) b = Buffer.concat([Buffer.from([0]), b]);
  return tlv(0x02, b);
}
function oid(hexStr) { return tlv(0x06, Buffer.from(hexStr, 'hex')); }
function utf8(s) { return tlv(0x0c, Buffer.from(s, 'utf8')); }
function utcTime(d) {
  const s = d.toISOString().replace(/[-:T]/g, '').slice(2).replace(/\.\d{3}/, '');
  return tlv(0x17, Buffer.from(s, 'ascii'));
}
function rdn(cn) { return seq([setOf([seq([oid('550403'), utf8(cn)])])]); }

function toPem(label, der) {
  const b64 = der.toString('base64').replace(/(.{64})/g, '$1\n').replace(/\n$/, '');
  return '-----BEGIN ' + label + '-----\n' + b64 + '\n-----END ' + label + '-----\n';
}

function buildCert(cn, publicKey, privateKey) {
  const spki = publicKey.export({ type: 'spki', format: 'der' });
  const sigAlg = seq([oid('2a864886f70d01010b'), Buffer.from([0x05, 0x00])]);
  const now = new Date();
  const nb = new Date(now.getTime() - 3600 * 1000);
  const na = new Date(now.getTime() + 10 * 365 * 24 * 3600 * 1000);
  const serial = crypto.randomBytes(8); serial[0] &= 0x7f;
  const tbs = seq([integer(serial), sigAlg, rdn(cn), seq([utcTime(nb), utcTime(na)]), rdn(cn), spki]);
  const sig = crypto.sign('sha256', tbs, privateKey);
  const cert = seq([tbs, sigAlg, tlv(0x03, Buffer.concat([Buffer.from([0]), sig]))]);
  return { certDer: cert, certPem: toPem('CERTIFICATE', cert), keyPem: privateKey.export({ type: 'pkcs8', format: 'pem' }) };
}

function generateSelfSigned(cn) {
  const { publicKey, privateKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 2048, publicExponent: 0x10001 });
  return buildCert(cn, publicKey, privateKey);
}

function selfSignedFromKey(cn, privateKey) {
  const keyObj = typeof privateKey === 'string' ? crypto.createPrivateKey(privateKey) : privateKey;
  return buildCert(cn, crypto.createPublicKey(keyObj), keyObj);
}

module.exports = { generateSelfSigned, selfSignedFromKey };
