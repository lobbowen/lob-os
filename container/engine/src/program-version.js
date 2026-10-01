'use strict';

const TOKEN = /\d+|\D+/g;

function tokens(v) {
  return String(v == null ? '' : v).match(TOKEN) || [];
}

function compare(a, b) {
  const ta = tokens(a);
  const tb = tokens(b);
  const n = Math.max(ta.length, tb.length);
  for (let i = 0; i < n; i += 1) {
    const x = ta[i];
    const y = tb[i];
    if (x === undefined) return -1;
    if (y === undefined) return 1;
    const nx = /^\d+$/.test(x) ? Number(x) : null;
    const ny = /^\d+$/.test(y) ? Number(y) : null;
    let c;
    if (nx !== null && ny !== null) c = nx === ny ? 0 : (nx > ny ? 1 : -1);
    else if (nx !== null) c = 1;
    else if (ny !== null) c = -1;
    else c = x === y ? 0 : (x > y ? 1 : -1);
    if (c !== 0) return c;
  }
  return 0;
}

function isNewer(remote, current) {
  if (!current) return true;
  return compare(remote, current) > 0;
}

module.exports = { compare, isNewer };
