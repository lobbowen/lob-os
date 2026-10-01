'use strict';

const crypto = require('node:crypto');
const fs = require('node:fs');

const P32 = 1n << 32n;
const DIGEST_INFO_SHA1 = Buffer.from('3021300906052b0e03021a05000414', 'hex');

function egcd(a, b) { if (b === 0n) return [a, 1n, 0n]; const [g, x, y] = egcd(b, a % b); return [g, y, x - (a / b) * y]; }
function modInv(a, m) { const [g, x] = egcd(((a % m) + m) % m, m); if (g !== 1n) throw new Error('RSA: 模逆不存在'); return ((x % m) + m) % m; }
function toLe(v, len) { const o = Buffer.alloc(len); let t = v; for (let i = 0; i < len; i++) { o[i] = Number(t & 0xffn); t >>= 8n; } return o; }
function b64uToBig(s) { return BigInt('0x' + Buffer.from(s, 'base64url').toString('hex')); }

function publicKeyBlob(jwk) {
  const n = b64uToBig(jwk.n);
  const e = BigInt('0x' + Buffer.from(jwk.e, 'base64url').toString('hex'));
  const n0inv = (P32 - modInv(n % P32, P32)) % P32;
  const rr = (1n << 4096n) % n;
  const blob = Buffer.alloc(524);
  toLe(64n, 4).copy(blob, 0);
  toLe(n0inv, 4).copy(blob, 4);
  toLe(n, 256).copy(blob, 8);
  toLe(rr, 256).copy(blob, 264);
  toLe(e, 4).copy(blob, 520);
  return blob;
}

function generate(name) {
  const { publicKey, privateKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 2048, publicExponent: 0x10001 });
  return {
    privatePem: privateKey.export({ type: 'pkcs8', format: 'pem' }),
    jwk: publicKey.export({ format: 'jwk' }),
    name: name || 'lobos@device',
  };
}

function loadOrCreate(file, name) {
  if (fs.existsSync(file)) {
    const privatePem = fs.readFileSync(file, 'utf8');
    const jwk = crypto.createPublicKey(privatePem).export({ format: 'jwk' });
    return { privatePem, jwk, name: name || 'lobos@device' };
  }
  const k = generate(name);
  fs.writeFileSync(file, k.privatePem, { mode: 0o600 });
  return k;
}

function pubkeyString(key, name) {
  return publicKeyBlob(key.jwk).toString('base64') + ' ' + (name || key.name || 'lobos@device');
}

function signToken(key, token) {
  if (token.length !== 20) throw new Error('ADB token 必须 20 字节');
  const em = Buffer.alloc(256);
  em[0] = 0; em[1] = 1;
  const sep = 256 - token.length - DIGEST_INFO_SHA1.length - 1;
  em.fill(0xff, 2, sep);
  em[sep] = 0;
  DIGEST_INFO_SHA1.copy(em, sep + 1);
  token.copy(em, 256 - token.length);
  return crypto.privateDecrypt({ key: key.privatePem, padding: crypto.constants.RSA_NO_PADDING }, em);
}

module.exports = { generate, loadOrCreate, publicKeyBlob, pubkeyString, signToken };
