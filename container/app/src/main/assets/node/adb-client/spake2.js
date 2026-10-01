'use strict';

const crypto = require('node:crypto');
const Ed = require('./ed25519');

const NAME_CLIENT = Buffer.from('adb pair client\u0000', 'latin1');
const NAME_SERVER = Buffer.from('adb pair server\u0000', 'latin1');

function sha512(b) { return crypto.createHash('sha512').update(b).digest(); }
function lenPrefixed(b) {
  const l = Buffer.alloc(8);
  l.writeBigUInt64LE(BigInt(b.length), 0);
  return Buffer.concat([l, b]);
}

function passwordScalar(password) {
  let s = Ed.scReduce(sha512(password));
  if (s & 1n) s += Ed.L;
  if (s & 2n) s += 2n * Ed.L;
  if (s & 4n) s += 4n * Ed.L;
  return s;
}

function generateMsg(role, password, randomBytes64) {
  const rnd = randomBytes64 || crypto.randomBytes(64);
  if (rnd.length !== 64) throw new Error('spake2 random 必须 64 字节');
  const priv = Ed.scReduce(rnd) * 8n;
  const P = Ed.mulScalar(priv, Ed.B);
  const pwHash = sha512(password);
  const pwScalar = passwordScalar(password);
  const mask = Ed.mulScalar(pwScalar, role === 'alice' ? Ed.M : Ed.N);
  return { msg: Ed.encode(Ed.add(P, mask)), priv, pwHash, pwScalar, role };
}

function processMsg(state, theirMsg) {
  const Qstar = Ed.decode(theirMsg);
  if (Qstar === null) return null;
  const peersMask = Ed.mulScalar(state.pwScalar, state.role === 'alice' ? Ed.N : Ed.M);
  const Qext = Ed.sub(Qstar, peersMask);
  const dhEnc = Ed.encode(Ed.mulScalar(state.priv, Qext));

  const myName = state.role === 'alice' ? NAME_CLIENT : NAME_SERVER;
  const theirName = state.role === 'alice' ? NAME_SERVER : NAME_CLIENT;
  const parts = state.role === 'alice'
    ? [myName, theirName, state.msg, theirMsg]
    : [theirName, myName, theirMsg, state.msg];

  const h = crypto.createHash('sha512');
  for (const p of parts) h.update(lenPrefixed(Buffer.from(p)));
  h.update(lenPrefixed(dhEnc));
  h.update(lenPrefixed(state.pwHash));
  return h.digest();
}

module.exports = { generateMsg, processMsg };
